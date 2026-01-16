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

        // UPDATED SETTINGS
        final float maxRadius = 22.0f;   // Reduced to 22 blocks
        final int expansionTicks = 40;   // 2.0 seconds to expand
        final int holdTicks = 10;        // 0.5 seconds hold

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
                int detonateTick = s.expansionTicks + s.holdTicks;

                // 1. EXPANSION PHASE
                if (s.age <= s.expansionTicks) {
                    s.currentRadius = (float) s.age / s.expansionTicks * s.maxRadius;

                    if (s.age % 5 == 0) {
                        float progress = (float) s.age / s.expansionTicks;
                        world.playSound(null, s.center.x, s.center.y, s.center.z,
                                SoundEvents.BLOCK_GLASS_PLACE, SoundCategory.PLAYERS, 2.0f, 0.5f + progress);
                    }
                    spawnSphereParticles(s.world, s.center, s.currentRadius);
                }

                // 2. HOLD PHASE
                else if (s.age <= detonateTick) {
                    s.currentRadius = s.maxRadius;
                    spawnSphereParticles(s.world, s.center, s.maxRadius);
                }

                // 3. DETONATION
                if (s.age == detonateTick) {
                    freezeEverything(s);
                }

                if (s.age > detonateTick) {
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

        spawnSphereParticles(s.world, s.center, s.maxRadius);
        spawnSphereParticles(s.world, s.center, s.maxRadius * 0.95f);

        Box box = Box.of(s.center, s.maxRadius * 2, s.maxRadius * 2, s.maxRadius * 2);
        List<LivingEntity> targets = s.world.getEntitiesByClass(LivingEntity.class, box, e -> e.squaredDistanceTo(s.center) <= s.maxRadius * s.maxRadius);

        for (LivingEntity e : targets) {
            if (s.casterId != null && e.getUuid().equals(s.casterId)) continue;

            // Damage + Status Effects
            e.damage(s.world.getDamageSources().freeze(), 10.0f);
            e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 100, 255, false, false, true));
            e.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE, 100, 255, false, false, true));
            e.setFrozenTicks(400);

            // NEW: "Ice Breaking" Particles
            // We use BLOCK particles of ICE, spawned within the entity's width/height.
            // Speed (0.02) is very low so they linger/cling rather than flying away.
            s.world.spawnParticles(
                    new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.ICE.getDefaultState()),
                    e.getX(), e.getY() + e.getHeight() / 2.0, e.getZ(),
                    50,                      // Count
                    e.getWidth() / 2.0,      // X Spread
                    e.getHeight() / 2.0,     // Y Spread
                    e.getWidth() / 2.0,      // Z Spread
                    0.001                     // Speed (Very Slow)
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