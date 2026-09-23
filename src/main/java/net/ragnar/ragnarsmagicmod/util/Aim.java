package net.ragnar.ragnarsmagicmod.util;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.function.Predicate;

/** Picking the entity a player is aiming at. */
public final class Aim {
    private Aim() {}

    /**
     * The entity under the crosshair within {@code range}, or failing that the visible one closest to the
     * crosshair inside a {@code coneDegrees} cone (a little aim assist). Walls block both.
     */
    public static Entity target(ServerWorld world, PlayerEntity player, double range, double coneDegrees, Predicate<Entity> filter) {
        Vec3d eye = player.getEyePos();
        Vec3d look = player.getRotationVector().normalize();
        Predicate<Entity> valid = e -> e != player && e.isAlive() && !e.isSpectator() && filter.test(e);

        HitResult block = world.raycast(new RaycastContext(eye, eye.add(look.multiply(range)),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
        Vec3d end = block.getType() == HitResult.Type.MISS ? eye.add(look.multiply(range)) : block.getPos();
        EntityHitResult direct = ProjectileUtil.raycast(player, eye, end, new Box(eye, end).expand(1.0), valid, eye.squaredDistanceTo(end));
        if (direct != null) return direct.getEntity();

        double minCos = Math.cos(Math.toRadians(coneDegrees));
        Box area = new Box(eye, eye.add(look.multiply(range))).expand(range * Math.tan(Math.toRadians(coneDegrees)) + 1.0);
        Entity best = null;
        double bestCos = minCos;
        for (Entity e : world.getOtherEntities(player, area, valid)) {
            Vec3d to = e.getBoundingBox().getCenter().subtract(eye);
            double dist = to.length();
            if (dist > range || dist < 1.0e-3) continue;
            double cos = to.multiply(1.0 / dist).dotProduct(look);
            if (cos < bestCos) continue;
            HitResult los = world.raycast(new RaycastContext(eye, e.getBoundingBox().getCenter(),
                    RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
            if (los.getType() != HitResult.Type.MISS) continue;
            bestCos = cos;
            best = e;
        }
        return best;
    }
}
