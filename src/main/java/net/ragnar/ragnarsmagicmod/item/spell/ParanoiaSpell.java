package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.block.AbstractCandleBlock;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.CampfireBlock;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.BlockBreakingProgressS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockEventS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.network.TestEntityPayload;
import net.ragnar.ragnarsmagicmod.util.Aim;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Tome of Paranoia. Haunts the player you're looking at (or yourself, looking straight down) for 60 seconds. Every few
 * seconds something happens that only they can hear or see: footsteps creeping up behind them, cave noises, a door
 * nearby creaking open and slamming shut, a chest lid lifting, knocking, a whisper at their shoulder, the lights going
 * out one by one, something digging through the wall, levers and buttons flicking on their own. Nothing in the world
 * actually changes, and nobody else notices a thing - including the fact that a spell was cast at them.
 *
 * Now and then they also catch sight of Test::Entity, a faceless Steve with a name tag (see TestEntityClient): a
 * split second outside the window, staring from far away, standing right behind them, creeping closer whenever they
 * look away... and very rarely, right in their face.
 */
public class ParanoiaSpell implements Spell {
    private static final double RANGE = 32.0;
    private static final double AIM_CONE = 6.0;
    private static final float SELF_PITCH = 75.0f;
    private static final int DURATION = 20 * 60;
    private static final int SEARCH_RADIUS = 10;
    private static final int SEARCH_HEIGHT = 4;

    // Test::Entity
    private static final float SIGHTING_CHANCE = 0.18f;  // per event, once the haunting is underway
    private static final int MAX_SIGHTINGS = 2;          // per haunting
    private static final String ENTITY_NAME = "Test::Entity";
    private static final String[] WHISPERS = {"turn around", "behind you", "i can see you", "dont look"};

    private enum Event { FOOTSTEPS, CAVE, DOOR, CHEST, KNOCKING, WHISPER, LIGHTS_OUT, DIGGING, SWITCHES }

    private static final Event[] POOL = {
            Event.FOOTSTEPS, Event.FOOTSTEPS, Event.FOOTSTEPS,
            Event.CAVE, Event.CAVE,
            Event.DOOR, Event.DOOR, Event.DOOR,
            Event.CHEST, Event.CHEST,
            Event.KNOCKING, Event.KNOCKING,
            Event.WHISPER,
            Event.LIGHTS_OUT, Event.LIGHTS_OUT,
            Event.DIGGING, Event.DIGGING,
            Event.SWITCHES, Event.SWITCHES
    };

    private record Timed(int at, Consumer<ServerPlayerEntity> action) {}

    private static final class Haunt {
        final ServerWorld world;
        int age = 0;
        int endAt = DURATION;
        int nextEvent;
        Event last = null;
        int sightings = 0;
        boolean joined = false; // told them Test::Entity "joined the game"
        final List<Timed> queue = new ArrayList<>();
        /** Doors, lids, lights and switches we've faked on their screen, and how to put each back. */
        final Map<BlockPos, Consumer<ServerPlayerEntity>> fakes = new HashMap<>();

        Haunt(ServerWorld world, Random random) {
            this.world = world;
            this.nextEvent = 40 + random.nextInt(40); // not right away, so it doesn't feel tied to anything
        }

        void schedule(int delay, Consumer<ServerPlayerEntity> action) {
            queue.add(new Timed(age + delay, action));
        }
    }

    private static final Map<UUID, Haunt> HAUNTS = new HashMap<>();
    private static boolean registered = false;

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(ParanoiaSpell::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> HAUNTS.clear());
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        if (!(world instanceof ServerWorld sw) || !(player instanceof ServerPlayerEntity caster)) return false;
        ensureRegistered();

        ServerPlayerEntity target;
        if (player.getPitch() >= SELF_PITCH) {
            target = caster;
        } else {
            Entity aimed = Aim.target(sw, player, RANGE, AIM_CONE, e -> e instanceof ServerPlayerEntity);
            if (aimed == null) {
                player.sendMessage(Text.literal("Look at a player, or straight down to haunt yourself."), true);
                return false;
            }
            target = (ServerPlayerEntity) aimed;
        }

        Haunt existing = HAUNTS.get(target.getUuid());
        if (existing != null && existing.world == sw) {
            existing.endAt = existing.age + DURATION;
        } else {
            HAUNTS.put(target.getUuid(), new Haunt(sw, sw.random));
        }

