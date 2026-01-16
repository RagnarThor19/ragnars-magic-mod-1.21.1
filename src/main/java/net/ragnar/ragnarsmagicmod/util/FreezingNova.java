// File: src/main/java/net/ragnar/ragnarsmagicmod/util/FreezingNova.java
package net.ragnar.ragnarsmagicmod.util;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Box;
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

        // Settings
        final float maxRadius = 22.0f;
        final int expansionTicks = 40;   // 2.0s expand
        final int holdTicks = 10;        // 0.5s hold
        final int freezeDuration = 100;  // 5.0s freeze duration

        // Track victims to keep spawning particles on them
        List<LivingEntity> frozenTargets = new ArrayList<>();

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
                int detonateTick = s.expansionTicks + s.holdTicks;       // 50
                int finishTick = detonateTick + s.freezeDuration;        // 150

                // 1. EXPANSION (0-40)
                if (s.age <= s.expansionTicks) {
                    s.currentRadius = (float) s.age / s.expansionTicks * s.maxRadius;
                    if (s.age % 5 == 0) {
                        float progress = (float) s.age / s.expansionTicks;
                        world.playSound(null, s.center.x, s.center.y, s.center.z,
                                SoundEvents.BLOCK_GLASS_PLACE, SoundCategory.PLAYERS, 2.0f, 0.5f + progress);
                    }
                    spawnSphereParticles(s.world, s.center, s.currentRadius);
                }

                // 2. HOLD (41-50)
                else if (s.age <= detonateTick) {
                    s.currentRadius = s.maxRadius;
                    spawnSphereParticles(s.world, s.center, s.maxRadius);
                }

                // 3. DETONATION (50)
                if (s.age == detonateTick) {
                    freezeEverything(s);
                }

                // 4. LINGERING (51-150)
                // Continuously spawn particles on victims until they unfreeze
                if (s.age > detonateTick && s.age <= finishTick) {
                    spawnLingeringParticles(s);
                }

                // cleanup
                if (s.age > finishTick) {
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
        s.world.playSound(null, s.center.x, s.center.y, s.center.z,
                SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 4.0f, 0.6f);
        s.world.playSound(null, s.center.x, s.center.y, s.center.z,
                SoundEvents.ITEM_TRIDENT_THUNDER, SoundCategory.PLAYERS, 2.0f, 1.2f);

        // Explosion visuals
        spawnSphereParticles(s.world, s.center, s.maxRadius);
        spawnSphereParticles(s.world, s.center, s.maxRadius * 0.95f);

        Box box = Box.of(s.center, s.maxRadius * 2, s.maxRadius * 2, s.maxRadius * 2);
        List<LivingEntity> targets = s.world.getEntitiesByClass(LivingEntity.class, box, e -> e.squaredDistanceTo(s.center) <= s.maxRadius * s.maxRadius);

        for (LivingEntity e : targets) {
            if (s.casterId != null && e.getUuid().equals(s.casterId)) continue;

            // Damage + Effect
            e.damage(s.world.getDamageSources().freeze(), 10.0f);
            e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 100, 255, false, false, true));
            e.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE, 100, 255, false, false, true));

            // Visual Freeze (Screen Tint / Shaking)
            e.setFrozenTicks(240);

            // Add to lingering list
            s.frozenTargets.add(e);
        }
    }

    private static void spawnLingeringParticles(NovaState s) {
        // Run every tick for smooth visuals
        var iceParticle = new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.ICE.getDefaultState());

        for (LivingEntity e : s.frozenTargets) {
            if (!e.isAlive()) continue;

            // Spawn 2 particles per tick on every frozen entity
            // This creates a constant "crumbling ice" effect clinging to them
            s.world.spawnParticles(
                    iceParticle,
                    e.getX(), e.getY() + e.getHeight() / 2.0, e.getZ(),
                    2,                       // Count (Low per tick, but constant)
                    e.getWidth() / 2.0,      // X Spread
                    e.getHeight() / 2.0,     // Y Spread
                    e.getWidth() / 2.0,      // Z Spread
                    0.02                     // Speed
            );
        }
    }

    private static void spawnSphereParticles(ServerWorld w, Vec3d center, float radius) {
        int particleCount = (int) (radius * 12);
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