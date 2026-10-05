package net.ragnar.ragnarsmagicmod.shadowhands.client;

import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.shadowhands.ShadowHands;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

/**
 * One shadow hand: a long blocky black arm bursting up out of the pool on a writhing curve, and a hand at the end of
 * it whose fingers wrap around its prey - or, with no prey, clench and claw at the air. Drawn twice a frame, once as
 * black flesh and once as the purple aura around it.
 */
final class Hand {
    /** Ticks it takes to sink back into the ground once it lets go. */
    static final int SINK_TICKS = 14;
    private static final int ARM_SEGMENTS = 10;
    private static final float[][] FINGERS = {
            // height offset, phalanx lengths (all times the hand's scale)
            {0.15f, 0.24f, 0.19f, 0.15f},
            {0.05f, 0.27f, 0.22f, 0.17f},
            {-0.05f, 0.26f, 0.21f, 0.16f},
            {-0.15f, 0.21f, 0.17f, 0.13f}};
    private static final float FR = 0.05f, FG = 0.015f, FB = 0.08f;

    final Vec3d anchor;
    final double theta;
    final float scale, heightFrac;
    final long seed;
    final int victimId;
    final int born;
    final int releaseAt;
    /** Pulse times for a hand that's squeezing something, empty otherwise. */
    final int[] pulses;

    // Where the prey last was, so the hand still has something to close on if it dies or vanishes
    private Vec3d lastPos;
    private float lastRadius, lastHeight;

    Hand(Vec3d anchor, double theta, float scale, float heightFrac, long seed, int born, int releaseAt,
         @Nullable Entity victim, Vec3d airCenter, int[] pulses) {
        this.anchor = anchor;
        this.theta = theta;
        this.scale = scale;
        this.heightFrac = heightFrac;
        this.seed = seed;
        this.born = born;
        this.releaseAt = releaseAt;
        this.pulses = pulses;
        this.victimId = victim != null ? victim.getId() : -1;
        if (victim != null) {
            lastPos = victim.getPos();
            lastRadius = victim.getWidth() / 2f;
            lastHeight = victim.getHeight();
        } else {
            lastPos = airCenter;
            lastRadius = 0.08f;
            lastHeight = 0f;
        }
    }

    boolean done(float age) {
        return age > releaseAt + SINK_TICKS;
    }

    /** How strongly it's squeezing right now, 0..1: a sharp clench at each pulse. */
    private float squeeze(float age) {
        float best = 0f;
        for (int p : pulses) {
            float d = (age - p) / 2.5f;
            best = Math.max(best, (float) Math.exp(-d * d));
        }
        return best;
    }

    void draw(VertexConsumer vc, Matrix4f pose, ClientWorld world, Vec3d cam, float age, float tickDelta, boolean aura) {
        if (age < born) return;
        // Bursts up fast and overshoots a touch, then sinks back down at the end
        float rise = MathHelper.clamp((age - born) / ShadowHands.RISE_TICKS, 0f, 1f);
        float grow = 1f - (1f - rise) * (1f - rise) * (1f - rise);
        if (age > releaseAt) grow *= 1f - MathHelper.clamp((age - releaseAt) / SINK_TICKS, 0f, 1f);
        if (grow <= 0.01f) return;

        boolean dead = false;
        if (victimId >= 0) {
            Entity e = world.getEntityById(victimId);
            if (e != null && e.isAlive()) {
                lastPos = e.getLerpedPos(tickDelta);
                lastRadius = e.getWidth() / 2f;
                lastHeight = e.getHeight();
            } else {
                dead = true;
            }
        }
        float k = scale;
        float pulse = squeeze(age);
        float t = age + seed % 1000;

        // Fingers: open while it rises, slam shut as it arrives, clench harder at every squeeze
        float curl;
        if (victimId < 0) curl = 0.55f + 0.45f * MathHelper.sin(t * 0.13f);
        else curl = rise < 0.7f ? 0.05f : MathHelper.lerp((rise - 0.7f) / 0.3f, 0.05f, 1f);
        curl += 0.2f * pulse;
        if (dead) curl = 1.3f;
        if (age > releaseAt) curl = MathHelper.lerp(MathHelper.clamp((age - releaseAt) / 6f, 0f, 1f), curl, 0.1f);

        float bodyR = dead ? 0.06f : lastRadius;
        double rho = bodyR * (1 - 0.12 * pulse) + 0.04;
        Vec3d out = new Vec3d(Math.cos(theta), 0, Math.sin(theta));
        double gripY = lastPos.y + lastHeight * heightFrac;
        Vec3d axis = new Vec3d(lastPos.x, gripY, lastPos.z);
        Vec3d palm = axis.add(out.multiply(rho + 0.05 * k));
        Vec3d wrist = palm.add(out.multiply(0.2 * k));
        // Sinking pulls the whole hand down into the ground with the arm
        double sink = (1 - grow) * (gripY - anchor.y + 1.0);
        Vec3d down = new Vec3d(0, -sink, 0);

        // The arm: a writhing curve up out of the pool and over onto the prey
        Vec3d p0 = anchor.add(0, -0.5, 0);
        Vec3d p1 = anchor.add(0, 1.0 + 0.5 * k, 0);
        Vec3d p2 = wrist.add(out.multiply(0.9 * k)).add(0, 0.5 * k, 0);
        Vec3d tangent = new Vec3d(-Math.sin(theta), 0, Math.cos(theta));
        Vec3d prev = null;
        float span = age > releaseAt ? 1f : grow;
        // A surge racing up the arm from the ground, reaching the hand just as it squeezes; -1 for none
        float travel = -1f;
        for (int p : pulses) {
            if (p >= age && p - age <= 6f) {
                travel = 1f - (p - age) / 6f;
                break;
            }
        }
        for (int i = 0; i <= ARM_SEGMENTS; i++) {
            float s = span * i / ARM_SEGMENTS;
            Vec3d pt = bezier(p0, p1, p2, wrist, s);
            double bend = Math.sin(Math.PI * s);
            pt = pt.add(tangent.multiply((Math.sin(t * 0.21 + i * 0.9) * 0.14 + Math.sin(t * 3.1 + i) * 0.04 * pulse) * k * bend))
                    .add(0, Math.cos(t * 0.17 + i * 1.1) * 0.07 * k * bend, 0);
            if (age > releaseAt) pt = pt.add(down);
            else if (grow < 1f) pt = pt.add(0, -(1 - grow) * 0.6, 0);
            if (prev != null) {
                float w = (0.27f - 0.12f * s) * k;
                // A surge of purple racing up the arm at every squeeze
                float surge = travel >= 0 ? Math.max(0f, 1f - Math.abs(s - travel) * 5f) : 0f;
                limb(vc, pose, prev.subtract(cam), pt.subtract(cam), w, w * 0.85f, out, aura, 0.07f * k, surge);
            }
            prev = pt;
        }
        Vec3d tip = prev;
        Vec3d shift = tip.subtract(wrist); // while it's still rising, the hand rides the tip of the arm

        // The hand
        Vec3d pc = palm.add(shift);
        limb(vc, pose, tip.subtract(cam), pc.subtract(cam), 0.17f * k, 0.13f * k, out, aura, 0.04f * k, 0f);
        limb(vc, pose, pc.add(0, -0.2 * k, 0).subtract(cam), pc.add(0, 0.2 * k, 0).subtract(cam), 0.42f * k, 0.12f * k, out, aura, 0f, 0f);
        double rf = rho + 0.06 * k;
        for (float[] f : FINGERS) finger(vc, pose, cam, axis.add(shift), gripY + f[0] * k + shift.y, rf, 1, f, curl, k, aura);
        finger(vc, pose, cam, axis.add(shift), gripY - 0.1 * k + shift.y, rf, -1, new float[]{0, 0.22f, 0.18f}, curl, k, aura);
    }

