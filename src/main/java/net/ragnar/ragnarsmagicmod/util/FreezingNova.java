// File: src/main/java/net/ragnar/ragnarsmagicmod/util/FreezingNova.java
package net.ragnar.ragnarsmagicmod.util;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

public final class FreezingNova {
    private FreezingNova() {}

    private static boolean registered = false;

    private static class NovaState {
        ServerWorld world;
        Vec3d center;
        UUID casterId;
        int age;
        float currentRadius;
        final float maxRadius = 15.0f;
        final int expansionTicks = 30; // 1.5 seconds to expand

        NovaState(ServerWorld w, Vec3d c, UUID id) {
            this.world = w;
            this.center = c;
            this.casterId = id;
            this.age = 0;
            this.currentRadius = 0;
        }
    }

    private static final List<NovaState> NOVAS = new ArrayList<>();

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;

        ServerTickEvents.END_WORLD_TICK.register(world -> {
            if (NOVAS.isEmpty()) return;

            Iterator<NovaState> it = NOVAS.iterator();
            while (it.hasNext()) {
                NovaState s = it.next();
                if (s.world != world) continue;

                s.age++;

                // 1. EXPANSION PHASE
                if (s.age <= s.expansionTicks) {
                    // Linearly increase radius
                    s.currentRadius = (float) s.age / s.expansionTicks * s.maxRadius;

                    // Play charging sound (pitch rises as it gets bigger)
                    if (s.age % 5 == 0) {
                        float progress = (float) s.age / s.expansionTicks;
                        world.playSound(null, s.center.x, s.center.y, s.center.z,
                                SoundEvents.BLOCK_GLASS_PLACE, SoundCategory.PLAYERS, 1.0f, 0.5f + progress);
                    }

                    // Spawn Sphere Particles
                    spawnSphereParticles(s.world, s.center, s.currentRadius);
                }

                // 2. DETONATION (Exact moment expansion finishes)
                if (s.age == s.expansionTicks) {
                    freezeEverything(s);
                }

                // 3. LINGERING EFFECT (Optional: Keep showing particles on frozen targets?)
                // For now, we remove immediately after detonation as the status effects handle the rest.
                if (s.age > s.expansionTicks) {
                    it.remove();
                }
            }
        });
    }

    public static void create(ServerWorld world, Vec3d center, UUID casterId) {
        ensureRegistered();
        NOVAS.add(new NovaState(world, center, casterId));
    }

    private static void freezeEverything(NovaState s) {
        // Big shatter sound
        s.world.playSound(null, s.center.x, s.center.y, s.center.z,
                SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 2.0f, 0.6f);
        s.world.playSound(null, s.center.x, s.center.y, s.center.z,
                SoundEvents.ITEM_TRIDENT_THUNDER, SoundCategory.PLAYERS, 1.0f, 1.2f);

        // Flash of particles at max radius
        spawnSphereParticles(s.world, s.center, s.maxRadius);
        spawnSphereParticles(s.world, s.center, s.maxRadius * 0.8f);

        // Find targets
        Box box = Box.of(s.center, s.maxRadius * 2, s.maxRadius * 2, s.maxRadius * 2);
        List<LivingEntity> targets = s.world.getEntitiesByClass(LivingEntity.class, box, e -> e.squaredDistanceTo(s.center) <= s.maxRadius * s.maxRadius);

        for (LivingEntity e : targets) {
            if (s.casterId != null && e.getUuid().equals(s.casterId)) continue; // Don't freeze caster

            // 1. Damage (10 Frost Damage = 5 Hearts)
            e.damage(s.world.getDamageSources().freeze(), 10.0f);

            // 2. Freeze Effect (5 seconds = 100 ticks)
            // Slowness 255 (Immobile) + Mining Fatigue 255 (Can't swing)
            e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 100, 255, false, false, true));
            e.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE, 100, 255, false, false, true));

            // Visual tint
            e.setFrozenTicks(140); // Vanilla visual freeze effect

            // Particle burst on entity
            s.world.spawnParticles(ParticleTypes.SNOWFLAKE, e.getX(), e.getY() + 1, e.getZ(), 20, 0.5, 1.0, 0.5, 0.1);
        }
    }

    private static void spawnSphereParticles(ServerWorld w, Vec3d center, float radius) {
        // Spawn ~50 particles per tick distributed on the sphere surface
        int particleCount = (int) (radius * 8);
        for (int i = 0; i < particleCount; i++) {
            double theta = w.random.nextDouble() * 2 * Math.PI;
            double phi = Math.acos(2 * w.random.nextDouble() - 1);

            double x = center.x + radius * Math.sin(phi) * Math.cos(theta);
            double y = center.y + radius * Math.sin(phi) * Math.sin(theta);
            double z = center.z + radius * Math.cos(phi);

            w.spawnParticles(ParticleTypes.SNOWFLAKE, x, y, z, 1, 0, 0, 0, 0);
        }
    }
}