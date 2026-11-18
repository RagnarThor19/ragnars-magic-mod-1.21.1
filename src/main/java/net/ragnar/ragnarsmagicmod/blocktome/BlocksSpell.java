package net.ragnar.ragnarsmagicmod.blocktome;

import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.ChanneledSpell;
import net.ragnar.ragnarsmagicmod.util.DeflectableShots;
import net.ragnar.ragnarsmagicmod.util.TempEntities;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tome of Blocks, on the server (see BlockTome): the ring of copied blocks, the presses and held barrages that fire
 * them, and the blocks in flight.
 */
public class BlocksSpell implements ChanneledSpell {
    /** How many blocks the local player has orbiting, as the server last said (client side only). */
    public static volatile int clientCount = 0;

    // The ring
    private static final int SCAN_RADIUS = 6;
    private static final double RING_RADIUS = 1.3;
    private static final double RING_HEIGHT = 0.85;   // above the eyes, so it stays at the top of your view
    private static final double RING_SPIN = 0.045;    // radians a tick
    private static final float SCALE = 0.34f;
    private static final int RISE_TICKS = 16;
    private static final int RISE_STAGGER = 2;        // ticks between one block lifting out and the next

    // Firing
    private static final int HOLD_DELAY = 6;          // held this long, it turns into a barrage
    private static final int BARRAGE_GAP = 2;         // ticks between barrage shots
    private static final double SPEED = 0.95;         // a third of an arrow's
    private static final double BARRAGE_SPEED = 1.5;
    private static final double RANGE = 48;
    private static final double AIM_CONE_DEGREES = 7;
    private static final double TURN_DEGREES = 2.5;   // light homing a tick
    private static final int FLIGHT_TICKS = 60;

    private static final class Orbiter {
        final BlockState state;
        final BlockTrait trait;
        final Vec3d source;
        final int delay;
        final float phase;
        final Vector3f axis;
        float spin;
        double offset;   // angle in the ring, easing toward its even share as others leave
        @Nullable DisplayEntity.ItemDisplayEntity display;

        Orbiter(BlockState state, Vec3d source, int delay, float phase, Vector3f axis, double offset) {
            this.state = state;
            this.trait = BlockTrait.of(state);
            this.source = source;
            this.delay = delay;
            this.phase = phase;
            this.axis = axis;
            this.offset = offset;
        }

        boolean ready(int ringAge) {
            return display != null && ringAge >= delay + RISE_TICKS;
        }
    }

    private static final class Ring {
        final ServerWorld world;
        final UUID owner;
        final List<Orbiter> blocks = new ArrayList<>();
        int age;
        int hold = -1;           // ticks the button's been held this press, -1 when it's up
        boolean summonPress;     // this press summoned the ring: holding it doesn't fire
        boolean pendingTap;      // pressed before any block was ready: fire as soon as one is
        int volley;              // shots this press, for the rising pitch

        Ring(ServerWorld world, UUID owner) {
            this.world = world;
            this.owner = owner;
        }
    }

    private static final class Flying implements DeflectableShots.Shot {
        final ServerWorld world;
        final DisplayEntity.ItemDisplayEntity display;
        final BlockState state;
        final BlockTrait trait;
        final Vector3f axis;
        final Set<UUID> hit = new HashSet<>();
        UUID owner;
        @Nullable LivingEntity target;
        Vec3d pos;
        Vec3d vel;
        float spin;
        int age;
        int pierce;
        int bounces;

        Flying(ServerWorld world, Orbiter o, UUID owner, @Nullable LivingEntity target, Vec3d pos, Vec3d vel) {
            this.world = world;
            this.display = o.display;
            this.state = o.state;
            this.trait = o.trait;
            this.axis = o.axis;
            this.spin = o.spin;
            this.owner = owner;
            this.target = target;
            this.pos = pos;
            this.vel = vel;
            this.pierce = trait.pierce();
            this.bounces = trait.bounces();
        }

        @Override public ServerWorld world() { return world; }
        @Override public Vec3d pos() { return pos; }
        @Override public Vec3d deflectVelocity() { return vel; }
        @Override public UUID deflectOwner() { return owner; }
        @Override public double radius() { return 0.35; }

        @Override
        public void deflect(PlayerEntity deflector, Vec3d at, Vec3d dir, double speed, @Nullable LivingEntity aimedAt) {
            owner = deflector.getUuid();
            target = aimedAt;
            vel = dir.multiply(vel.length());
            pos = at;
            age = 0;
            hit.clear();
        }
    }

