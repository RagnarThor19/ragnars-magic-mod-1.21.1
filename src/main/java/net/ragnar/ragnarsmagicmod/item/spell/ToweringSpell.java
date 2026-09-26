package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tome of Towering: a column of oak planks stacks up under your feet and carries you up to {@link #MAX_HEIGHT}
 * blocks, stopping short of anything overhead. It stands for a few seconds - cracking all over as a warning - then
 * collapses from the top down. Anyone still up there gets Slow Falling for the way down.
 */
public class ToweringSpell implements Spell {
    private static final int MAX_HEIGHT = 20;
    private static final double RISE_SPEED = 0.9;     // blocks per tick
    private static final int STAND_TICKS = 80;        // 4s at the top
    private static final int CRACK_TICKS = 40;        // the last 2s of that, it visibly cracks
    private static final int MAX_RISE_TICKS = 60;     // give up rising if the player isn't coming up with it
    private static final BlockState PLANKS = Blocks.OAK_PLANKS.getDefaultState();
    private static final BlockStateParticleEffect PLANK_DUST = new BlockStateParticleEffect(ParticleTypes.BLOCK, PLANKS);

    private enum Phase { RISING, STANDING, COLLAPSING }

    private static final class Tower {
        final ServerWorld world;
        final UUID owner;
        final int id;
        final BlockPos base;
        final int height;
        final List<BlockPos> placed = new ArrayList<>();
        Phase phase = Phase.RISING;
        int timer;

        Tower(ServerWorld world, UUID owner, int id, BlockPos base, int height) {
            this.world = world;
            this.owner = owner;
            this.id = id;
            this.base = base;
            this.height = height;
        }

        double centerX() { return base.getX() + 0.5; }
        double centerZ() { return base.getZ() + 0.5; }
    }

    private static final Map<UUID, Tower> TOWERS = new HashMap<>();
    private static int nextId = 1;

    public static void register() {
        ServerTickEvents.END_WORLD_TICK.register(ToweringSpell::tick);
        // A tower must never be left standing: take them all down if the server stops
        ServerLifecycleEvents.SERVER_STOPPING.register(ToweringSpell::removeAll);
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw) || !(player instanceof ServerPlayerEntity sp)) return false;
        if (TOWERS.containsKey(player.getUuid())) return false;
        if (!player.canModifyBlocks()) return false;

        BlockPos base = BlockPos.ofFloored(player.getX(), player.getY() + 0.01, player.getZ());
        int height = clearHeight(sw, player, base);
        if (height <= 0) {
            sw.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BLOCK_WOOD_HIT, SoundCategory.PLAYERS, 0.8f, 0.6f);
            return false;
        }

        // Step onto the middle of the column so you ride straight up it
        sp.networkHandler.requestTeleport(base.getX() + 0.5, player.getY(), base.getZ() + 0.5, player.getYaw(), player.getPitch());
        TOWERS.put(player.getUuid(), new Tower(sw, player.getUuid(), nextId++, base, height));

        sw.spawnParticles(ParticleTypes.POOF, base.getX() + 0.5, base.getY() + 0.1, base.getZ() + 0.5, 12, 0.4, 0.05, 0.4, 0.03);
        sw.spawnParticles(PLANK_DUST, base.getX() + 0.5, base.getY() + 0.1, base.getZ() + 0.5, 20, 0.4, 0.05, 0.4, 0.1);
        sw.playSound(null, base, SoundEvents.ENTITY_EVOKER_CAST_SPELL, SoundCategory.PLAYERS, 0.6f, 1.6f);
        return true;
    }

    /**
     * How many planks fit: each one needs its own spot free, plus room for you to stand on top of it. Stops at the
     * first thing in the way.
     */
    private static int clearHeight(ServerWorld world, PlayerEntity player, BlockPos base) {
        int h = 0;
        for (int i = 0; i < MAX_HEIGHT; i++) {
            BlockPos p = base.up(i);
            if (!canPlace(world, player, p) || !isOpen(world, p.up()) || !isOpen(world, p.up(2))) break;
            h++;
        }
        return h;
    }

    private static boolean canPlace(ServerWorld world, PlayerEntity player, BlockPos pos) {
        BlockState s = world.getBlockState(pos);
        return world.canPlayerModifyAt(player, pos) && world.isInBuildLimit(pos)
                && (s.isAir() || (s.isReplaceable() && s.getFluidState().isEmpty()));
    }

    private static boolean isOpen(ServerWorld world, BlockPos pos) {
        return world.isInBuildLimit(pos) && world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }

    // ---------------------------------------------------------------------
    // Ticking
    // ---------------------------------------------------------------------

    private static void tick(ServerWorld world) {
        if (TOWERS.isEmpty()) return;
        Iterator<Tower> it = TOWERS.values().iterator();
        while (it.hasNext()) {
            Tower t = it.next();
            if (t.world != world) continue;
            PlayerEntity player = world.getPlayerByUuid(t.owner);
            t.timer++;
            boolean done = switch (t.phase) {
                case RISING -> { rise(t, player); yield false; }
                case STANDING -> { stand(t); yield false; }
                case COLLAPSING -> collapse(t, player);
            };
            if (done) it.remove();
        }
    }

    /** Push the player up and fill in planks beneath them as they pass. */
    private static void rise(Tower t, PlayerEntity player) {
        ServerWorld world = t.world;
        boolean lost = player == null || !player.isAlive() || player.getWorld() != world
                || Math.abs(player.getX() - t.centerX()) > 0.9 || Math.abs(player.getZ() - t.centerZ()) > 0.9;

        if (!lost) {
            // Every block level the player's feet have fully cleared gets a plank
            int reached = MathHelper.floor(player.getY() + 0.001) - t.base.getY();
            while (t.placed.size() < Math.min(reached, t.height)) {
                BlockPos p = t.base.up(t.placed.size());
                if (!canPlace(world, player, p)) {
                    t.timer = MAX_RISE_TICKS; // something moved in the way: stop here
                    break;
                }
                world.setBlockState(p, PLANKS, Block.NOTIFY_ALL);
                t.placed.add(p);
                float pitch = 0.8f + 0.9f * t.placed.size() / t.height;
                world.playSound(null, p, SoundEvents.BLOCK_WOOD_PLACE, SoundCategory.BLOCKS, 1.0f, pitch);
                world.spawnParticles(PLANK_DUST, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, 6, 0.35, 0.2, 0.35, 0.05);
            }
        }

        boolean topped = t.placed.size() >= t.height;
        if (lost || topped || t.timer >= MAX_RISE_TICKS) {
            if (!lost && player instanceof ServerPlayerEntity) {
                player.setVelocity(0, Math.min(0.0, player.getVelocity().y), 0);
                player.velocityModified = true;
            }
            if (t.placed.isEmpty()) {
                t.phase = Phase.COLLAPSING;
            } else {
                BlockPos top = t.placed.get(t.placed.size() - 1);
                world.playSound(null, top, SoundEvents.BLOCK_BARREL_CLOSE, SoundCategory.BLOCKS, 1.2f, 0.9f);
                world.playSound(null, top, SoundEvents.BLOCK_WOOD_PLACE, SoundCategory.BLOCKS, 1.2f, 0.6f);
                world.spawnParticles(PLANK_DUST, top.getX() + 0.5, top.getY() + 1.0, top.getZ() + 0.5, 25, 0.5, 0.05, 0.5, 0.12);
                t.phase = Phase.STANDING;
            }
            t.timer = 0;
            return;
        }

        // Ride up the middle of the column, slowing a touch for the last few blocks
        int left = t.height - t.placed.size();
        double vy = left > 3 ? RISE_SPEED : 0.45;
        player.setVelocity((t.centerX() - player.getX()) * 0.5, vy, (t.centerZ() - player.getZ()) * 0.5);
        player.velocityModified = true;
        player.fallDistance = 0;
    }

    /** Stands a while, then cracks all over as a warning. */
    private static void stand(Tower t) {
        int crackStart = STAND_TICKS - CRACK_TICKS;
        if (t.timer > crackStart) {
            int stage = MathHelper.clamp((t.timer - crackStart) * 10 / CRACK_TICKS, 0, 9);
            for (int i = 0; i < t.placed.size(); i++) {
                BlockPos p = t.placed.get(i);
                if (t.world.getBlockState(p).isOf(Blocks.OAK_PLANKS)) t.world.setBlockBreakingInfo(crackId(t, i), p, stage);
            }
            if (t.timer % 8 == 0) {
                BlockPos p = t.placed.get(t.world.random.nextInt(t.placed.size()));
                t.world.playSound(null, p, SoundEvents.BLOCK_WOOD_HIT, SoundCategory.BLOCKS, 0.7f, 0.5f + t.world.random.nextFloat() * 0.3f);
            }
        }
        if (t.timer >= STAND_TICKS) {
            t.phase = Phase.COLLAPSING;
            t.timer = 0;
        }
    }

    /** Falls apart from the top down, a block each tick. Returns true once it's gone. */
    private static boolean collapse(Tower t, PlayerEntity player) {
        if (t.timer == 1 && player != null && player.isAlive() && player.getWorld() == t.world
                && Math.abs(player.getX() - t.centerX()) < 2.5 && Math.abs(player.getZ() - t.centerZ()) < 2.5
                && player.getY() > t.base.getY() + 1.5) {
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, 20 * 6, 0, false, false, true));
        }
        if (t.placed.isEmpty()) return true;
        int i = t.placed.size() - 1;
        BlockPos p = t.placed.remove(i);
        t.world.setBlockBreakingInfo(crackId(t, i), p, -1);
        if (t.world.getBlockState(p).isOf(Blocks.OAK_PLANKS)) t.world.breakBlock(p, false);
        return t.placed.isEmpty();
    }

    /** Break-progress ids must be unique per block; these sit far below any real entity id. */
    private static int crackId(Tower t, int index) {
        return -1_000_000 - t.id * 32 - index;
    }

    private static void removeAll(MinecraftServer server) {
        for (Tower t : TOWERS.values()) {
            for (BlockPos p : t.placed) {
                if (t.world.getBlockState(p).isOf(Blocks.OAK_PLANKS)) t.world.setBlockState(p, Blocks.AIR.getDefaultState());
            }
        }
        TOWERS.clear();
    }
}
