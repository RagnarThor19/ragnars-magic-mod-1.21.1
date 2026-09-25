package net.ragnar.ragnarsmagicmod.util;

import net.minecraft.entity.Entity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * The flight of a Tome of TNT throw, stepped the way a lit TNT block moves (gravity 0.04, drag 0.98), so the
 * aiming arc drawn on the client and the throw on the server agree.
 */
public final class TntArc {
    private TntArc() {}

    public static final float MIN_POWER = 0.4f;
    public static final float MAX_POWER = 2.2f;
    public static final float DEFAULT_POWER = 1.0f;
    public static final float POWER_STEP = 0.1f;
    public static final int FUSE_AFTER_LANDING = 20;
    private static final int MAX_TICKS = 120;

    /** The path (one point per tick), where it first hits something (or null), and how long that takes. */
    public record Flight(List<Vec3d> points, Vec3d landing, int ticks) {}

    public static float clampPower(float power) {
        return MathHelper.clamp(Math.round(power / POWER_STEP) * POWER_STEP, MIN_POWER, MAX_POWER);
    }

    /** Where the TNT leaves the hand. */
    public static Vec3d start(Entity thrower) {
        return thrower.getEyePos().add(thrower.getRotationVector().multiply(0.6)).add(0, -0.35, 0);
    }

    public static Vec3d velocity(Entity thrower, float power) {
        return thrower.getRotationVector().multiply(power).add(0, 0.1, 0);
    }

    public static Flight simulate(World world, Entity thrower, float power) {
        Vec3d pos = start(thrower);
        Vec3d vel = velocity(thrower, power);
        List<Vec3d> points = new ArrayList<>();
        points.add(pos);
        for (int t = 1; t <= MAX_TICKS; t++) {
            vel = vel.add(0, -0.04, 0);
            Vec3d next = pos.add(vel);
            BlockHitResult hit = world.raycast(new RaycastContext(pos, next, RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE, thrower));
            if (hit.getType() == HitResult.Type.BLOCK) {
                points.add(hit.getPos());
                return new Flight(points, hit.getPos(), t);
            }
            pos = next;
            points.add(pos);
            vel = vel.multiply(0.98);
        }
        return new Flight(points, null, MAX_TICKS);
    }

    /** Lit so it goes off a moment after it lands. */
    public static int fuseFor(Flight flight) {
        return MathHelper.clamp(flight.ticks() + FUSE_AFTER_LANDING, 20, 100);
    }
}