    private static final Map<UUID, Ring> RINGS = new HashMap<>();
    private static final List<Flying> FLYING = new ArrayList<>();

    static {
        DeflectableShots.register(() -> FLYING);
    }

    @Nullable
    private static Ring ring(PlayerEntity player) {
        Ring r = RINGS.get(player.getUuid());
        return r != null && r.world == player.getWorld() && !r.blocks.isEmpty() ? r : null;
    }

    /** Blocks orbiting {@code player} right now (server side). */
    public static int count(PlayerEntity player) {
        Ring r = ring(player);
        return r == null ? 0 : r.blocks.size();
    }

    // ---------------------------------------------------------------------
    // The staff
    // ---------------------------------------------------------------------

    /** Summoning costs the tome's XP; shots are free. */
    @Override
    public int xpCost(PlayerEntity player, int tomeCost) {
        int n = player.getWorld().isClient ? clientCount : count(player);
        return n > 0 ? 0 : tomeCost;
    }

    /** The cooldown starts when the ring is empty (see {@link #finish}), never on a press. */
    @Override
    public int cooldownAfterCast(PlayerEntity player, int tomeCooldown) {
        return 0;
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw)) return true;
        Ring ring = ring(player);
        if (ring == null) {
            ring = summon(sw, player);
            if (ring == null) {
                player.sendMessage(Text.literal("No blocks nearby to copy."), true);
                return false;
            }
            ring.summonPress = true;
            ring.hold = 0;
            return true;
        }
        ring.summonPress = false;
        ring.hold = 0;
        ring.volley = 0;
        if (!fire(ring, player, staff, false)) ring.pendingTap = true;
        return true;
    }

    @Override
    public boolean channelTick(ServerWorld world, PlayerEntity player, ItemStack staff, TomeItem tome) {
        Ring ring = ring(player);
        if (ring == null || ring.summonPress || ring.hold < 0) return false;
        ring.hold++;
        if (ring.pendingTap && fire(ring, player, staff, false)) ring.pendingTap = false;
        if (ring.hold >= HOLD_DELAY && (ring.hold - HOLD_DELAY) % BARRAGE_GAP == 0) fire(ring, player, staff, true);
        return RINGS.containsKey(player.getUuid());
    }

    @Override
    public void channelStop(ServerWorld world, PlayerEntity player) {
        Ring ring = RINGS.get(player.getUuid());
        if (ring != null) {
            ring.hold = -1;
            ring.summonPress = false;
        }
    }

    // ---------------------------------------------------------------------
    // Summoning
    // ---------------------------------------------------------------------

    private record Found(BlockState state, BlockPos pos, double dist) {}

    @Nullable
    private static Ring summon(ServerWorld world, PlayerEntity player) {
        List<Found> picks = scan(world, player);
        if (picks.isEmpty()) return null;

        Ring old = RINGS.remove(player.getUuid());
        if (old != null) shatterRing(old);

        Ring ring = new Ring(world, player.getUuid());
        List<Found> order = new ArrayList<>();
        for (int i = 0; i < BlockTome.COUNT; i++) order.add(picks.get(i % picks.size()));
        Collections.shuffle(order, new java.util.Random(world.random.nextLong()));
        for (int i = 0; i < order.size(); i++) {
            Found f = order.get(i);
            Vector3f axis = new Vector3f(world.random.nextFloat() - 0.5f, world.random.nextFloat() - 0.5f, world.random.nextFloat() - 0.5f);
            if (axis.lengthSquared() < 1e-3) axis.set(0, 1, 0);
            axis.normalize();
            ring.blocks.add(new Orbiter(f.state, Vec3d.ofCenter(f.pos), i * RISE_STAGGER, world.random.nextFloat() * 6.28f, axis,
                    i * Math.PI * 2 / BlockTome.COUNT));
        }
        RINGS.put(player.getUuid(), ring);
        BlockTome.sendCount(player, ring.blocks.size());

        Vec3d c = player.getEyePos();
        world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_ENCHANTMENT_TABLE_USE, SoundCategory.PLAYERS, 1.3f, 0.7f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_ILLUSIONER_CAST_SPELL, SoundCategory.PLAYERS, 1.0f, 1.1f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.PLAYERS, 0.6f, 1.5f);
        world.spawnParticles(ParticleTypes.ENCHANT, c.x, c.y + 0.6, c.z, 80, 1.2, 0.6, 1.2, 1.0);
        return ring;
    }

    /** One of each kind of block in reach (the nearest of each), the ones with a trait first, then by distance. */
    private static List<Found> scan(ServerWorld world, PlayerEntity player) {
        Map<Block, Found> nearest = new HashMap<>();
        BlockPos c = player.getBlockPos();
        Vec3d feet = player.getPos();
        for (BlockPos pos : BlockPos.iterate(c.add(-SCAN_RADIUS, -4, -SCAN_RADIUS), c.add(SCAN_RADIUS, 3, SCAN_RADIUS))) {
            double d = Vec3d.ofCenter(pos).squaredDistanceTo(feet);
            if (d > SCAN_RADIUS * SCAN_RADIUS) continue;
            BlockState state = world.getBlockState(pos);
            if (!copyable(world, pos, state) || !exposed(world, pos)) continue;
            Found prev = nearest.get(state.getBlock());
            if (prev == null || d < prev.dist) nearest.put(state.getBlock(), new Found(state.getBlock().getDefaultState(), pos.toImmutable(), d));
        }
        List<Found> out = new ArrayList<>(nearest.values());
        out.sort((a, b) -> {
            boolean sa = BlockTrait.special(a.state), sb = BlockTrait.special(b.state);
            if (sa != sb) return sa ? -1 : 1;
            return Double.compare(a.dist, b.dist);
        });
        return out.size() > BlockTome.COUNT ? new ArrayList<>(out.subList(0, BlockTome.COUNT)) : out;
    }

    /** A solid block with an item to draw it with: no fluids, chests, signs, fences or the like. */
    static boolean copyable(ServerWorld world, BlockPos pos, BlockState state) {
        if (state.isAir() || !state.getFluidState().isEmpty() || state.hasBlockEntity()) return false;
        if (state.getRenderType() != BlockRenderType.MODEL || state.getBlock().asItem() == Items.AIR) return false;
        return state.isOf(Blocks.CACTUS) || Block.isShapeFullCube(state.getOutlineShape(world, pos));
    }

    /** Can you see some side of it. */
    private static boolean exposed(ServerWorld world, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockPos n = pos.offset(d);
            if (!world.getBlockState(n).isOpaqueFullCube(world, n)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------------
    // The ring, every tick
    // ---------------------------------------------------------------------

    public static void tick(ServerWorld world) {
        Iterator<Ring> rings = RINGS.values().iterator();
        while (rings.hasNext()) {
            Ring r = rings.next();
            if (r.world != world) continue;
            PlayerEntity player = world.getServer().getPlayerManager().getPlayer(r.owner);
            if (player == null || !player.isAlive() || player.getWorld() != world) {
                shatterRing(r);
                rings.remove();
                if (player != null) BlockTome.sendCount(player, 0);
                continue;
            }
            if (++r.age > BlockTome.LIFETIME) {
                // Unused, they crumble away, and the tome goes on cooldown as if they'd all been thrown
                shatterRing(r);
                rings.remove();
                BlockTome.sendCount(player, 0);
                ItemStack staff = StaffItem.findStaffWith(player, BlockTome.TOME_OF_BLOCKS);
                if (!staff.isEmpty()) StaffItem.applyCooldown(world, player, staff, BlockTome.TOME_OF_BLOCKS, BlockTome.COOLDOWN);
                continue;
            }
            tickRing(r, player);
        }

        Iterator<Flying> flying = FLYING.iterator();
        while (flying.hasNext()) {
            Flying f = flying.next();
            if (f.world == world && !tickFlying(f)) {
                TempEntities.discard(f.display);
                flying.remove();
            }
        }
    }

    private static void tickRing(Ring r, PlayerEntity player) {
        ServerWorld world = r.world;
        int n = r.blocks.size();
        for (int i = 0; i < n; i++) {
            Orbiter o = r.blocks.get(i);
            // Slide round to an even share of the ring as blocks leave it
            double want = i * Math.PI * 2 / n;
            o.offset += MathHelper.wrapDegrees(Math.toDegrees(want - o.offset)) * Math.PI / 180 * 0.12;
            o.spin += 0.05f;

            if (r.age < o.delay) continue;
            Vec3d slot = slot(player, r.age, o);
            float scale = SCALE;
            Vec3d p = slot;
            if (o.display == null) {
                o.display = spawnDisplay(world, o);
                lifted(world, o, i);
            }
            int t = r.age - o.delay;
            if (t < RISE_TICKS) {
                // Out of the ground full-size, up in an arc, shrinking into its place in the ring
                double k = (double) t / RISE_TICKS;
                double ease = 1 - Math.pow(1 - k, 3);
                p = o.source.lerp(slot, ease).add(0, Math.sin(k * Math.PI) * 1.2, 0);
                scale = (float) MathHelper.lerp(ease, 1.0, SCALE);
                if (t % 2 == 0) {
                    world.spawnParticles(ParticleTypes.ENCHANT, p.x, p.y, p.z, 2, 0.1, 0.1, 0.1, 0.3);
                    world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.FALLING_DUST, o.state), p.x, p.y, p.z, 1, 0.15, 0.15, 0.15, 0);
                }
                if (t == RISE_TICKS - 1) {
                    world.spawnParticles(ParticleTypes.ENCHANTED_HIT, slot.x, slot.y, slot.z, 4, 0.1, 0.1, 0.1, 0.1);
                    world.playSound(null, slot.x, slot.y, slot.z, SoundEvents.BLOCK_AMETHYST_BLOCK_STEP, SoundCategory.PLAYERS, 0.6f, 1.2f + i * 0.08f);
                }
            } else {
                ambient(world, o, p);
            }
            move(o.display, p, o.axis, o.spin, scale);
        }
    }

    /** Where {@code o} sits in the ring: a slightly tilted circle above the head, turning, each block bobbing. */
    private static Vec3d slot(PlayerEntity player, int age, Orbiter o) {
        double a = age * RING_SPIN + o.offset;
        double bob = Math.sin(age * 0.11 + o.phase) * 0.12;
        double tilt = Math.sin(a + age * 0.02) * 0.15;
        return player.getPos().add(Math.cos(a) * RING_RADIUS, player.getStandingEyeHeight() + RING_HEIGHT + bob + tilt, Math.sin(a) * RING_RADIUS);
    }

    private static DisplayEntity.ItemDisplayEntity spawnDisplay(ServerWorld world, Orbiter o) {
        DisplayEntity.ItemDisplayEntity d = EntityType.ITEM_DISPLAY.create(world);
        ItemStack stack = new ItemStack(o.state.getBlock().asItem());
        stack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        d.setItemStack(stack);
        d.setTransformationMode(ModelTransformationMode.NONE);
        d.setTeleportDuration(1);
        d.setViewRange(2.0f);
        d.setBrightness(new net.minecraft.entity.decoration.Brightness(15, 15));
        d.refreshPositionAndAngles(o.source.x, o.source.y, o.source.z, 0f, 0f);
        d.setTransformation(transform(o.axis, o.spin, 1.0f));
        TempEntities.track(d);
        world.spawnEntity(d);
        return d;
    }

    /** A block copying itself out of the ground. */
    private static void lifted(ServerWorld world, Orbiter o, int index) {
        Vec3d s = o.source;
        world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, o.state), s.x, s.y + 0.5, s.z, 18, 0.3, 0.1, 0.3, 0.1);
        // Letters streaming in to the block, as they do into an enchanting table
        world.spawnParticles(ParticleTypes.ENCHANT, s.x, s.y + 0.6, s.z, 20, 0.5, 0.5, 0.5, 1.0);
        world.playSound(null, s.x, s.y, s.z, o.state.getSoundGroup().getBreakSound(), SoundCategory.PLAYERS, 0.8f, 1.1f);
        world.playSound(null, s.x, s.y, s.z, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.9f, 0.7f + index * 0.12f);
    }

    /** What a block gives off as it circles: the odd stream of enchanting letters, and a hint of its trait. */
    private static void ambient(ServerWorld world, Orbiter o, Vec3d p) {
        var rand = world.random;
        if (rand.nextInt(45) == 0) {
            // The glyph particle flies from (pos + velocity) to pos and sinks 1.2 at the end: start it at the block
            for (int k = 0; k < 3; k++) {
                double dx = (rand.nextDouble() - 0.5) * 1.4, dy = 1.3 + rand.nextDouble() * 0.6, dz = (rand.nextDouble() - 0.5) * 1.4;
                world.spawnParticles(ParticleTypes.ENCHANT, p.x + dx, p.y + dy, p.z + dz, 0, -dx, -dy, -dz, 1.0);
            }
        }
        if (o.trait.ambient != null && rand.nextInt(16) == 0) {
            world.spawnParticles(o.trait.ambient, p.x, p.y, p.z, 1, 0.12, 0.12, 0.12, 0.0);
        }
        if (rand.nextInt(70) == 0) {
            world.spawnParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 1, 0.15, 0.15, 0.15, 0.0);
        }
    }

    private static void move(DisplayEntity.ItemDisplayEntity d, Vec3d p, Vector3f axis, float spin, float scale) {
        d.setPosition(p.x, p.y, p.z);
        d.setTransformation(transform(axis, spin, scale));
        d.setStartInterpolation(0);
        d.setInterpolationDuration(1);
    }

    private static AffineTransformation transform(Vector3f axis, float spin, float scale) {
        return new AffineTransformation(new Vector3f(), new Quaternionf().rotationAxis(spin, axis),
                new Vector3f(scale, scale, scale), new Quaternionf());
    }

    private static void shatterRing(Ring r) {
        for (Orbiter o : r.blocks) {
            if (o.display == null) continue;
            Vec3d p = o.display.getPos();
            r.world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, o.state), p.x, p.y, p.z, 10, 0.15, 0.15, 0.15, 0.05);
            r.world.spawnParticles(ParticleTypes.ENCHANT, p.x, p.y, p.z, 8, 0.2, 0.2, 0.2, 0.5);
            r.world.playSound(null, p.x, p.y, p.z, o.state.getSoundGroup().getBreakSound(), SoundCategory.PLAYERS, 0.5f, 1.3f);
            TempEntities.discard(o.display);
        }
        r.blocks.clear();
    }

    // ---------------------------------------------------------------------
    // Firing
    // ---------------------------------------------------------------------

    /** Sends one block off; false if none was ready (still rising out of the ground). */
    private static boolean fire(Ring ring, PlayerEntity player, ItemStack staff, boolean barrage) {
        ServerWorld world = ring.world;
        LivingEntity target = findTarget(world, player);
        Vec3d aim = target != null ? target.getBoundingBox().getCenter() : aimPoint(world, player);

        // The ready block nearest the line of sight goes first
        Orbiter pick = null;
        double best = Double.MAX_VALUE;
        for (Orbiter o : ring.blocks) {
            if (!o.ready(ring.age)) continue;
            double d = o.display.getPos().squaredDistanceTo(aim);
            if (d < best) {
                best = d;
                pick = o;
            }
        }
        if (pick == null) return false;
        ring.blocks.remove(pick);

        Vec3d pos = pick.display.getPos();
        Vec3d dir = aim.subtract(pos).normalize();
        if (barrage) {
            dir = dir.add(world.random.nextGaussian() * 0.025, world.random.nextGaussian() * 0.025, world.random.nextGaussian() * 0.025).normalize();
        }
        FLYING.add(new Flying(world, pick, player.getUuid(), target, pos, dir.multiply(barrage ? BARRAGE_SPEED : SPEED)));

        float pitch = barrage ? 0.9f + Math.min(ring.volley, 10) * 0.07f : 0.9f;
        ring.volley++;
        world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ENTITY_BREEZE_SHOOT, SoundCategory.PLAYERS, barrage ? 0.7f : 0.9f, pitch + 0.2f);
        world.playSound(null, pos.x, pos.y, pos.z, pick.state.getSoundGroup().getPlaceSound(), SoundCategory.PLAYERS, 0.8f, pitch);
        if (!barrage) world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ENTITY_ILLUSIONER_MIRROR_MOVE, SoundCategory.PLAYERS, 0.5f, 1.6f);
        world.spawnParticles(ParticleTypes.ENCHANT, pos.x, pos.y, pos.z, 6, 0.1, 0.1, 0.1, 0.6);
        
        BlockTome.sendCount(player, ring.blocks.size());
        if (ring.blocks.isEmpty()) finish(ring, player, staff);
        return true;
    }

    /** The last block is gone: the ring's done and the tome cools down. */
    private static void finish(Ring ring, PlayerEntity player, ItemStack staff) {
        RINGS.remove(player.getUuid());
        BlockTome.sendCount(player, 0);
        StaffItem.applyCooldown(ring.world, player, staff, BlockTome.TOME_OF_BLOCKS, BlockTome.COOLDOWN);
    }

    /** The creature nearest the crosshair, in a narrow cone, that you can see. */
    @Nullable
    private static LivingEntity findTarget(ServerWorld world, PlayerEntity player) {
        Vec3d eye = player.getEyePos();
        Vec3d look = player.getRotationVector().normalize();
        double minCos = Math.cos(Math.toRadians(AIM_CONE_DEGREES));
        Box area = new Box(eye, eye.add(look.multiply(RANGE))).expand(RANGE * Math.tan(Math.toRadians(AIM_CONE_DEGREES)) + 2);
        LivingEntity best = null;
        double bestCos = minCos;
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, area, e -> BlockTome.canHit(e, player))) {
            Vec3d to = e.getBoundingBox().getCenter().subtract(eye);
            double dist = to.length();
            if (dist > RANGE || dist < 1e-3) continue;
            double cos = to.multiply(1 / dist).dotProduct(look);
            if (cos < bestCos) continue;
            HitResult wall = world.raycast(new RaycastContext(eye, e.getBoundingBox().getCenter(), RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
            if (wall.getType() != HitResult.Type.MISS) continue;
            bestCos = cos;
            best = e;
        }
        return best;
    }

    /** What the crosshair's on, out to {@link #RANGE}. */
    private static Vec3d aimPoint(ServerWorld world, PlayerEntity player) {
        Vec3d eye = player.getEyePos();
        Vec3d far = eye.add(player.getRotationVector().multiply(RANGE));
        BlockHitResult hit = world.raycast(new RaycastContext(eye, far, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
        return hit.getType() == HitResult.Type.MISS ? far : hit.getPos();
    }

    // ---------------------------------------------------------------------
    // Flight
    // ---------------------------------------------------------------------

    /** False once the block's done. */
    private static boolean tickFlying(Flying f) {
        ServerWorld world = f.world;
        if (++f.age > FLIGHT_TICKS) {
            crumble(world, f.state, f.pos, 0.6f);
            return false;
        }
        double speed = f.vel.length();
        if (f.target != null && f.target.isAlive() && !f.hit.contains(f.target.getUuid())) {
            Vec3d want = f.target.getBoundingBox().getCenter().subtract(f.pos).normalize();
            f.vel = turnToward(f.vel.normalize(), want, Math.toRadians(TURN_DEGREES)).multiply(speed);
        }

        Vec3d from = f.pos;
        Vec3d to = from.add(f.vel);
        PlayerEntity owner = world.getPlayerByUuid(f.owner);

        LivingEntity victim = null;
        Vec3d victimAt = null;
        double victimDist = Double.MAX_VALUE;
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, new Box(from, to).expand(0.8),
                e -> !f.hit.contains(e.getUuid()) && !e.getUuid().equals(f.owner) && BlockTome.canHit(e, owner))) {
            var clip = e.getBoundingBox().expand(0.25).raycast(from, to);
            if (clip.isEmpty()) continue;
            double d = from.squaredDistanceTo(clip.get());
            if (d < victimDist) {
                victimDist = d;
                victim = e;
                victimAt = clip.get();
            }
        }

        BlockHitResult wall = world.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, net.minecraft.block.ShapeContext.absent()));
        if (wall.getType() == HitResult.Type.BLOCK && (victim == null || from.squaredDistanceTo(wall.getPos()) < victimDist)) {
            Vec3d at = wall.getPos().subtract(f.vel.normalize().multiply(0.2));
            crumble(world, f.state, at, 1.0f);
            f.trait.onImpact(world, owner, at);
            return false;
        }

        if (victim != null) {
            strike(world, owner, f, victim, victimAt);
            f.hit.add(victim.getUuid());
            if (f.pierce > 0) {
                f.pierce--;
            } else if (f.bounces > 0) {
                LivingEntity next = nextBounce(world, owner, f, victimAt);
                if (next == null) return false;
                f.bounces--;
                f.target = next;
                to = victimAt;
                f.vel = next.getBoundingBox().getCenter().subtract(victimAt).normalize().multiply(speed);
            } else {
                return false;
            }
        }

        f.pos = to;
        f.spin += 0.45f;
        move(f.display, f.pos, f.axis, f.spin, SCALE);

        // Trail: dust of the block, a little of its trait, the odd letter
        world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.FALLING_DUST, f.state), from.x, from.y, from.z, 1, 0.08, 0.08, 0.08, 0);
        if (f.trait.ambient != null) world.spawnParticles(f.trait.ambient, from.x, from.y, from.z, 1, 0.05, 0.05, 0.05, 0.0);
        if (f.age % 3 == 0) world.spawnParticles(ParticleTypes.ENCHANT, from.x, from.y, from.z, 1, 0.1, 0.1, 0.1, 0.4);
        if (f.age % 4 == 0) world.spawnParticles(ParticleTypes.END_ROD, from.x, from.y, from.z, 1, 0.03, 0.03, 0.03, 0.0);
        return true;
    }

    private static void strike(ServerWorld world, @Nullable PlayerEntity owner, Flying f, LivingEntity victim, Vec3d at) {
        Vec3d dir = f.vel.normalize();
        victim.timeUntilRegen = 0; // a barrage lands a block every other tick; none of them should bounce off
        boolean hurt = victim.damage(BlockTome.damageSource(world, owner), BlockTome.DAMAGE * f.trait.damage);
        if (hurt) victim.takeKnockback(0.45 * f.trait.knockback, -dir.x, -dir.z);
        f.trait.onHit(world, owner, victim, at, dir);

        world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, f.state), at.x, at.y, at.z, 30, 0.25, 0.25, 0.25, 0.15);
        world.spawnParticles(ParticleTypes.ENCHANTED_HIT, at.x, at.y, at.z, 12, 0.2, 0.2, 0.2, 0.3);
        world.spawnParticles(ParticleTypes.CRIT, at.x, at.y, at.z, 8, 0.2, 0.2, 0.2, 0.3);
        world.playSound(null, at.x, at.y, at.z, f.state.getSoundGroup().getBreakSound(), SoundCategory.PLAYERS, 1.3f, 0.8f);
        world.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.PLAYERS, 0.9f, 0.9f + world.random.nextFloat() * 0.2f);
        // The caster gets a little ding for every hit, like an arrow landing
        if (owner != null) owner.playSoundToPlayer(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.3f, 1.6f + world.random.nextFloat() * 0.3f);
    }

    /** Slime: the nearest other creature it can reach, within 10 blocks. */
    @Nullable
    private static LivingEntity nextBounce(ServerWorld world, @Nullable PlayerEntity owner, Flying f, Vec3d from) {
        LivingEntity best = null;
        double bestDist = 100;
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, new Box(from, from).expand(10),
                e -> !f.hit.contains(e.getUuid()) && !e.getUuid().equals(f.owner) && BlockTome.canHit(e, owner))) {
            double d = e.getBoundingBox().getCenter().squaredDistanceTo(from);
            if (d >= bestDist) continue;
            HitResult wall = world.raycast(new RaycastContext(from, e.getBoundingBox().getCenter(), RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, net.minecraft.block.ShapeContext.absent()));
            if (wall.getType() != HitResult.Type.MISS) continue;
            bestDist = d;
            best = e;
        }
        return best;
    }

    private static void crumble(ServerWorld world, BlockState state, Vec3d at, float volume) {
        world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, state), at.x, at.y, at.z, 25, 0.2, 0.2, 0.2, 0.12);
        world.spawnParticles(ParticleTypes.ENCHANT, at.x, at.y, at.z, 8, 0.2, 0.2, 0.2, 0.5);
        world.playSound(null, at.x, at.y, at.z, state.getSoundGroup().getBreakSound(), SoundCategory.PLAYERS, volume, 0.9f);
    }

    /** Rotates unit vector {@code from} toward {@code to} by at most {@code maxAngle} radians. */
    private static Vec3d turnToward(Vec3d from, Vec3d to, double maxAngle) {
        double angle = Math.acos(MathHelper.clamp(from.dotProduct(to), -1.0, 1.0));
        if (angle <= maxAngle || angle < 1e-6) return to;
        double t = maxAngle / angle, sin = Math.sin(angle);
        return from.multiply(Math.sin((1 - t) * angle) / sin).add(to.multiply(Math.sin(t * angle) / sin)).normalize();
    }

    // ---------------------------------------------------------------------
    // Leaving
    // ---------------------------------------------------------------------

    /** Drops {@code player}'s ring without a cooldown (they logged out, died...). */
    public static void dismiss(PlayerEntity player, boolean tell) {
        Ring r = RINGS.remove(player.getUuid());
        if (r != null) shatterRing(r);
        if (tell) BlockTome.sendCount(player, 0);
    }
}
