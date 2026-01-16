// File: src/main/java/net/ragnar/ragnarsmagicmod/util/SpikeWave.java
package net.ragnar.ragnarsmagicmod.util;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.PointedDripstoneBlock;
import net.minecraft.block.enums.Thickness;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import java.util.UUID;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class SpikeWave {
    private SpikeWave() {}

    private static boolean registered = false;

    private record PendingSpawn(
            ServerWorld w, double x, double z, double startY,
            double dirX, double dirZ, int ticksUntil, int lifetime,
            UUID casterId,
            int height,
            double lift,
            float damage
    ) {}

    private record ActiveSpike(ServerWorld w, List<BlockPos> blocks, int ticksLeft) {}

    private static final List<PendingSpawn> SPAWNS = new ArrayList<>();
    private static final List<ActiveSpike> ACTIVE  = new ArrayList<>();

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;

        ServerTickEvents.END_WORLD_TICK.register(world -> {
            // handle spawns
            if (!SPAWNS.isEmpty()) {
                Iterator<PendingSpawn> it = SPAWNS.iterator();
                List<PendingSpawn> requeue = new ArrayList<>();
                while (it.hasNext()) {
                    PendingSpawn p = it.next();
                    if (p.w() != world) continue;
                    int t = p.ticksUntil() - 1;
                    if (t <= 0) {
                        spawnSpike(p);
                        it.remove();
                    } else {
                        requeue.add(new PendingSpawn(
                                p.w(), p.x(), p.z(), p.startY(),
                                p.dirX(), p.dirZ(),
                                t, p.lifetime(),
                                p.casterId(),
                                p.height(),
                                p.lift(),
                                p.damage()
                        ));
                        it.remove();
                    }
                }
                SPAWNS.addAll(requeue);
            }

            // handle despawns
            if (!ACTIVE.isEmpty()) {
                Iterator<ActiveSpike> it2 = ACTIVE.iterator();
                List<ActiveSpike> requeue2 = new ArrayList<>();
                while (it2.hasNext()) {
                    ActiveSpike a = it2.next();
                    if (a.w() != world) continue;
                    int t = a.ticksLeft() - 1;
                    if (t <= 0) {
                        BlockPos bottom = a.blocks.isEmpty() ? null : a.blocks.get(0);
                        for (BlockPos pos : a.blocks) {
                            a.w().breakBlock(pos, false);
                        }
                        if (bottom != null) {
                            impactFX(a.w(), bottom.getX() + 0.5, bottom.getY(), bottom.getZ() + 0.5, true);
                        }
                        it2.remove();
                    } else {
                        requeue2.add(new ActiveSpike(a.w(), a.blocks(), t));
                        it2.remove();
                    }
                }
                ACTIVE.addAll(requeue2);
            }
        });
    }

    /** Rising Spikes: h=2, lift=1.0 (Stronger to compensate for single loop), dmg=12 */
    public static void queueWave(ServerWorld world, Vec3d origin, Vec3d forward,
                                 int length, int[] laneOffsets, int stepDelayTicks,
                                 int lifetimeTicks, UUID casterId) {
        ensureRegistered();
        Vec3d f = new Vec3d(forward.x, 0, forward.z);
        if (f.lengthSquared() < 1.0e-6) return;
        f = f.normalize();
        Vec3d s = new Vec3d(-f.z, 0, f.x); // left

        // Using +2.5 offset for cave/tree compatibility
        double startY = origin.y + 2.5;
        for (int step = 1; step <= length; step++) {
            Vec3d base = origin.add(f.multiply(step));
            for (int off : laneOffsets) {
                Vec3d lane = base.add(s.multiply(off));
                SPAWNS.add(new PendingSpawn(
                        world,
                        lane.x, lane.z,
                        startY,
                        f.x, f.z,
                        step * stepDelayTicks,
                        lifetimeTicks,
                        casterId,
                        2,      // Height
                        1.3,    // Lift (Single loop now, so 1.3 is good)
                        12.0F   // Damage (6 Hearts)
                ));
            }
        }
    }

    /** Impaling: height custom, lift custom, damage custom */
    public static void queueSingleSpike(ServerWorld world, double x, double z, double startY,
                                        double dirX, double dirZ, int delayTicks, int lifetimeTicks,
                                        UUID casterId, int height, double lift, float damage) {
        ensureRegistered();
        SPAWNS.add(new PendingSpawn(
                world, x, z, startY,
                dirX, dirZ,
                delayTicks,
                lifetimeTicks,
                casterId,
                height,
                lift,
                damage
        ));
    }

    private static void spawnSpike(PendingSpawn p) {
        int startYInt = MathHelper.floor(p.startY());
        BlockPos ground = findGround(p.w(), MathHelper.floor(p.x()), MathHelper.floor(p.z()), startYInt);
        if (ground == null) return;

        List<BlockPos> placedPositions = new ArrayList<>();
        int h = Math.max(1, p.height());

        for (int i = 1; i <= h; i++) {
            if (!isAiry(p.w(), ground.up(i))) return;
        }

        // Apply Entity Effects (Damage + Launch + Teleport)
        // We do this BEFORE placing blocks (or essentially same tick) but we move them OUT of the block space.
        Box aabb = new Box(ground.up(1)).expand(0.85, h, 0.85);
        double topY = ground.getY() + 1.0 + h; // The Y level just above the spike tip

        for (Entity e : p.w().getOtherEntities(null, aabb, en -> en.isAlive() && !en.isSpectator())) {
            if (e instanceof LivingEntity le) {
                if (p.casterId() != null && le.getUuid().equals(p.casterId())) continue;

                // FIX: Pop entity to the top of the spike so they don't get stuck
                le.setPosition(le.getX(), topY, le.getZ());

                // Damage
                le.damage(p.w().getDamageSources().stalagmite(), p.damage());

                // Launch
                le.addVelocity(p.dirX() * 0.35, p.lift(), p.dirZ() * 0.35);
                le.velocityDirty = true;
            }
        }

        // Place Blocks
        for (int i = 1; i <= h; i++) {
            BlockPos pos = ground.up(i);
            Thickness thick;
            if (i == h) thick = Thickness.TIP;
            else if (i == h - 1) thick = Thickness.FRUSTUM;
            else if (i == 1) thick = Thickness.BASE;
            else thick = Thickness.MIDDLE;

            if (h == 2) {
                if (i == 1) thick = Thickness.FRUSTUM;
                if (i == 2) thick = Thickness.TIP;
            }

            BlockState state = Blocks.POINTED_DRIPSTONE.getDefaultState()
                    .with(PointedDripstoneBlock.VERTICAL_DIRECTION, Direction.UP)
                    .with(PointedDripstoneBlock.THICKNESS, thick)
                    .with(PointedDripstoneBlock.WATERLOGGED, false);

            p.w().setBlockState(pos, state, 3);
            placedPositions.add(pos);
        }

        BlockPos basePos = ground.up(1);
        impactFX(p.w(), basePos.getX() + 0.5, basePos.getY(), basePos.getZ() + 0.5, false);
        ACTIVE.add(new ActiveSpike(p.w(), placedPositions, p.lifetime()));
    }

    private static void impactFX(ServerWorld w, double x, double y, double z, boolean breakSound) {
        var effect = new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.DRIPSTONE_BLOCK.getDefaultState());
        w.spawnParticles(effect, x, y, z, 24, 0.5, 0.25, 0.5, 0.08);
        w.spawnParticles(ParticleTypes.POOF, x, y + 0.2, z, 8, 0.2, 0.2, 0.2, 0.02);
        if (breakSound) {
            w.playSound(null, x, y, z, SoundEvents.BLOCK_STONE_BREAK, SoundCategory.PLAYERS, 0.9f, 1.0f);
        } else {
            w.playSound(null, x, y, z, SoundEvents.BLOCK_STONE_PLACE, SoundCategory.PLAYERS, 0.8f, 0.9f);
            w.playSound(null, x, y, z, SoundEvents.BLOCK_TRIAL_SPAWNER_HIT, SoundCategory.PLAYERS, 0.4f, 0.8f);
        }
    }

    private static BlockPos findGround(ServerWorld w, int x, int z, int startY) {
        int minY = w.getBottomY() + 1;
        for (int y = startY; y >= minY; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            var state = w.getBlockState(pos);
            if (!state.getFluidState().isEmpty()) continue;
            if (!state.getCollisionShape(w, pos).isEmpty()) {
                return pos;
            }
        }
        return null;
    }

    private static boolean isAiry(ServerWorld w, BlockPos pos) {
        var state = w.getBlockState(pos);
        return state.isAir() || state.getCollisionShape(w, pos).isEmpty();
    }
}