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
            UUID casterId
    ) {}

    private record ActiveSpike(ServerWorld w, BlockPos bottom, BlockPos top, int ticksLeft) {}

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
                                p.casterId()                 // <<< keep it
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
                        // break without drops + particles + sound
                        a.w().breakBlock(a.top(), false);
                        a.w().breakBlock(a.bottom(), false);
                        impactFX(a.w(), a.bottom().getX() + 0.5, a.bottom().getY(), a.bottom().getZ() + 0.5, true);
                        it2.remove();
                    } else {
                        requeue2.add(new ActiveSpike(a.w(), a.bottom(), a.top(), t));
                        it2.remove();
                    }
                }
                ACTIVE.addAll(requeue2);
            }
        });
    }

    /** Queue an entire 3-lane wave forward. */
    public static void queueWave(ServerWorld world, Vec3d origin, Vec3d forward,
                                 int length, int[] laneOffsets, int stepDelayTicks,
                                 int lifetimeTicks, UUID casterId) {
        ensureRegistered();
        Vec3d f = new Vec3d(forward.x, 0, forward.z);
        if (f.lengthSquared() < 1.0e-6) return;
        f = f.normalize();
        Vec3d s = new Vec3d(-f.z, 0, f.x); // left

        double startY = origin.y + 6.0;
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
                        casterId
                ));
            }
        }
    }



    private static void spawnSpike(PendingSpawn p) {
        // find ground at (x,z)
        int startYInt = MathHelper.floor(p.startY());
        BlockPos ground = findGround(p.w(), MathHelper.floor(p.x()), MathHelper.floor(p.z()), startYInt);
        if (ground == null) return;

        BlockPos bottomPos = ground.up(1);
        BlockPos topPos    = ground.up(2);

        // need 2-block headroom
        if (!isAiry(p.w(), bottomPos) || !isAiry(p.w(), topPos)) return;

        BlockState bottom = Blocks.POINTED_DRIPSTONE.getDefaultState()
                .with(PointedDripstoneBlock.VERTICAL_DIRECTION, Direction.UP)
                .with(PointedDripstoneBlock.THICKNESS, Thickness.FRUSTUM)
                .with(PointedDripstoneBlock.WATERLOGGED, false);

        BlockState top = Blocks.POINTED_DRIPSTONE.getDefaultState()
                .with(PointedDripstoneBlock.VERTICAL_DIRECTION, Direction.UP)
                .with(PointedDripstoneBlock.THICKNESS, Thickness.TIP)
                .with(PointedDripstoneBlock.WATERLOGGED, false);

        // place both blocks
        p.w().setBlockState(bottomPos, bottom, 3);
        p.w().setBlockState(topPos, top, 3);

        // FX on spawn
        impactFX(p.w(), bottomPos.getX() + 0.5, bottomPos.getY(), bottomPos.getZ() + 0.5, false);

        // damage + knock
        Box aabb = new Box(bottomPos).expand(0.85, 1.2, 0.85);
        for (Entity e : p.w().getOtherEntities(null, aabb, en -> en.isAlive() && !en.isSpectator())) {
            if (e instanceof LivingEntity le) {
                // 6 hearts = 12.0F
                le.damage(p.w().getDamageSources().stalagmite(), 12.0F);
                // knock upward and forward
                le.addVelocity(p.dirX() * 0.35, 0.6, p.dirZ() * 0.35);
                le.velocityDirty = true;
            }
        }
        for (Entity e : p.w().getOtherEntities(null, aabb, en -> en.isAlive() && !en.isSpectator())) {
            if (e.getUuid().equals(p.casterId())) continue; // don't hit the caster
            if (e instanceof LivingEntity le) {
                le.damage(p.w().getDamageSources().stalagmite(), 12.0F); // 6 hearts
                le.addVelocity(p.dirX() * 0.35, 0.6, p.dirZ() * 0.35);
                le.velocityDirty = true;
            }
        }

        // queue despawn
        ACTIVE.add(new ActiveSpike(p.w(), bottomPos.toImmutable(), topPos.toImmutable(), p.lifetime()));
    }

    private static void impactFX(ServerWorld w, double x, double y, double z, boolean breakSound) {
        var effect = new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.DRIPSTONE_BLOCK.getDefaultState());
        w.spawnParticles(effect, x, y, z, 24, 0.5, 0.25, 0.5, 0.08);
        w.spawnParticles(ParticleTypes.POOF, x, y + 0.2, z, 8, 0.2, 0.2, 0.2, 0.02);
        // sound
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
            if (!state.getFluidState().isEmpty()) continue; // skip water/lava surfaces
            if (!state.getCollisionShape(w, pos).isEmpty()) {
                return pos; // this is the ground block
            }
        }
        return null;
    }

    private static boolean isAiry(ServerWorld w, BlockPos pos) {
        var state = w.getBlockState(pos);
        return state.isAir() || state.getCollisionShape(w, pos).isEmpty();
    }


}
