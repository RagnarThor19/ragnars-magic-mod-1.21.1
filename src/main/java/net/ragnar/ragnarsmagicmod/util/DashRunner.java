package net.ragnar.ragnarsmagicmod.util;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.entity.player.PlayerEntity;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

public final class DashRunner {
    private DashRunner() {}

    private static boolean registered = false;

    private record ActiveDash(ServerWorld world, java.util.UUID playerId, Vec3d dir, int ticksLeft,
                              double hitRadius, float damage, double knock, double knockUp) {}

    private static final List<ActiveDash> ACTIVE = new ArrayList<>();

    public static void start(ServerWorld world,
                             PlayerEntity player,
                             Vec3d dir,
                             int durationTicks,
                             double hitRadius,
                             float damage,
                             double knock,
                             double knockUp) {
        ensureRegistered();
        ACTIVE.add(new ActiveDash(world, player.getUuid(), dir.normalize(),
                durationTicks, hitRadius, damage, knock, knockUp));
    }


    private static void ensureRegistered() {
        if (registered) return;
        registered = true;

        ServerTickEvents.END_WORLD_TICK.register(world -> {
            if (ACTIVE.isEmpty()) return;

            Iterator<ActiveDash> it = ACTIVE.iterator();
            List<ActiveDash> requeue = new ArrayList<>();

            while (it.hasNext()) {
                ActiveDash d = it.next();
                if (d.world != world) continue;

                PlayerEntity p = d.world.getPlayerByUuid(d.playerId());
                if (p == null || p.isRemoved() || !p.isAlive()) { it.remove(); continue; }

                // Trail particles
                world.spawnParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 0.1, p.getZ(),
                        10, 0.25, 0.12, 0.25, 0.04);
                world.spawnParticles(ParticleTypes.SWEEP_ATTACK, p.getX(), p.getY() + 0.9, p.getZ(),
                        1, 0, 0, 0, 0);

                // Hit entities around current player position
                Box box = p.getBoundingBox().expand(d.hitRadius, 0.8, d.hitRadius);
                for (Entity e : world.getOtherEntities(p, box, en -> en.isAlive() && !en.isSpectator())) {
                    if (e instanceof LivingEntity le) {
                        // light damage + knock away in dash direction
                        le.damage(world.getDamageSources().playerAttack(p), d.damage);
                        le.addVelocity(d.dir.x * d.knock, d.knockUp, d.dir.z * d.knock);
                        le.velocityDirty = true;
                        // little impact sound once in a while
                        world.playSound(null, e.getBlockPos(),
                                SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.5f, 1.35f);
                    }
                }

                int t = d.ticksLeft - 1;
                if (t <= 0) {
                    it.remove();
                } else {
                    requeue.add(new ActiveDash(d.world, d.playerId, d.dir, t, d.hitRadius, d.damage, d.knock, d.knockUp));
                    it.remove();
                }
            }

            ACTIVE.addAll(requeue);
        });
    }
}