    /**
     * A finger wrapping around the axis {@code axis} at height {@code y}: each joint further round, and splayed out
     * from the body while the hand is open. Ends in a thin claw.
     */
    private void finger(VertexConsumer vc, Matrix4f pose, Vec3d cam, Vec3d axis, double y, double radius, int sign,
                        float[] f, float curl, float k, boolean aura) {
        double phi = theta + sign * (0.19 * k) / radius;
        double cum = 0;
        Vec3d prev = ring(axis, y, phi, radius);
        Vec3d prevDir = null;
        for (int j = 1; j < f.length; j++) {
            cum += f[j] * k;
            double wrap = Math.min(2.6, cum / radius * MathHelper.clamp(curl, 0f, 1.35f));
            double radial = radius + (1 - MathHelper.clamp(curl, 0f, 1f)) * cum * 0.9;
            Vec3d pt = ring(axis, y, phi + sign * wrap, radial);
            float w = (j == f.length - 1 ? 0.075f : 0.095f) * k;
            limb(vc, pose, prev.subtract(cam), pt.subtract(cam), w, w, new Vec3d(0, 1, 0), aura, 0.02f * k, 0f);
            prevDir = pt.subtract(prev);
            prev = pt;
        }
        if (prevDir != null && prevDir.lengthSquared() > 1e-6) {
            Vec3d claw = prev.add(prevDir.normalize().multiply(0.1 * k));
            limb(vc, pose, prev.subtract(cam), claw.subtract(cam), 0.035f * k, 0.035f * k, new Vec3d(0, 1, 0), aura, 0f, 0f);
        }
    }

    private static Vec3d ring(Vec3d axis, double y, double angle, double radius) {
        return new Vec3d(axis.x + Math.cos(angle) * radius, y, axis.z + Math.sin(angle) * radius);
    }

    /** One piece of limb: black flesh, or the aura around it (thicker, faint, brighter where a surge is passing). */
    private static void limb(VertexConsumer vc, Matrix4f pose, Vec3d a, Vec3d b, float w, float h, Vec3d up,
                             boolean aura, float overlap, float surge) {
        if (!aura) {
            Shapes.bar(vc, pose, a, b, w, h, up, FR, FG, FB, 1f, overlap);
        } else {
            Shapes.bar(vc, pose, a, b, w * 1.45f + 0.04f, h * 1.45f + 0.04f, up, 0.3f, 0.02f, 0.5f, 0.07f + 0.25f * surge, overlap);
            if (surge > 0.05f) Shapes.bar(vc, pose, a, b, w * 1.15f, h * 1.15f, up, 0.85f, 0.25f, 1f, 0.45f * surge, overlap);
        }
    }

    private static Vec3d bezier(Vec3d a, Vec3d b, Vec3d c, Vec3d d, double s) {
        double u = 1 - s;
        return a.multiply(u * u * u).add(b.multiply(3 * u * u * s)).add(c.multiply(3 * u * s * s)).add(d.multiply(s * s * s));
    }
}
