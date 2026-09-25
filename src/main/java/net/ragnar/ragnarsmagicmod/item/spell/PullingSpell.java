package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.List;

/**
 * Yanks every creature in front of you toward you. It reaches creatures inside a cone around the crosshair, plus
 * anything close to the aim line itself, so both a crowd ahead and a single mob you're pointing at get caught.
 * Walls block it.
 */
public class PullingSpell implements Spell {
    private static final double RANGE = 30.0;
    private static final double PULL_STRENGTH = 1.8;
    private static final double CONE_DEGREES = 30.0;     // half-angle around the crosshair
    private static final double LINE_RADIUS = 2.0;       // anything this close to the aim line counts too

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        ServerWorld sw = (ServerWorld) world;

        Vec3d eye = player.getEyePos();
        Vec3d look = player.getRotationVector().normalize();
        double minCos = Math.cos(Math.toRadians(CONE_DEGREES));

        List<LivingEntity> candidates = sw.getEntitiesByClass(LivingEntity.class, player.getBoundingBox().expand(RANGE),
                e -> e != player && e.isAlive() && !e.isSpectator());
        for (LivingEntity e : candidates) {
            Vec3d center = e.getBoundingBox().getCenter();
            Vec3d to = center.subtract(eye);
            double dist = to.length();
            if (dist > RANGE || dist < 1.0e-3) continue;
            double along = to.dotProduct(look);
            if (along <= 0) continue;                                  // behind you
            double offLine = to.subtract(look.multiply(along)).length();
            boolean inCone = along / dist >= minCos;
            if (!inCone && offLine > LINE_RADIUS + e.getWidth() * 0.5) continue;
            if (!canSee(sw, player, eye, e)) continue;

            // Same launch as before: straight at your eyes
            Vec3d velocity = eye.subtract(e.getPos()).normalize().multiply(PULL_STRENGTH);
            e.addVelocity(velocity.x, velocity.y, velocity.z);
            e.velocityModified = true;

            sw.spawnParticles(ParticleTypes.REVERSE_PORTAL, e.getX(), e.getBodyY(0.5), e.getZ(), 10, 0.3, 0.3, 0.3, 0.1);
            sw.spawnParticles(ParticleTypes.CLOUD, e.getX(), e.getY(), e.getZ(), 3, 0.2, 0.1, 0.2, 0.05);
        }

        // Air rushing in toward you from the area you pulled
        for (int i = 0; i < 14; i++) {
            double t = 4 + sw.random.nextDouble() * 10;
            Vec3d jitter = new Vec3d(sw.random.nextGaussian(), sw.random.nextGaussian(), sw.random.nextGaussian()).multiply(t * 0.2);
            Vec3d from = eye.add(look.multiply(t)).add(jitter);
            Vec3d in = eye.subtract(from).normalize();
            sw.spawnParticles(ParticleTypes.CLOUD, from.x, from.y, from.z, 0, in.x, in.y, in.z, 0.9);
        }

        sw.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_EVOKER_PREPARE_ATTACK, SoundCategory.PLAYERS, 0.8f, 1.5f);
        sw.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_FISHING_BOBBER_RETRIEVE, SoundCategory.PLAYERS, 1.0f, 0.8f);
        return true;
    }

    /** Clear line from the eye to the creature's eyes or middle. */
    private static boolean canSee(ServerWorld world, PlayerEntity player, Vec3d eye, LivingEntity e) {
        for (Vec3d point : new Vec3d[]{e.getEyePos(), e.getBoundingBox().getCenter()}) {
            HitResult hit = world.raycast(new RaycastContext(eye, point, RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE, player));
            if (hit.getType() == HitResult.Type.MISS) return true;
        }
        return false;
    }
}
