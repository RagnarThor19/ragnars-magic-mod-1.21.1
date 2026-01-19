package net.ragnar.ragnarsmagicmod.util;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

public final class CloudRunner {
    private CloudRunner() {}

    private static boolean registered = false;

    // State: ticksLeft determines phase.
    private record ActiveFlight(ServerWorld world, UUID playerId, int ticksLeft, boolean isCreative) {}

    private static final List<ActiveFlight> ACTIVE = new ArrayList<>();

    // Launch duration before "sudden stop"
    private static final int LAUNCH_TICKS = 8;

    public static void start(ServerWorld world, PlayerEntity player, int durationTicks) {
        ensureRegistered();
        // Launch user immediately
        player.addVelocity(0, 1.25, 0);
        player.velocityModified = true;

        ACTIVE.add(new ActiveFlight(world, player.getUuid(), durationTicks, player.isCreative()));
    }

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;

        ServerTickEvents.END_WORLD_TICK.register(world -> {
            if (ACTIVE.isEmpty()) return;

            Iterator<ActiveFlight> it = ACTIVE.iterator();
            List<ActiveFlight> requeue = new ArrayList<>();

            while (it.hasNext()) {
                ActiveFlight f = it.next();
                if (f.world != world) continue;

                PlayerEntity p = world.getPlayerByUuid(f.playerId);

                // Cleanup if player is gone or dead
                if (p == null || p.isRemoved() || !p.isAlive()) {
                    it.remove();
                    continue;
                }

                int totalDuration = 400; // 20 seconds
                int elapsed = totalDuration - f.ticksLeft;

                // --- 3 Second Warning Logic ---
                if (f.ticksLeft == 60) { // 3 Seconds remaining
                    world.playSound(null, p.getX(), p.getY(), p.getZ(),
                            SoundEvents.BLOCK_DECORATED_POT_INSERT_FAIL, SoundCategory.PLAYERS, 1.4F, 1.4F);
                } else if (f.ticksLeft == 40) { // 2 Seconds remaining
                    world.playSound(null, p.getX(), p.getY(), p.getZ(),
                            SoundEvents.BLOCK_DECORATED_POT_INSERT_FAIL, SoundCategory.PLAYERS, 1.4F, 1.1F); // Lower
                } else if (f.ticksLeft == 20) { // 1 Second remaining
                    world.playSound(null, p.getX(), p.getY(), p.getZ(),
                            SoundEvents.BLOCK_DECORATED_POT_INSERT_FAIL, SoundCategory.PLAYERS, 1.4F, 0.7F); // Lowest
                }
                // ------------------------------

                // Movement/Flight Logic
                if (elapsed < LAUNCH_TICKS) {
                    // Phase 1: Launching
                    world.spawnParticles(ParticleTypes.CLOUD, p.getX(), p.getY(), p.getZ(), 1, 0.2, 0.1, 0.2, 0.1);
                } else if (elapsed == LAUNCH_TICKS) {
                    // Phase 2: Sudden Stop & Stabilize
                    p.setVelocity(0, 0, 0);
                    p.velocityModified = true;

                    if (!p.isSpectator()) {
                        p.getAbilities().allowFlying = true;
                        p.getAbilities().flying = true;
                        p.sendAbilitiesUpdate();
                    }

                    world.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_POWDER_SNOW_BREAK, SoundCategory.PLAYERS, 1.0f, 0.5f);
                } else {
                    // Phase 3: Flying
                    if (!p.isCreative() && !p.isSpectator()) {
                        p.getAbilities().allowFlying = true;
                        p.sendAbilitiesUpdate();
                    }

                    if (!p.isOnGround()) {
                        world.spawnParticles(ParticleTypes.CLOUD, p.getX(), p.getY() - 0.2, p.getZ(),
                                2, 0.3, 0.05, 0.3, 0.01);
                    }
                }

                int t = f.ticksLeft - 1;
                if (t <= 0) {
                    // Time up: Disable flight
                    if (!f.isCreative && !p.isSpectator()) {
                        p.getAbilities().allowFlying = false;
                        p.getAbilities().flying = false;
                        p.sendAbilitiesUpdate();
                    }
                    // End sound (Extinguish)
                    world.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_LAVA_EXTINGUISH, SoundCategory.PLAYERS, 0.5f, 2.0f);
                    it.remove();
                } else {
                    requeue.add(new ActiveFlight(f.world, f.playerId, t, f.isCreative));
                    it.remove();
                }
            }
            ACTIVE.addAll(requeue);
        });
    }
}