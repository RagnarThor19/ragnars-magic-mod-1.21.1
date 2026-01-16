// File: src/main/java/net/ragnar/ragnarsmagicmod/util/VoidZone.java
package net.ragnar.ragnarsmagicmod.util;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

public final class VoidZone {
    private VoidZone() {}

    private static boolean registered = false;

    private record ActiveZone(
            ServerWorld w,
            List<BlockPos> floorBlocks,
            UUID casterId,
            int ticksExisted,
            int maxDuration
    ) {
        private static class State { int t; }
    }

    private static class ZoneState {
        ActiveZone zone;
        int age;

        ZoneState(ActiveZone z) { this.zone = z; this.age = 0; }
    }

    private static final List<ZoneState> ZONES = new ArrayList<>();

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;

        ServerTickEvents.END_WORLD_TICK.register(world -> {
            if (ZONES.isEmpty()) return;

            Iterator<ZoneState> it = ZONES.iterator();
            while (it.hasNext()) {
                ZoneState s = it.next();
                if (s.zone.w() != world) continue;

                s.age++;

                // 1. WARNING PHASE (0 - 20 ticks)
                if (s.age < 20) {
                    if (s.age == 1) {
                        BlockPos center = s.zone.floorBlocks().get(s.zone.floorBlocks().size() / 2);
                        world.playSound(null, center, SoundEvents.BLOCK_SCULK_SHRIEKER_SHRIEK, SoundCategory.PLAYERS, 0.5f, 0.5f);
                    }
                    if (s.age % 5 == 0) {
                        for (BlockPos pos : s.zone.floorBlocks()) {
                            // Warning smoke raised slightly too
                            world.spawnParticles(ParticleTypes.SMOKE,
                                    pos.getX() + 0.5, pos.getY() + 1.5, pos.getZ() + 0.5,
                                    1, 0.2, 0.1, 0.2, 0.01);
                        }
                    }
                }

                // 2. ACTIVATION
                else if (s.age == 20) {
                    BlockPos center = s.zone.floorBlocks().get(s.zone.floorBlocks().size() / 2);
                    world.playSound(null, center, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.PLAYERS, 1.0f, 0.5f);
                    world.playSound(null, center, SoundEvents.ENTITY_WITHER_SPAWN, SoundCategory.PLAYERS, 0.75f, 0.5f);
                }

                // 3. ACTIVE PHASE
                else {
                    // FIX: Raised particles from +0.5 to +1.2 so they don't clip into ground
                    for (BlockPos pos : s.zone.floorBlocks()) {
                        world.spawnParticles(ParticleTypes.SQUID_INK,
                                pos.getX() + 0.5, pos.getY() + 0.8, pos.getZ() + 0.5,
                                2, 0.4, 0.1, 0.4, 0.01);

                        if (world.random.nextFloat() < 0.05f) {
                            world.spawnParticles(ParticleTypes.SCULK_SOUL,
                                    pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5,
                                    1, 0.2, 0.2, 0.2, 0.05);
                        }
                    }

                    if (s.age % 10 == 0) {
                        damageEntitiesInZone(s.zone);
                    }
                }

                if (s.age >= s.zone.maxDuration()) {
                    it.remove();
                }
            }
        });
    }

    public static void create(ServerWorld world, Vec3d centerPos, UUID casterId) {
        ensureRegistered();

        List<BlockPos> floorBlocks = new ArrayList<>();
        BlockPos center = BlockPos.ofFloored(centerPos);


        int radius = 5;

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                BlockPos colCenter = center.add(x, 0, z);
                BlockPos surface = findSurface(world, colCenter);
                if (surface != null) {
                    floorBlocks.add(surface);
                }
            }
        }

        if (!floorBlocks.isEmpty()) {
            ZONES.add(new ZoneState(new ActiveZone(world, floorBlocks, casterId, 0, 120)));
        }
    }

    private static BlockPos findSurface(ServerWorld w, BlockPos start) {
        for (int i = 4; i >= -4; i--) {
            BlockPos p = start.up(i);
            BlockState state = w.getBlockState(p);
            BlockState above = w.getBlockState(p.up());

            if (state.isSolidBlock(w, p) && !above.isSolidBlock(w, p.up())) {
                return p;
            }
        }
        return null;
    }

    private static void damageEntitiesInZone(ActiveZone z) {
        for (BlockPos pos : z.floorBlocks()) {
            Box box = new Box(pos).expand(0.0, 1.5, 0.0);

            List<LivingEntity> targets = z.w().getEntitiesByClass(LivingEntity.class, box, e -> true);
            for (LivingEntity e : targets) {
                if (z.casterId() != null && e.getUuid().equals(z.casterId())) continue;

                // 6.0F (3 hearts) every 10 ticks = 12.0F (6 hearts) per second
                e.damage(z.w().getDamageSources().magic(), 8.0f);
            }
        }
    }
}