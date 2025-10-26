package net.ragnar.ragnarsmagicmod.util;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class MeteorEffects {
    private MeteorEffects() {}

    private static boolean registered = false;

    private record Trail(ServerWorld world, UUID uuid, int ticksRemaining, double radius) {}

    private static final List<Trail> ACTIVE = new ArrayList<>();

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;

        ServerTickEvents.END_WORLD_TICK.register(world -> {
            if (ACTIVE.isEmpty()) return;

            Iterator<Trail> it = ACTIVE.iterator();
            List<Trail> requeue = new ArrayList<>();

            while (it.hasNext()) {
                Trail t = it.next();
                if (t.world() != world) continue; // handled on its own world tick

                var entity = world.getEntity(t.uuid());
                int remaining = t.ticksRemaining();

                if (entity == null || !entity.isAlive() || remaining <= 0) {
                    it.remove();
                    continue;
                }

                // Big fiery/smoky shell to fake a huge meteor volume (≈ 8x8)
                double cx = entity.getX();
                double cy = entity.getY();
                double cz = entity.getZ();

                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                double R = t.radius(); // ~4.0 => 8-wide visual

                // Dense hot core
                world.spawnParticles(ParticleTypes.FLAME, cx, cy, cz, 160, 0.6, 0.6, 0.6, 0.02);
                world.spawnParticles(ParticleTypes.SMOKE, cx, cy, cz, 200, 1.2, 1.2, 1.2, 0.04);
                world.spawnParticles(ParticleTypes.LARGE_SMOKE, cx, cy, cz, 90, 1.6, 1.6, 1.6, 0.02);
                world.spawnParticles(ParticleTypes.LAVA, cx, cy, cz, 60, 0.5, 0.5, 0.5, 0.0);

                // Outer embers shell for volume (random ring around)
                for (int i = 0; i < 30; i++) {
                    double theta = rnd.nextDouble(0, Math.PI * 2);
                    double phi = Math.acos(rnd.nextDouble(-1, 1)); // uniform sphere
                    double r = R * (0.8 + rnd.nextDouble(0.2));    // ~R .. ~1.0R
                    double ox = r * Math.sin(phi) * Math.cos(theta);
                    double oy = r * Math.cos(phi);
                    double oz = r * Math.sin(phi) * Math.sin(theta);
                    world.spawnParticles(ParticleTypes.SMALL_FLAME, cx + ox, cy + oy, cz + oz, 5, 0, 0, 0, 0.0);
                }

                // Occasional whoosh
                if (remaining % 10 == 0) {
                    world.playSound(
                            null, cx, cy, cz,
                            SoundEvents.ENTITY_BLAZE_SHOOT,
                            SoundCategory.PLAYERS,
                            0.35f, 0.6f
                    );
                }

                it.remove();
                requeue.add(new Trail(world, t.uuid(), remaining - 1, R));
            }

            ACTIVE.addAll(requeue);
        });
    }

    /**
     * Attach a big particle trail to an entity for 'ticks' ticks.
     * @param radius visual radius (4.0 ≈ 8-wide look)
     */
    public static void attach(ServerWorld world, java.util.UUID entityUuid, int ticks, double radius) {
        ensureRegistered();
        ACTIVE.add(new Trail(world, entityUuid, Math.max(1, ticks), radius));
    }
}
