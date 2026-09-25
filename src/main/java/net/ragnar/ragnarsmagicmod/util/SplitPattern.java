package net.ragnar.ragnarsmagicmod.util;

import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

import java.util.ArrayList;
import java.util.List;

/**
 * The slashes of a Tome of Dimension Split, and its timeline. Built from a seed on both sides, so the
 * server cuts exactly where the clients draw.
 */
public final class SplitPattern {
    private SplitPattern() {}

    // --- timeline, in ticks after impact ---
    public static final int SWEEP_TICKS = 2;   // how long one slash takes to cut across
    public static final int SEVER = 14;        // everything lands at once
    public static final int RIFT_OPEN = 4;     // the tear opening after the sever
    public static final int RIFT_CLOSE = 44;   // when the tear starts sealing
    public static final int END = 48;

    /**
     * One crescent slash, lying in the plane spanned by {@code u} (the cut direction) and {@code v}.
     * {@code t} runs 0..1 along the cut.
     */
    public record Slash(Vec3d center, Vec3d u, Vec3d v, double length, double halfWidth, double bend, int start) {
        public Vec3d point(double t) {
            double along = (t - 0.5) * length;
            double curve = bend * (Math.sin(Math.PI * t) - 0.6);
            return center.add(u.multiply(along)).add(v.multiply(curve));
        }

        /** Crescent thickness: sharp at both tips, fattest in the middle. */
        public double halfWidthAt(double t) {
            return halfWidth * Math.pow(Math.max(0, Math.sin(Math.PI * t)), 0.7);
        }

        public int landsAt() {
            return start + SWEEP_TICKS;
        }
    }

    /**
     * @param view unit vector from the impact toward whoever cast it: the big slashes face them
     */
    public static List<Slash> generate(Vec3d center, Vec3d view, long seed) {
        Random rand = Random.create(seed);
        List<Slash> slashes = new ArrayList<>();

        Vec3d n = view.lengthSquared() < 1.0e-6 ? new Vec3d(0, 0, 1) : view.normalize();
        double theta = Math.toRadians((rand.nextBoolean() ? 1 : -1) * (25 + rand.nextDouble() * 30));

        // The two great cuts, crossing in an X
        slashes.add(make(center, n, theta, 18.0, 0.55, 2.2, 0));
        slashes.add(make(center, n, theta + Math.toRadians(90 + (rand.nextDouble() - 0.5) * 30), 15.0, 0.45, -1.8, 3));

        // A flurry of smaller cuts at every angle through the area
        for (int i = 0; i < 6; i++) {
            Vec3d offset = new Vec3d(rand.nextGaussian(), rand.nextGaussian() * 0.6, rand.nextGaussian()).multiply(1.3);
            Vec3d tilt = new Vec3d(rand.nextGaussian(), rand.nextGaussian(), rand.nextGaussian()).multiply(0.7);
            Vec3d ni = n.add(tilt).normalize();
            double ti = rand.nextDouble() * Math.PI;
            double len = 7.0 + rand.nextDouble() * 5.0;
            double hw = 0.25 + rand.nextDouble() * 0.15;
            double bend = (rand.nextBoolean() ? 1 : -1) * (0.8 + rand.nextDouble() * 0.8);
            slashes.add(make(center.add(offset), ni, ti, len, hw, bend, 5 + i));
        }
        return slashes;
    }

    private static Slash make(Vec3d center, Vec3d n, double theta, double length, double halfWidth, double bend, int start) {
        Vec3d up = Math.abs(n.y) > 0.95 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
        Vec3d a = n.crossProduct(up).normalize();
        Vec3d b = n.crossProduct(a).normalize();
        Vec3d u = a.multiply(Math.cos(theta)).add(b.multiply(Math.sin(theta))).normalize();
        Vec3d v = n.crossProduct(u).normalize();
        return new Slash(center, u, v, length, halfWidth, bend, start);
    }

    /** Distance from {@code p} to the slash's centre line, less its thickness there (0 or less = inside). */
    public static double distanceOutside(Slash s, Vec3d p) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i <= 24; i++) {
            double t = i / 24.0;
            double d = s.point(t).distanceTo(p) - s.halfWidthAt(t);
            if (d < best) best = d;
        }
        return best;
    }

    /** 0..1 how open the rift is at {@code age} ticks after impact. */
    public static float riftOpen(float age) {
        if (age < SEVER) return 0f;
        if (age < SEVER + RIFT_OPEN) return (age - SEVER) / RIFT_OPEN;
        if (age < RIFT_CLOSE) return 1f;
        return MathHelper.clamp(1f - (age - RIFT_CLOSE) / (END - RIFT_CLOSE), 0f, 1f);
    }
}
