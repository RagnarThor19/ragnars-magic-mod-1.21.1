package net.ragnar.ragnarsmagicmod.util;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class SpellEffects {
    private static final List<PendingImpact> PENDING = new ArrayList<>();
    private static boolean registered = false;

    private record PendingImpact(ServerWorld world, double x, double y, double z, int ticks) {}

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;

        ServerTickEvents.END_WORLD_TICK.register(world -> {
            if (PENDING.isEmpty()) return;
            Iterator<PendingImpact> it = PENDING.iterator();
            List<PendingImpact> toReschedule = new ArrayList<>();
            while (it.hasNext()) {
                PendingImpact p = it.next();
                if (p.world() != world) continue; // different world tick will handle it
                int t = p.ticks() - 1;
                if (t <= 0) {
                    // impact particles + sound
                    world.playSound(
                            null,
                            p.x(), p.y(), p.z(),
                            SoundEvents.BLOCK_ANVIL_LAND, // deep impact
                            SoundCategory.PLAYERS,
                            0.9f,
                            0.9f
                    );

                    var effect = new BlockStateParticleEffect(
                            ParticleTypes.BLOCK,
                            Blocks.DAMAGED_ANVIL.getDefaultState()
                    );
                    world.spawnParticles(effect, p.x(), p.y(), p.z(),
                            24,   // count
                            0.5, 0.2, 0.5, // spread
                            0.12  // speed
                    );
                    it.remove();
                } else {
                    toReschedule.add(new PendingImpact(p.world(), p.x(), p.y(), p.z(), t));
                    it.remove();
                }
            }
            // re-add remaining with decremented ticks
            PENDING.addAll(toReschedule);
        });
    }

    /** Schedule an impact FX at (x,y,z) to fire in 'ticks' ticks. */
    public static void scheduleImpact(ServerWorld world, double x, double y, double z, int ticks) {
        ensureRegistered();
        PENDING.add(new PendingImpact(world, x, y, z, Math.max(1, ticks)));
    }

    /**
     * Estimate fall time (in ticks) for vanilla FallingBlockEntity from height 'h' (blocks).
     * Uses a simple physics sim with Minecraft's per-tick gravity/drag to be accurate enough.
     */
    public static int estimateFallTicks(double h) {
        // vanilla-ish: gravity = 0.04 per tick, initial vel ~ -0.05, drag ~0.98 after move
        double y = 0.0;
        double v = -0.05;
        int ticks = 0;
        while (-y < h && ticks < 400) {
            // move
            y += v;
            // gravity
            v -= 0.04;
            // drag
            v *= 0.98;
            ticks++;
        }
        return Math.max(1, ticks);
    }

    private SpellEffects() {}
}