        // Only the caster sees and hears the curse leave the staff
        Vec3d eye = caster.getEyePos();
        Vec3d look = caster.getRotationVector();
        for (int i = 1; i <= 6; i++) {
            Vec3d p = eye.add(look.multiply(i * 0.4)).add(0, -0.3, 0);
            sw.spawnParticles(caster, ParticleTypes.SCULK_SOUL, false, p.x, p.y, p.z, 1, 0.05, 0.05, 0.05, 0.01);
        }
        sound(caster, SoundEvents.PARTICLE_SOUL_ESCAPE, SoundCategory.PLAYERS, caster.getPos(), 1.0f, 0.6f);
        if (target != caster) {
            caster.sendMessage(Text.literal(target.getName().getString() + " is haunted..."), true);
        }
        return true;
    }

    private static void tick(MinecraftServer server) {
        Iterator<Map.Entry<UUID, Haunt>> it = HAUNTS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Haunt> e = it.next();
            Haunt h = e.getValue();
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
            if (p == null) { it.remove(); continue; } // logging out reloads everything, fakes included
            if (p.getServerWorld() != h.world) { it.remove(); continue; } // their screen reloaded the new world anyway
            if (!p.isAlive()) {
                end(h, p);
                it.remove();
                continue;
            }
            h.age++;

            List<Timed> due = new ArrayList<>();
            h.queue.removeIf(t -> {
                if (t.at() > h.age) return false;
                due.add(t);
                return true;
            });
            for (Timed t : due) t.action().accept(p);

            if (h.age >= h.nextEvent && h.age < h.endAt - 40) {
                haunt(h, p);
                h.nextEvent = h.age + 50 + p.getRandom().nextInt(60);
            }

            // Once it's over and everything has played out, leave nothing behind
            if (h.age >= h.endAt && (h.queue.isEmpty() || h.age >= h.endAt + 200)) {
                end(h, p);
                it.remove();
            }
        }
    }

    private static void end(Haunt h, ServerPlayerEntity p) {
        for (Consumer<ServerPlayerEntity> restore : new ArrayList<>(h.fakes.values())) restore.accept(p);
        h.fakes.clear();
        if (h.joined) p.sendMessage(Text.translatable("multiplayer.player.left", ENTITY_NAME).formatted(Formatting.YELLOW), false);
    }

    private static void haunt(Haunt h, ServerPlayerEntity p) {
        Random r = p.getRandom();
        if (h.age > 100 && h.sightings < MAX_SIGHTINGS && r.nextFloat() < SIGHTING_CHANCE && TestEntityPayload.canSend(p) && sighting(h, p)) {
            h.sightings++;
            return;
        }

        Event event;
        do event = POOL[r.nextInt(POOL.length)]; while (event == h.last);
        h.last = event;

        boolean happened = switch (event) {
            case FOOTSTEPS -> footsteps(h, p);
            case CAVE -> cave(p);
            case DOOR -> door(h, p);
            case CHEST -> chest(h, p);
            case KNOCKING -> knocking(h, p);
            case WHISPER -> whisper(p);
            case LIGHTS_OUT -> lightsOut(h, p);
            case DIGGING -> digging(h, p);
            case SWITCHES -> switches(h, p);
        };
        // Nothing of that kind around: someone's still following them
        if (!happened) footsteps(h, p);
    }

    // Test::Entity

    /** Picks how Test::Entity shows itself this time and where. False if there was nowhere for it to stand. */
    private static boolean sighting(Haunt h, ServerPlayerEntity p) {
        Random r = p.getRandom();
        int roll = r.nextInt(100);
        int mode;
        Vec3d at;
        int ticks;
        if (roll < 30) {
            mode = TestEntityPayload.GLIMPSE;
            at = outsideWindow(p);
            if (at == null) at = farAway(p, 12, 20);
            ticks = 6 + r.nextInt(4);
        } else if (roll < 55) {
            mode = TestEntityPayload.STARE;
            at = farAway(p, 16, 28);
            ticks = 20 * 10;
        } else if (roll < 72) {
            mode = TestEntityPayload.APPROACH;
            at = farAway(p, 18, 26);
            ticks = 20 * 20;
        } else if (roll < 90) {
            mode = TestEntityPayload.BEHIND;
            at = behind(p);
            ticks = 20 * 8;
        } else {
            mode = TestEntityPayload.JUMPSCARE;
            at = behind(p);
            ticks = 20 * 10;
        }
        if (at == null) return false;

        int delay = 0;
        if (!h.joined && r.nextBoolean()) {
            // The name in chat comes first; the thing itself a couple of seconds later
            h.joined = true;
            p.sendMessage(Text.translatable("multiplayer.player.joined", ENTITY_NAME).formatted(Formatting.YELLOW), false);
            delay = 40 + r.nextInt(40);
        }
        final int m = mode, t = ticks;
        final Vec3d pos = at;
        h.schedule(delay, player -> TestEntityPayload.send(player, m, pos, t));

        if ((mode == TestEntityPayload.BEHIND || mode == TestEntityPayload.JUMPSCARE) && r.nextInt(3) == 0) {
            String line = WHISPERS[r.nextInt(WHISPERS.length)];
            h.schedule(delay + 10, player -> player.sendMessage(Text.literal("<" + ENTITY_NAME + "> " + line), false));
        }
        // While it stares, the lights around them die
        if ((mode == TestEntityPayload.STARE || mode == TestEntityPayload.APPROACH) && r.nextInt(3) == 0) {
            h.schedule(delay + 20, player -> lightsOut(h, player));
        }
        return true;
    }

    /** Just outside a window they're facing, looking in. */
    private static Vec3d outsideWindow(ServerPlayerEntity p) {
        ServerWorld world = p.getServerWorld();
        Vec3d eye = p.getEyePos();
        Vec3d look = p.getRotationVector();
        BlockPos center = p.getBlockPos();
        List<Vec3d> spots = new ArrayList<>();
        for (BlockPos pos : BlockPos.iterate(center.add(-12, -3, -12), center.add(12, 3, 12))) {
            BlockState state = world.getBlockState(pos);
            if (!state.isIn(ConventionalBlockTags.GLASS_BLOCKS) && !state.isIn(ConventionalBlockTags.GLASS_PANES)) continue;
            Vec3d to = pos.toCenterPos().subtract(eye);
            if (to.lengthSquared() < 4.0 || to.normalize().dotProduct(look) < 0.6) continue;
            BlockHitResult hit = world.raycast(new RaycastContext(eye, pos.toCenterPos(),
                    RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, p));
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos)) continue; // can't see this pane

            Direction out = Direction.getFacing(to.x, 0, to.z);
            for (int k = 1; k <= 2; k++) {
                for (int dy = -1; dy <= 0; dy++) {
                    BlockPos feet = pos.offset(out, k).up(dy);
                    if (canStand(world, feet)) spots.add(Vec3d.ofBottomCenter(feet));
                }
            }
        }
        return spots.isEmpty() ? null : spots.get(p.getRandom().nextInt(spots.size()));
    }

    /** Somewhere on the ground in front of them, far off, in plain sight. */
    private static Vec3d farAway(ServerPlayerEntity p, double min, double max) {
        ServerWorld world = p.getServerWorld();
        Random r = p.getRandom();
        for (int attempt = 0; attempt < 25; attempt++) {
            float yaw = p.getYaw() + (r.nextFloat() - 0.5f) * 70f;
            double dist = min + r.nextDouble() * (max - min);
            Vec3d flat = p.getPos().add(Vec3d.fromPolar(0, yaw).multiply(dist));
            BlockPos feet = ground(world, BlockPos.ofFloored(flat.x, p.getY(), flat.z), 8);
            if (feet == null) continue;
            Vec3d at = Vec3d.ofBottomCenter(feet);
            if (clearView(world, p, at.add(0, 1.6, 0))) return at;
        }
        return null;
    }

    /** A couple of blocks behind them, where they'd see it if they turned around. */
    private static Vec3d behind(ServerPlayerEntity p) {
        ServerWorld world = p.getServerWorld();
        for (double dist = 2.5; dist >= 1.8; dist -= 0.35) {
            Vec3d flat = p.getPos().add(Vec3d.fromPolar(0, p.getYaw() + 180f).multiply(dist));
            BlockPos feet = ground(world, BlockPos.ofFloored(flat.x, p.getY(), flat.z), 2);
            if (feet == null) continue;
            Vec3d at = new Vec3d(flat.x, feet.getY(), flat.z);
            if (clearView(world, p, at.add(0, 1.6, 0))) return at;
        }
        return null;
    }

    // The events

    /** Slow steps coming up from behind, stopping just short of them. */
    private static boolean footsteps(Haunt h, ServerPlayerEntity p) {
        Vec3d back = Vec3d.fromPolar(0, p.getYaw() + 180f + (p.getRandom().nextFloat() - 0.5f) * 60f);
        Vec3d side = new Vec3d(-back.z, 0, back.x);
        int steps = 5 + p.getRandom().nextInt(3);
        for (int i = 0; i < steps; i++) {
            double dist = 9.0 - i * (6.5 / (steps - 1));
            double sway = (i % 2 == 0 ? 0.25 : -0.25);
            h.schedule(i * 7, player -> {
                Vec3d at = player.getPos().add(back.multiply(dist)).add(side.multiply(sway));
                BlockState ground = player.getServerWorld().getBlockState(BlockPos.ofFloored(at.x, player.getY() - 0.5, at.z));
                SoundEvent step = ground.isAir() ? SoundEvents.BLOCK_STONE_STEP : ground.getSoundGroup().getStepSound();
                sound(player, step, SoundCategory.HOSTILE, at, 0.35f, 0.85f);
            });
        }
        return true;
    }

    private static boolean cave(ServerPlayerEntity p) {
        Vec3d dir = Vec3d.fromPolar(0, p.getRandom().nextFloat() * 360f);
        Vec3d at = p.getEyePos().add(dir.multiply(10)).add(0, -3, 0);
        sound(p, SoundEvents.AMBIENT_CAVE, SoundCategory.AMBIENT, at, 0.9f, 0.8f + p.getRandom().nextFloat() * 0.3f);
        return true;
    }

    private static boolean whisper(ServerPlayerEntity p) {
        Vec3d at = p.getEyePos().add(Vec3d.fromPolar(0, p.getYaw() + 180f).multiply(1.5));
        sound(p, SoundEvents.AMBIENT_SOUL_SAND_VALLEY_MOOD, SoundCategory.AMBIENT, at, 0.7f, 0.9f);
        return true;
    }

    /** A closed door or trapdoor creaks slowly open... and a few seconds later slams shut. */
    private static boolean door(Haunt h, ServerPlayerEntity p) {
        ServerWorld world = p.getServerWorld();
        BlockPos feet = p.getBlockPos();
        BlockPos pos = find(h, p, (at, s) -> {
            if (s.getBlock() instanceof DoorBlock) return s.get(DoorBlock.HALF) == DoubleBlockHalf.LOWER && !s.get(DoorBlock.OPEN);
            // Never yank a trapdoor out from under their feet
            if (s.getBlock() instanceof TrapdoorBlock) return !s.get(TrapdoorBlock.OPEN) && (at.getY() >= feet.getY() || at.getManhattanDistance(feet) > 3);
            return false;
        });
        if (pos == null) return false;
        BlockPos key = pos.toImmutable();
        BlockState state = world.getBlockState(key);

        SoundEvent open, close;
        if (state.getBlock() instanceof DoorBlock door) {
            open = door.getBlockSetType().doorOpen();
            close = door.getBlockSetType().doorClose();
            BlockState upper = world.getBlockState(key.up());
            p.networkHandler.sendPacket(new BlockUpdateS2CPacket(key, state.with(DoorBlock.OPEN, true)));
            if (upper.isOf(door)) p.networkHandler.sendPacket(new BlockUpdateS2CPacket(key.up(), upper.with(DoorBlock.OPEN, true)));
        } else {
            boolean iron = state.isOf(Blocks.IRON_TRAPDOOR);
            open = iron ? SoundEvents.BLOCK_IRON_TRAPDOOR_OPEN : SoundEvents.BLOCK_WOODEN_TRAPDOOR_OPEN;
            close = iron ? SoundEvents.BLOCK_IRON_TRAPDOOR_CLOSE : SoundEvents.BLOCK_WOODEN_TRAPDOOR_CLOSE;
            p.networkHandler.sendPacket(new BlockUpdateS2CPacket(key, state.with(TrapdoorBlock.OPEN, true)));
        }
        sound(p, open, SoundCategory.BLOCKS, key.toCenterPos(), 0.8f, 0.55f);

        h.fakes.put(key, player -> {
            player.networkHandler.sendPacket(new BlockUpdateS2CPacket(world, key));
            player.networkHandler.sendPacket(new BlockUpdateS2CPacket(world, key.up()));
            sound(player, close, SoundCategory.BLOCKS, key.toCenterPos(), 1.0f, 0.75f);
        });
        h.schedule(60 + p.getRandom().nextInt(80), player -> restore(h, player, key));
        return true;
    }

    /** A chest or barrel lid lifts, as if someone is looking through it, then drops. */
    private static boolean chest(Haunt h, ServerPlayerEntity p) {
        ServerWorld world = p.getServerWorld();
        BlockPos pos = find(h, p, (at, s) -> s.getBlock() instanceof ChestBlock || (s.getBlock() instanceof BarrelBlock && !s.get(BarrelBlock.OPEN)));
        if (pos == null) return false;
        BlockPos key = pos.toImmutable();
        BlockState state = world.getBlockState(pos);

        if (state.getBlock() instanceof ChestBlock) {
            p.networkHandler.sendPacket(new BlockEventS2CPacket(key, state.getBlock(), 1, 1));
            sound(p, SoundEvents.BLOCK_CHEST_OPEN, SoundCategory.BLOCKS, key.toCenterPos(), 0.6f, 0.8f);
            h.fakes.put(key, player -> {
                int viewers = ChestBlockEntity.getPlayersLookingInChestCount(world, key);
                player.networkHandler.sendPacket(new BlockEventS2CPacket(key, world.getBlockState(key).getBlock(), 1, viewers));
                sound(player, SoundEvents.BLOCK_CHEST_CLOSE, SoundCategory.BLOCKS, key.toCenterPos(), 0.6f, 0.8f);
            });
        } else {
            p.networkHandler.sendPacket(new BlockUpdateS2CPacket(key, state.with(BarrelBlock.OPEN, true)));
            sound(p, SoundEvents.BLOCK_BARREL_OPEN, SoundCategory.BLOCKS, key.toCenterPos(), 0.6f, 0.8f);
            h.fakes.put(key, player -> {
                player.networkHandler.sendPacket(new BlockUpdateS2CPacket(world, key));
                sound(player, SoundEvents.BLOCK_BARREL_CLOSE, SoundCategory.BLOCKS, key.toCenterPos(), 0.6f, 0.8f);
            });
        }
        h.schedule(40 + p.getRandom().nextInt(40), player -> restore(h, player, key));
        return true;
    }

    /** Three slow knocks on a wooden door. */
    private static boolean knocking(Haunt h, ServerPlayerEntity p) {
        BlockPos pos = find(h, p, (at, s) -> s.getBlock() instanceof DoorBlock && DoorBlock.canOpenByHand(s) && s.get(DoorBlock.HALF) == DoubleBlockHalf.LOWER);
        if (pos == null) return false;
        Vec3d at = pos.toCenterPos().add(0, 0.5, 0);
        for (int i = 0; i < 3; i++) {
            float pitch = 0.75f + p.getRandom().nextFloat() * 0.1f;
            h.schedule(i * 12, player -> sound(player, SoundEvents.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, SoundCategory.HOSTILE, at, 0.35f, pitch));
        }
        return true;
    }

    /** The torches, candles and campfires around them snuff out one by one, closing in, then come back all at once. */
    private static boolean lightsOut(Haunt h, ServerPlayerEntity p) {
        ServerWorld world = p.getServerWorld();
        BlockPos center = p.getBlockPos();
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos pos : BlockPos.iterate(center.add(-SEARCH_RADIUS, -SEARCH_HEIGHT, -SEARCH_RADIUS), center.add(SEARCH_RADIUS, SEARCH_HEIGHT, SEARCH_RADIUS))) {
            if (!h.fakes.containsKey(pos) && unlit(world.getBlockState(pos)) != null) found.add(pos.toImmutable());
        }
        if (found.isEmpty()) return false;
        found.sort(Comparator.comparingDouble((BlockPos pos) -> pos.getSquaredDistance(center)).reversed());
        List<BlockPos> lights = found.subList(Math.max(0, found.size() - 14), found.size()); // the nearest ones, farthest first

        for (int i = 0; i < lights.size(); i++) {
            BlockPos pos = lights.get(i);
            h.schedule(i * 5, player -> {
                BlockState out = unlit(world.getBlockState(pos));
                if (out == null || h.fakes.containsKey(pos)) return;
                player.networkHandler.sendPacket(new BlockUpdateS2CPacket(pos, out));
                sound(player, SoundEvents.BLOCK_CANDLE_EXTINGUISH, SoundCategory.BLOCKS, pos.toCenterPos(), 0.7f, 0.8f);
                h.fakes.put(pos, pl -> pl.networkHandler.sendPacket(new BlockUpdateS2CPacket(world, pos)));
            });
        }
        // Everything flickers back on together
        h.schedule(lights.size() * 5 + 100 + p.getRandom().nextInt(60), player -> {
            boolean any = false;
            for (BlockPos pos : lights) {
                if (h.fakes.containsKey(pos)) { restore(h, player, pos); any = true; }
            }
            if (any) sound(player, SoundEvents.ITEM_FIRECHARGE_USE, SoundCategory.BLOCKS, player.getPos(), 0.4f, 0.6f);
        });
        return true;
    }

    /** What this light looks like put out, or null if it isn't a light we can put out. */
    private static BlockState unlit(BlockState s) {
        if (s.isOf(Blocks.TORCH) || s.isOf(Blocks.WALL_TORCH) || s.isOf(Blocks.SOUL_TORCH) || s.isOf(Blocks.SOUL_WALL_TORCH)) {
            return Blocks.AIR.getDefaultState();
        }
        if (s.isOf(Blocks.JACK_O_LANTERN)) return Blocks.CARVED_PUMPKIN.getDefaultState().with(Properties.HORIZONTAL_FACING, s.get(Properties.HORIZONTAL_FACING));
        if ((s.getBlock() instanceof AbstractCandleBlock || s.getBlock() instanceof CampfireBlock)
                && s.contains(Properties.LIT) && s.get(Properties.LIT)) {
            return s.with(Properties.LIT, false);
        }
        return null;
    }

    /** Something is digging at a wall near them: cracks spread across it with every scrape... then stop. */
    private static boolean digging(Haunt h, ServerPlayerEntity p) {
        ServerWorld world = p.getServerWorld();
        BlockPos feet = p.getBlockPos();
        BlockPos pos = find(h, p, (at, s) -> at.getY() >= feet.getY() && at.getY() <= feet.getY() + 1
                && s.isSolidBlock(world, at) && s.getHardness(world, at) >= 0 && hasAirSide(world, at));
        if (pos == null) return false;
        BlockPos key = pos.toImmutable();
        int breakerId = -1000 - p.getRandom().nextInt(100000);

        int hits = 9;
        for (int i = 0; i < hits; i++) {
            int stage = Math.min(8, i);
            h.schedule(i * 7 + p.getRandom().nextInt(3), player -> {
                BlockState state = world.getBlockState(key);
                if (state.isAir()) return;
                player.networkHandler.sendPacket(new BlockBreakingProgressS2CPacket(breakerId, key, stage));
                sound(player, state.getSoundGroup().getHitSound(), SoundCategory.BLOCKS, key.toCenterPos(), 0.6f, 0.5f);
            });
        }
        h.fakes.put(key, player -> player.networkHandler.sendPacket(new BlockBreakingProgressS2CPacket(breakerId, key, -1)));
        // It stops just before breaking through, and the cracks fade a while later
        h.schedule(hits * 7 + 60, player -> restore(h, player, key));
        return true;
    }

    /** A lever or button nearby clicks on by itself, and back off. */
    private static boolean switches(Haunt h, ServerPlayerEntity p) {
        ServerWorld world = p.getServerWorld();
        BlockPos pos = find(h, p, (at, s) -> (s.getBlock() instanceof LeverBlock || s.getBlock() instanceof ButtonBlock) && !s.get(Properties.POWERED));
        if (pos == null) return false;
        BlockPos key = pos.toImmutable();
        BlockState state = world.getBlockState(key);

        SoundEvent on, off;
        float pitchOn, pitchOff;
        if (state.getBlock() instanceof LeverBlock) {
            on = off = SoundEvents.BLOCK_LEVER_CLICK;
            pitchOn = 0.6f;
            pitchOff = 0.5f;
        } else {
            boolean wooden = state.isIn(BlockTags.WOODEN_BUTTONS);
            on = wooden ? SoundEvents.BLOCK_WOODEN_BUTTON_CLICK_ON : SoundEvents.BLOCK_STONE_BUTTON_CLICK_ON;
            off = wooden ? SoundEvents.BLOCK_WOODEN_BUTTON_CLICK_OFF : SoundEvents.BLOCK_STONE_BUTTON_CLICK_OFF;
            pitchOn = 0.6f;
            pitchOff = 0.5f;
        }
        p.networkHandler.sendPacket(new BlockUpdateS2CPacket(key, state.with(Properties.POWERED, true)));
        sound(p, on, SoundCategory.BLOCKS, key.toCenterPos(), 0.5f, pitchOn);
        h.fakes.put(key, player -> {
            player.networkHandler.sendPacket(new BlockUpdateS2CPacket(world, key));
            sound(player, off, SoundCategory.BLOCKS, key.toCenterPos(), 0.5f, pitchOff);
        });
        h.schedule(20 + p.getRandom().nextInt(40), player -> restore(h, player, key));
        return true;
    }

    // Helpers

    private static void restore(Haunt h, ServerPlayerEntity p, BlockPos pos) {
        Consumer<ServerPlayerEntity> restore = h.fakes.remove(pos);
        if (restore != null) restore.accept(p);
    }

    private interface BlockMatch {
        boolean test(BlockPos pos, BlockState state);
    }

    /** A random matching block near them, preferring ones behind them where they aren't looking. */
    private static BlockPos find(Haunt h, ServerPlayerEntity p, BlockMatch match) {
        ServerWorld world = p.getServerWorld();
        BlockPos center = p.getBlockPos();
        Vec3d look = p.getRotationVector();
        List<BlockPos> behind = new ArrayList<>();
        List<BlockPos> any = new ArrayList<>();
        for (BlockPos pos : BlockPos.iterate(center.add(-SEARCH_RADIUS, -SEARCH_HEIGHT, -SEARCH_RADIUS), center.add(SEARCH_RADIUS, SEARCH_HEIGHT, SEARCH_RADIUS))) {
            if (h.fakes.containsKey(pos) || !match.test(pos, world.getBlockState(pos))) continue;
            Vec3d to = pos.toCenterPos().subtract(p.getEyePos());
            if (to.lengthSquared() < 4.0) continue; // right next to them is too obvious
            BlockPos found = pos.toImmutable();
            any.add(found);
            if (to.normalize().dotProduct(look) < 0.3) behind.add(found);
        }
        List<BlockPos> from = behind.isEmpty() ? any : behind;
        return from.isEmpty() ? null : from.get(p.getRandom().nextInt(from.size()));
    }

    private static boolean hasAirSide(ServerWorld world, BlockPos pos) {
        for (Direction d : Direction.Type.HORIZONTAL) {
            if (world.getBlockState(pos.offset(d)).isAir()) return true;
        }
        return false;
    }

    /** Room for a player to stand with their feet in {@code feet}. */
    private static boolean canStand(ServerWorld world, BlockPos feet) {
        return world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()
                && world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty()
                && !world.getBlockState(feet.down()).getCollisionShape(world, feet.down()).isEmpty()
                && world.getFluidState(feet).isEmpty();
    }

    /** The nearest spot within {@code range} blocks up or down from {@code start} where a player could stand. */
    private static BlockPos ground(ServerWorld world, BlockPos start, int range) {
        for (int i = 0; i <= range; i++) {
            if (canStand(world, start.up(i))) return start.up(i);
            if (i > 0 && canStand(world, start.down(i))) return start.down(i);
        }
        return null;
    }

    private static boolean clearView(ServerWorld world, ServerPlayerEntity p, Vec3d to) {
        return world.raycast(new RaycastContext(p.getEyePos(), to, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, p)).getType() == HitResult.Type.MISS;
    }

    private static void sound(ServerPlayerEntity p, SoundEvent sound, SoundCategory category, Vec3d at, float volume, float pitch) {
        sound(p, Registries.SOUND_EVENT.getEntry(sound), category, at, volume, pitch);
    }

    /** Plays a sound only this one player can hear. */
    private static void sound(ServerPlayerEntity p, RegistryEntry<SoundEvent> sound, SoundCategory category, Vec3d at, float volume, float pitch) {
        p.networkHandler.sendPacket(new PlaySoundS2CPacket(sound, category, at.x, at.y, at.z, volume, pitch, p.getRandom().nextLong()));
    }
}
