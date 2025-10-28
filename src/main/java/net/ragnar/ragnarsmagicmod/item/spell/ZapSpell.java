package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.DustColorTransitionParticleEffect;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.List;

public final class ZapSpell implements Spell {

    private static final double RANGE = 32.0;
    private static final float DAMAGE = 5.0f;
    private static final double KNOCK = 0.6;
    private static final double KNOCK_UP = 0.10;

    // visuals
    private static final int SEGMENTS = 16;
    private static final double JITTER = 0.75;
    private static final int DENSITY_PER_EDGE = 5; // slightly fewer particles
    private static final double START_OFFSET = 1.8;

    // bright yellow
    private static final org.joml.Vector3f YELLOW = new org.joml.Vector3f(1.0f, 0.95f, 0.20f);
    private static final org.joml.Vector3f YELLOW_WHITE = new org.joml.Vector3f(1.0f, 1.0f, 0.85f);

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return true;

        Vec3d eye = player.getEyePos();
        Vec3d look = player.getRotationVec(1.0f).normalize();
        Vec3d start = eye.add(look.multiply(START_OFFSET));
        Vec3d endGuess = eye.add(look.multiply(RANGE));

        HitResult blockHit = world.raycast(new RaycastContext(
                start, endGuess, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
        double bestDist = start.distanceTo(((BlockHitResult) blockHit).getPos());
        Vec3d hitPos = ((BlockHitResult) blockHit).getPos();

        Entity hitEntity = getEntityOnLine(world, player, start, endGuess, bestDist);
        if (hitEntity != null) {
            hitPos = hitEntity.getPos().add(0, hitEntity.getStandingEyeHeight() * 0.5, 0);
        }

        if (world instanceof ServerWorld sw) {
            spawnJaggedYellowBeam(sw, start, hitPos, SEGMENTS, JITTER, DENSITY_PER_EDGE);

            DustParticleEffect yellowFlash = new DustParticleEffect(YELLOW, 1.4f);
            DustColorTransitionParticleEffect yellowBurst =
                    new DustColorTransitionParticleEffect(YELLOW, YELLOW_WHITE, 1.2f);

            sw.spawnParticles(yellowFlash, hitPos.x, hitPos.y, hitPos.z,
                    30, 0.30, 0.30, 0.30, 0.05);
            sw.spawnParticles(yellowBurst, hitPos.x, hitPos.y, hitPos.z,
                    24, 0.35, 0.35, 0.35, 0.04);
            sw.spawnParticles(ParticleTypes.ELECTRIC_SPARK, hitPos.x, hitPos.y, hitPos.z,
                    10, 0.20, 0.20, 0.20, 0.1);
        }

        player.playSound(net.ragnar.ragnarsmagicmod.sound.ModSoundEvents.ZAP_CAST, 1.0f, 1.0f);
        world.playSound(null, player.getBlockPos(), net.ragnar.ragnarsmagicmod.sound.ModSoundEvents.ZAP_CAST,
                SoundCategory.PLAYERS, 1.0f, 1.0f);
        world.playSound(null, BlockPos.ofFloored(hitPos),
                SoundEvents.BLOCK_REDSTONE_TORCH_BURNOUT, SoundCategory.PLAYERS, 0.75f, 1.8f);

        if (hitEntity instanceof LivingEntity le) {
            le.damage(world.getDamageSources().playerAttack(player), DAMAGE);
            le.addVelocity(look.x * KNOCK, KNOCK_UP, look.z * KNOCK);
            le.velocityDirty = true;
        }

        return true;
    }

    private Entity getEntityOnLine(World world, PlayerEntity player, Vec3d start, Vec3d end, double maxDist) {
        Box box = new Box(start, end).expand(1.0);
        List<Entity> list = world.getOtherEntities(player, box, e -> e.isAlive() && !e.isSpectator());
        Entity best = null;
        double bestSq = maxDist * maxDist;

        Vec3d seg = end.subtract(start);
        double segLen = seg.length();
        if (segLen < 1e-6) return null;
        Vec3d dir = seg.normalize();

        for (Entity e : list) {
            Vec3d p = e.getPos().add(0, e.getStandingEyeHeight() * 0.5, 0);
            double t = p.subtract(start).dotProduct(dir);
            t = MathHelper.clamp(t, 0.0, segLen);
            Vec3d closest = start.add(dir.multiply(t));
            double distSq = p.squaredDistanceTo(closest);
            if (distSq <= 0.9 * 0.9) {
                double startDistSq = start.squaredDistanceTo(p);
                if (startDistSq < bestSq) {
                    bestSq = startDistSq;
                    best = e;
                }
            }
        }
        return best;
    }

    private void spawnJaggedYellowBeam(ServerWorld sw, Vec3d from, Vec3d to, int segments, double jitter, int densityPerEdge) {
        var rand = sw.random;
        Vec3d dir = to.subtract(from);
        double len = dir.length();
        if (len < 1e-6) return;
        Vec3d n = dir.normalize();

        Vec3d up = Math.abs(n.y) > 0.9 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
        Vec3d u = n.crossProduct(up).normalize();
        Vec3d v = n.crossProduct(u).normalize();

        DustParticleEffect yellowCore = new DustParticleEffect(YELLOW, 1.0f);
        DustColorTransitionParticleEffect yellowGlow =
                new DustColorTransitionParticleEffect(YELLOW, YELLOW_WHITE, 1.0f);

        Vec3d prev = from;
        for (int i = 1; i <= segments; i++) {
            double t = i / (double) segments;
            Vec3d base = from.lerp(to, t);

            double sign = (i % 2 == 0) ? 1.0 : -1.0;
            double a = (rand.nextDouble() * jitter * 1.15) * sign;
            double b = (rand.nextDouble() * jitter * 1.15) * (rand.nextBoolean() ? 1.0 : -1.0);
            Vec3d node = base.add(u.multiply(a)).add(v.multiply(b));

            int steps = Math.max(1, (int) (prev.distanceTo(node) * 4));
            for (int s = 0; s <= steps; s++) {
                double ts = s / (double) steps;
                Vec3d p = prev.lerp(node, ts);

                // shorter-lived beam: higher spread (0.8) = 60% faster fade
                sw.spawnParticles(yellowCore, p.x, p.y, p.z,
                        densityPerEdge, 0.02, 0.02, 0.02, 0.8);

                if ((s & 1) == 0) {
                    sw.spawnParticles(yellowGlow, p.x, p.y, p.z,
                            Math.max(1, densityPerEdge / 2), 0.02, 0.02, 0.02, 0.8);
                }
            }

            sw.spawnParticles(ParticleTypes.ELECTRIC_SPARK, node.x, node.y, node.z,
                    3, 0.02, 0.02, 0.02, 0.05);

            prev = node;
        }
    }
}
