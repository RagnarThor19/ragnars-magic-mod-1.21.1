package net.ragnar.ragnarsmagicmod.boulders;

import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * How a round lump of stone is turned, kept on the client for drawing. Each tick it turns as if it had rolled along
 * the way it just moved, so it tumbles end over end in the air and rolls true along the ground. Shared by the Tome of
 * Rocks' rock and the Tome of Boulders' boulder.
 */
public final class Tumble {
    private final Quaternionf prev = new Quaternionf();
    private final Quaternionf now = new Quaternionf();
    private final Vector3f axis = new Vector3f(1, 0, 0);

    /** Starts at a turn picked from {@code seed}, so no two rocks look alike. */
    public Tumble(long seed) {
        now.rotationXYZ(seed * 1.7f, seed * 2.9f, seed * 0.6f);
        prev.set(now);
    }

    /**
     * Turns it for having moved {@code moved} this tick, {@code radius} being how big it is. {@code rate} scales the
     * spin (1 = rolling without slipping) and {@code maxAngle} caps it per tick so a fast little rock doesn't strobe.
     */
    public void roll(Vec3d moved, double radius, float rate, float maxAngle) {
        prev.set(now);
        double dist = moved.length();
        if (dist < 1e-3) return;
        // Turning about "up x moved" rolls it forwards; a straight drop keeps the last axis so it doesn't snap round
        Vector3f a = new Vector3f((float) moved.z, 0f, (float) -moved.x);
        if (a.lengthSquared() > 1e-6f) axis.set(a.normalize());
        float angle = MathHelper.clamp((float) (dist / radius) * rate, 0f, maxAngle);
        now.premul(new Quaternionf().rotationAxis(angle, axis)).normalize();
    }

    /** Spins it on the spot about {@code axisX, axisY, axisZ}. */
    public void spin(float angle, float axisX, float axisY, float axisZ) {
        prev.set(now);
        now.premul(new Quaternionf().rotationAxis(angle, axisX, axisY, axisZ)).normalize();
    }

    /** Its turn part way through this tick, for drawing. */
    public Quaternionf get(float tickDelta) {
        return new Quaternionf(prev).slerp(now, tickDelta);
    }
}
