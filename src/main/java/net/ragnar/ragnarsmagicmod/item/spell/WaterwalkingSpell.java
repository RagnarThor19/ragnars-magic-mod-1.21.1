package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FrostedIceBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.*;

/**
 * Tome of Waterwalking. For 30 seconds, water freezes and lava crusts over into obsidian under your feet,
 * like Frost Walker for both. Each block holds while you're near it, then cracks and turns back into what
 * it was a few seconds after you've moved on.
 */
public class WaterwalkingSpell implements Spell {
    private static final int DURATION = 20 * 30;
    private static final int RADIUS = 3;
    private static final int LINGER_TICKS = 60;  // how long a block lasts after you leave it
    private static final int CRACK_TICKS = 30;   // the last part of that, spent visibly cracking

    private record Walker(ServerWorld world, int ticksLeft) {}

    /** A block the spell made, and what to put back. */
    private static final class Temp {
        final BlockState original;
        final boolean lava;
        int ticksLeft = LINGER_TICKS;
        int crackStage = -1;

        Temp(BlockState original, boolean lava) {
            this.original = original;
            this.lava = lava;
        }
    }

    private static final Map<UUID, Walker> WALKERS = new HashMap<>();
    private static final Map<ServerWorld, Map<BlockPos, Temp>> TEMPS = new HashMap<>();
    private static boolean registered = false;

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(WaterwalkingSpell::tick);
        // Never leave the world full of stray ice and obsidian
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (Map.Entry<ServerWorld, Map<BlockPos, Temp>> e : TEMPS.entrySet()) {
                for (Map.Entry<BlockPos, Temp> t : e.getValue().entrySet()) restore(e.getKey(), t.getKey(), t.getValue(), false);
            }
            TEMPS.clear();
            WALKERS.clear();
        });
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        if (!(world instanceof ServerWorld sw)) return false;
        ensureRegistered();

        WALKERS.put(player.getUuid(), new Walker(sw, DURATION));
        Vec3d p = player.getPos();
        sw.playSound(null, p.x, p.y, p.z, SoundEvents.BLOCK_POWDER_SNOW_PLACE, SoundCategory.PLAYERS, 1.0f, 0.8f);
        sw.playSound(null, p.x, p.y, p.z, SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.PLAYERS, 0.5f, 1.4f);
        sw.spawnParticles(ParticleTypes.SNOWFLAKE, p.x, p.y + 0.2, p.z, 15, 0.5, 0.1, 0.5, 0.02);
        sw.spawnParticles(ParticleTypes.SMOKE, p.x, p.y + 0.2, p.z, 10, 0.5, 0.1, 0.5, 0.02);
        player.sendMessage(Text.literal("The water and lava will hold you."), true);
        return true;
    }

    private static void tick(ServerWorld world) {
        Map<BlockPos, Temp> temps = TEMPS.computeIfAbsent(world, w -> new HashMap<>());

        // Walkers harden what's under them and keep nearby blocks from wearing off
        Iterator<Map.Entry<UUID, Walker>> it = WALKERS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Walker> e = it.next();
            Walker w = e.getValue();
            if (w.world() != world) continue;
            PlayerEntity p = world.getPlayerByUuid(e.getKey());
            if (p == null || !p.isAlive()) { it.remove(); continue; }

            int left = w.ticksLeft() - 1;
            if (left <= 0) {
                it.remove();
                world.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 0.5f, 1.6f);
                p.sendMessage(Text.literal("Your footing fades."), true);
                continue;
            }
            e.setValue(new Walker(world, left));
            if (left == 60) {
                world.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.0f, 0.7f);
                p.sendMessage(Text.literal("Your footing is fading..."), true);
            }
            harden(world, p, temps);
        }

        // Blocks nobody is near any more crack and turn back
        Iterator<Map.Entry<BlockPos, Temp>> blocks = temps.entrySet().iterator();
        while (blocks.hasNext()) {
            Map.Entry<BlockPos, Temp> e = blocks.next();
            BlockPos pos = e.getKey();
            Temp t = e.getValue();
            BlockState now = world.getBlockState(pos);
            if (!now.isOf(t.lava ? Blocks.OBSIDIAN : Blocks.FROSTED_ICE)) { // mined, replaced, melted on its own
                clearCrack(world, pos, t);
                blocks.remove();
                continue;
            }
            if (--t.ticksLeft <= 0) {
                restore(world, pos, t, true);
                blocks.remove();
            } else if (t.ticksLeft <= CRACK_TICKS) {
                crack(world, pos, t, now);
            }
        }
    }

    /** Turns the water and lava under and around the walker into something to stand on. */
    private static void harden(ServerWorld world, PlayerEntity p, Map<BlockPos, Temp> temps) {
        if (p.isSpectator() || p.hasVehicle() || p.isTouchingWater() || p.isInLava()) return;
        BlockPos feet = p.getBlockPos();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        boolean froze = false, crusted = false;

        for (int dy = -1; dy >= -2; dy--) { // also catch the surface when coming down onto it
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    if (dx * dx + dz * dz > RADIUS * RADIUS + 1) continue;
                    pos.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
                    Temp existing = temps.get(pos);
                    if (existing != null) {
                        // Still in use: stay solid
                        if (existing.ticksLeft < LINGER_TICKS) {
                            clearCrack(world, pos, existing);
                            if (!existing.lava) world.setBlockState(pos, Blocks.FROSTED_ICE.getDefaultState(), Block.NOTIFY_LISTENERS);
                        }
                        existing.ticksLeft = LINGER_TICKS;
                        continue;
                    }
                    if (!world.getBlockState(pos.up()).isAir()) continue; // only the surface
                    BlockState state = world.getBlockState(pos);
                    if (state.isOf(Blocks.WATER) && state.getFluidState().isStill()) {
                        world.setBlockState(pos, Blocks.FROSTED_ICE.getDefaultState());
                        temps.put(pos.toImmutable(), new Temp(state, false));
                        froze = true;
                    } else if (state.isOf(Blocks.LAVA)) {
                        world.setBlockState(pos, Blocks.OBSIDIAN.getDefaultState());
                        temps.put(pos.toImmutable(), new Temp(state, true));
                        world.spawnParticles(ParticleTypes.LARGE_SMOKE, pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5, 1, 0.2, 0.05, 0.2, 0.01);
                        crusted = true;
                    }
                }
            }
        }
        if (froze && world.getRandom().nextInt(3) == 0) {
            world.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_POWDER_SNOW_STEP, SoundCategory.PLAYERS, 0.6f, 1.2f);
            world.spawnParticles(ParticleTypes.SNOWFLAKE, p.getX(), p.getY() + 0.1, p.getZ(), 3, 0.8, 0.05, 0.8, 0.01);
        }
        if (crusted && world.getRandom().nextInt(3) == 0) {
            world.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_LAVA_EXTINGUISH, SoundCategory.PLAYERS, 0.3f, 1.6f);
        }
    }

    /** Visible cracks while a block wears off: ice ages, obsidian gets breaking marks. */
    private static void crack(ServerWorld world, BlockPos pos, Temp t, BlockState now) {
        int stage = (CRACK_TICKS - t.ticksLeft) * 10 / CRACK_TICKS; // 0..9
        if (stage == t.crackStage) return;
        t.crackStage = stage;
        if (t.lava) {
            world.setBlockBreakingInfo(breakerId(pos), pos, stage);
        } else {
            int age = Math.min(3, stage * 4 / 10);
            if (now.get(FrostedIceBlock.AGE) != age) world.setBlockState(pos, now.with(FrostedIceBlock.AGE, age), Block.NOTIFY_LISTENERS);
        }
    }

    private static void clearCrack(ServerWorld world, BlockPos pos, Temp t) {
        if (t.crackStage >= 0 && t.lava) world.setBlockBreakingInfo(breakerId(pos), pos, -1);
        t.crackStage = -1;
    }

    private static void restore(ServerWorld world, BlockPos pos, Temp t, boolean effects) {
        clearCrack(world, pos, t);
        if (!world.getBlockState(pos).isOf(t.lava ? Blocks.OBSIDIAN : Blocks.FROSTED_ICE)) return;
        world.setBlockState(pos, t.original);
        if (!effects) return;
        if (t.lava) {
            world.spawnParticles(ParticleTypes.LAVA, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 1, 0.2, 0, 0.2, 0);
        } else {
            world.spawnParticles(ParticleTypes.SPLASH, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 4, 0.3, 0, 0.3, 0);
            if (world.getRandom().nextInt(4) == 0) {
                world.playSound(null, pos, SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.BLOCKS, 0.3f, 1.8f);
            }
        }
    }

    /** Breaking marks are keyed by a "breaker" id; give each block its own so they don't clash. */
    private static int breakerId(BlockPos pos) {
        return -1_000_000 - (int) (pos.asLong() & 0x7FFFFFF);
    }
}
