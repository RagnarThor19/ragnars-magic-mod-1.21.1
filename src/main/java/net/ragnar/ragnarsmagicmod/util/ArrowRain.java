// File: src/main/java/net/ragnar/ragnarsmagicmod/util/ArrowRain.java
package net.ragnar.ragnarsmagicmod.util;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes; // Added Import
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Vec3d;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

public final class ArrowRain {
    private ArrowRain() {}

    private static boolean registered = false;

    private static class RainState {
        ServerWorld world;
        Vec3d targetCenter;
        UUID casterId;
        int age;

        final int duration = 50; // 3 Seconds
        final double radius = 2.5;
        final double spawnHeight = 15.0;

        RainState(ServerWorld w, Vec3d c, UUID id) {
            this.world = w;
            this.targetCenter = c;
            this.casterId = id;
            this.age = 0;
        }
    }

    private static final List<RainState> RAINS = new ArrayList<>();

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;

        ServerTickEvents.END_WORLD_TICK.register(world -> {
            if (RAINS.isEmpty()) return;

            Iterator<RainState> it = RAINS.iterator();
            while (it.hasNext()) {
                RainState s = it.next();
                if (s.world != world) continue;

                s.age++;

                if (s.age <= s.duration) {
                    // Logic preserved: Spawns 2 arrows every 2 ticks
                    if (s.age % 2 == 0) {
                        spawnArrow(s);
                        spawnArrow(s);
                    }

                    if (s.age % 10 == 0) {
                        float weirdPitch = 0.5f + s.world.random.nextFloat();
                        s.world.playSound(null, s.targetCenter.x, s.targetCenter.y + s.spawnHeight, s.targetCenter.z,
                                SoundEvents.ENTITY_PHANTOM_FLAP, SoundCategory.PLAYERS, 2.0f, weirdPitch);
                    }
                } else {
                    it.remove();
                }
            }
        });
    }

    public static void create(ServerWorld world, Vec3d center, UUID casterId) {
        ensureRegistered();
        RAINS.add(new RainState(world, center, casterId));
    }

    private static void spawnArrow(RainState s) {
        double theta = s.world.random.nextDouble() * 2 * Math.PI;
        double r = Math.sqrt(s.world.random.nextDouble()) * s.radius;

        double x = s.targetCenter.x + r * Math.cos(theta);
        double z = s.targetCenter.z + r * Math.sin(theta);
        double y = s.targetCenter.y + s.spawnHeight + (s.world.random.nextDouble() * 4.0);

        // NEW: Spawn a small cloud particle at the arrow's origin
        s.world.spawnParticles(ParticleTypes.CLOUD, x, y, z, 2, 0.1, 0.1, 0.1, 0.02);

        // Robust Despawn Logic
        ArrowEntity arrow = new ArrowEntity(s.world, x, y, z, new ItemStack(Items.ARROW), null) {

            @Override
            protected void onEntityHit(EntityHitResult entityHitResult) {
                super.onEntityHit(entityHitResult);
                if (!this.getWorld().isClient) {
                    this.discard();
                }
            }

            @Override
            protected void onBlockHit(BlockHitResult blockHitResult) {
                super.onBlockHit(blockHitResult);
                if (!this.getWorld().isClient) {
                    this.discard();
                }
            }

            @Override
            public void tick() {
                super.tick();
                if (!this.getWorld().isClient && this.age > 100) {
                    this.discard();
                }
            }
        };

        if (s.casterId != null) {
            var player = s.world.getPlayerByUuid(s.casterId);
            if (player != null) arrow.setOwner(player);
        }

        arrow.setDamage(3.5);
        arrow.pickupType = PersistentProjectileEntity.PickupPermission.DISALLOWED;

        arrow.setVelocity(0, -4.5, 0, 4.5f, 12.0f);

        s.world.spawnEntity(arrow);
    }
}