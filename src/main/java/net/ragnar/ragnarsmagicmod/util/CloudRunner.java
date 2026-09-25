package net.ragnar.ragnarsmagicmod.util;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.ragnar.ragnarsmagicmod.network.CloudPayload;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Tome of Clouds flight. The cloud under the player is drawn by the client (CloudClient) at the
 * player's exact render position, so it never lags behind; the server just says who has one.
 */
public final class CloudRunner {
    private CloudRunner() {}

    private static boolean registered = false;

    private static final class ActiveFlight {
        final ServerWorld world;
        final UUID playerId;
        final int entityId;
        final boolean isCreative;
        int ticksLeft;
        boolean shown = true; // starts on the cloud for the launch

        ActiveFlight(ServerWorld world, PlayerEntity player, int ticksLeft) {
            this.world = world;
            this.playerId = player.getUuid();
            this.entityId = player.getId();
            this.isCreative = player.isCreative();
            this.ticksLeft = ticksLeft;
        }
    }

    private static final List<ActiveFlight> ACTIVE = new ArrayList<>();

    // Launch duration before "sudden stop"
    private static final int LAUNCH_TICKS = 8;
    // How often clients are reminded (also covers players who walk into range mid-flight)
    private static final int SYNC_EVERY = 10;

    public static void start(ServerWorld world, PlayerEntity player, int durationTicks) {
        ensureRegistered();
        // Recasting refreshes the flight instead of stacking a second one
        ACTIVE.removeIf(f -> f.playerId.equals(player.getUuid()));

        // Launch user immediately
        player.addVelocity(0, 1.25, 0);
        player.velocityModified = true;

        ACTIVE.add(new ActiveFlight(world, player, durationTicks));
        CloudPayload.broadcast(world, player.getId(), durationTicks, true);
        world.spawnParticles(ParticleTypes.CLOUD, player.getX(), player.getY(), player.getZ(), 30, 0.9, 0.2, 0.9, 0.03);
    }

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;

        ServerTickEvents.END_WORLD_TICK.register(world -> {
            if (ACTIVE.isEmpty()) return;

            Iterator<ActiveFlight> it = ACTIVE.iterator();
            while (it.hasNext()) {
                ActiveFlight f = it.next();
                if (f.world != world) continue;

                PlayerEntity p = world.getPlayerByUuid(f.playerId);

                // Cleanup if player is gone or dead
                if (p == null || p.isRemoved() || !p.isAlive()) {
                    CloudPayload.broadcast(world, f.entityId, 0, false);
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
                if (elapsed == LAUNCH_TICKS) {
                    // Sudden Stop & Stabilize
                    p.setVelocity(0, 0, 0);
                    p.velocityModified = true;

                    if (!p.isSpectator()) {
                        p.getAbilities().allowFlying = true;
                        p.getAbilities().flying = true;
                        p.sendAbilitiesUpdate();
                    }

                    world.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_POWDER_SNOW_BREAK, SoundCategory.PLAYERS, 1.0f, 0.5f);
                } else if (elapsed > LAUNCH_TICKS) {
                    // Flying
                    if (!p.isCreative() && !p.isSpectator()) {
                        p.getAbilities().allowFlying = true;
                        p.sendAbilitiesUpdate();
                    }
                }

                // The cloud is only there while you're actually flying (or being launched), not
                // when you jump or run around with flight toggled off
                boolean shown = !p.isOnGround() && (elapsed <= LAUNCH_TICKS || p.getAbilities().flying);
                boolean changed = shown != f.shown;
                if (shown && changed) {
                    world.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_WOOL_PLACE, SoundCategory.PLAYERS, 0.8f, 0.6f);
                }
                f.shown = shown;

                // Last 3 seconds: the cloud thins out and drips wisps
                if (shown && f.ticksLeft <= 60 && f.ticksLeft % 3 == 0) {
                    world.spawnParticles(ParticleTypes.CLOUD, p.getX(), p.getY() - 0.6, p.getZ(), 2, 0.8, 0.05, 0.8, 0.0);
                }

                f.ticksLeft--;
                if (changed || f.ticksLeft % SYNC_EVERY == 0) CloudPayload.broadcast(world, f.entityId, Math.max(f.ticksLeft, 0), shown);

                if (f.ticksLeft <= 0) {
                    // Time up: Disable flight
                    if (!f.isCreative && !p.isSpectator()) {
                        p.getAbilities().allowFlying = false;
                        p.getAbilities().flying = false;
                        p.sendAbilitiesUpdate();
                    }
                    // End sound (Extinguish)
                    world.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_LAVA_EXTINGUISH, SoundCategory.PLAYERS, 0.5f, 2.0f);
                    if (shown) {
                        world.spawnParticles(ParticleTypes.CLOUD, p.getX(), p.getY() - 0.3, p.getZ(), 25, 0.9, 0.15, 0.9, 0.02);
                    }
                    it.remove();
                }
            }
        });
    }
}
