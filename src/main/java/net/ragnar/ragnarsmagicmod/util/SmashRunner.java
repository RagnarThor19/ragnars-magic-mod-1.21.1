package net.ragnar.ragnarsmagicmod.util;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
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

public final class SmashRunner {
    private SmashRunner() {}

    private static boolean registered = false;

    // Phases: 0 = Launching/Rising, 1 = Smashing (Falling)
    private record ActiveSmash(ServerWorld world, UUID playerId, int age, int phase) {}

    private static final List<ActiveSmash> ACTIVE = new ArrayList<>();

    // Tweakables
    private static final int PEAK_TIME = 15; // Ticks to reach peak/hang
    private static final double SMASH_RADIUS = 5.0;
    private static final float DAMAGE = 12.0f; // 6 Hearts

    public static void start(ServerWorld world, PlayerEntity player) {
        ensureRegistered();
        // 1. Launch Effect
        player.addVelocity(0, 1.35, 0); // approx 10-12 blocks
        player.velocityModified = true;
        player.velocityDirty = true;

        world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_WIND_CHARGE_THROW, SoundCategory.PLAYERS, 1.0f, 0.8f);
        world.spawnParticles(ParticleTypes.CLOUD, player.getX(), player.getY(), player.getZ(), 10, 0.2, 0.1, 0.2, 0.1);

        ACTIVE.add(new ActiveSmash(world, player.getUuid(), 0, 0));
    }

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;

        ServerTickEvents.END_WORLD_TICK.register(world -> {
            if (ACTIVE.isEmpty()) return;

            Iterator<ActiveSmash> it = ACTIVE.iterator();
            List<ActiveSmash> requeue = new ArrayList<>();

            while (it.hasNext()) {
                ActiveSmash s = it.next();
                if (s.world != world) continue;

                PlayerEntity p = world.getPlayerByUuid(s.playerId);
                if (p == null || p.isRemoved() || !p.isAlive()) {
                    it.remove();
                    continue;
                }

                // Always Negate fall damage during the move
                p.fallDistance = 0;

                int nextPhase = s.phase;
                int nextAge = s.age + 1;
                boolean keepGoing = true;

                if (s.phase == 0) {
                    // RISING PHASE
                    // Particle trail
                    if (nextAge % 2 == 0) {
                        world.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, p.getX(), p.getY(), p.getZ(), 1, 0, 0, 0, 0);
                    }

                    // Check if we hit peak or waiting time is over
                    if (nextAge >= PEAK_TIME || p.getVelocity().y < -0.1) {
                        // SWITCH TO SMASH
                        nextPhase = 1;
                        // Force Downward Velocity
                        p.setVelocity(0, -3.5, 0);
                        p.velocityModified = true;
                        p.velocityDirty = true;

                        // Sound for the downward thrust
                        world.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_BREEZE_IDLE_AIR, SoundCategory.PLAYERS, 1.0f, 0.5f);
                    }
                } else {
                    // FALLING/SMASH PHASE
                    // Keep forcing down in case they hit water or drag slows them
                    p.addVelocity(0, -0.2, 0);
                    p.velocityModified = true;

                    // Trail
                    world.spawnParticles(ParticleTypes.SONIC_BOOM, p.getX(), p.getY(), p.getZ(), 1, 0,0,0, 0);

                    if (p.isOnGround()) {
                        // --- IMPACT ---
                        performImpact(world, p);
                        keepGoing = false; // Stop tracking
                    }

                    // Safety timeout (if they fall into void)
                    if (nextAge > 100) keepGoing = false;
                }

                if (keepGoing) {
                    requeue.add(new ActiveSmash(s.world, s.playerId, nextAge, nextPhase));
                }
                it.remove();
            }
            ACTIVE.addAll(requeue);
        });
    }

    private static void performImpact(ServerWorld world, PlayerEntity p) {
        // 1. Sounds
        // Use Mace smash sound if available in 1.21, fallback to Heavy Smash/Anvil
        world.playSound(null, p.getBlockPos(), SoundEvents.ITEM_MACE_SMASH_GROUND, SoundCategory.PLAYERS, 1.5f, 1.0f);

        // 2. Particles (Cool Shockwave)
        // Center "Poof"
        world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, p.getX(), p.getY(), p.getZ(), 1, 0, 0, 0, 0);
        // Ring of debris/smoke
        world.spawnParticles(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, p.getX(), p.getY(), p.getZ(), 20, 1.5, 0.2, 1.5, 0.1);
        // Sweep attack particles flying out
        world.spawnParticles(ParticleTypes.SWEEP_ATTACK, p.getX(), p.getY() + 0.5, p.getZ(), 8, 1.0, 0.1, 1.0, 0.5);

        // 3. AoE Logic
        Box box = p.getBoundingBox().expand(SMASH_RADIUS, 2.0, SMASH_RADIUS);
        List<Entity> targets = world.getOtherEntities(p, box, e -> e instanceof LivingEntity && !e.isSpectator());

        for (Entity e : targets) {
            if (e instanceof LivingEntity living) {
                // Damage
                living.damage(world.getDamageSources().playerAttack(p), DAMAGE);

                // Knockback: Up and Away
                Vec3d vec = e.getPos().subtract(p.getPos()).normalize();
                // If directly on top, pick random dir
                if (vec.lengthSquared() < 0.0001) vec = new Vec3d(1, 0, 0);

                // Strong upward punt + outward push
                living.setVelocity(vec.x * 1.5, 0.8, vec.z * 1.5);
                living.velocityModified = true;
            }
        }
    }
}