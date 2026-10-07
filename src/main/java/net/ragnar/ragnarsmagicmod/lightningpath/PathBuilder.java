package net.ragnar.ragnarsmagicmod.lightningpath;

import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * The path a caster paints with their crosshair, a point at a time. It's forgiving by design:
 * <ul>
 *   <li>points closer than {@link #SPACING} to the last one are ignored, so a steady gaze doesn't pile them up;</li>
 *   <li>if the crosshair comes back near an earlier point, the loop in between is cut out - so whirling the camera
 *       round in circles just keeps the path short instead of tying it in knots;</li>
 *   <li>it stops growing at {@link #MAX_LENGTH} blocks (the last stretch is cut to fit).</li>
 * </ul>
 * {@link #route} then smooths the corners off and spaces it out evenly for running along. Pure maths - shared by the
 * server and every client, so they all agree on the exact same route.
 */
public final class PathBuilder {
    public static final double SPACING = 1.0;
    public static final double LOOP_RADIUS = 1.8;
    public static final double MAX_LENGTH = 150;

    private final List<Vec3d> points = new ArrayList<>();
    private double length;

    public PathBuilder(Vec3d start) {
        points.add(start);
    }

    public List<Vec3d> points() {
        return points;
    }

    public double length() {
        return length;
    }

    public boolean full() {
        return length >= MAX_LENGTH - 1e-6;
    }

    /** Adds the point the crosshair is on now. True if the path changed. */
    public boolean add(Vec3d p) {
        Vec3d last = points.get(points.size() - 1);
        if (p.distanceTo(last) < SPACING) return false;
        // Back near an earlier point: cut the loop out
        for (int i = 0; i < points.size() - 2; i++) {
            Vec3d q = points.get(i);
            double horizontal = Math.sqrt(MathHelper.square(q.x - p.x) + MathHelper.square(q.z - p.z));
            if (horizontal < LOOP_RADIUS && Math.abs(q.y - p.y) < 2.5) {
                points.subList(i + 1, points.size()).clear();
                length = measure(points);
                return true;
            }
        }
        if (full()) return false;
        double step = p.distanceTo(last);
        if (length + step > MAX_LENGTH) {
            p = last.add(p.subtract(last).normalize().multiply(MAX_LENGTH - length));
            step = MAX_LENGTH - length;
        }
        points.add(p);
        length += step;
        return true;
    }

    /** Drops the last point (e.g. one that would leave you standing in lava). */
    public void removeLast() {
        if (points.size() > 1) {
            points.remove(points.size() - 1);
            length = measure(points);
        }
    }

    public static double measure(List<Vec3d> pts) {
        double l = 0;
        for (int i = 1; i < pts.size(); i++) l += pts.get(i).distanceTo(pts.get(i - 1));
        return l;
    }

    // ---------------------------------------------------------------------
    // The route actually run
    // ---------------------------------------------------------------------

    /** A smoothed, evenly spaced path to run along, with the distance to each point worked out. */
    public static final class Route {
        public final List<Vec3d> points;
        private final double[] at;

        Route(List<Vec3d> points) {
            this.points = points;
            at = new double[points.size()];
            for (int i = 1; i < points.size(); i++) at[i] = at[i - 1] + points.get(i).distanceTo(points.get(i - 1));
        }

        public double length() {
            return at.length == 0 ? 0 : at[at.length - 1];
        }

        /** The point {@code s} blocks along. */
        public Vec3d pointAt(double s) {
            if (points.size() == 1 || s <= 0) return points.get(0);
            if (s >= length()) return points.get(points.size() - 1);
            int lo = 0, hi = at.length - 1;
            while (hi - lo > 1) {
                int mid = (lo + hi) >>> 1;
                if (at[mid] <= s) lo = mid;
                else hi = mid;
            }
            double span = at[hi] - at[lo];
            return points.get(lo).lerp(points.get(hi), span < 1e-6 ? 0 : (s - at[lo]) / span);
        }

        /** Which way the route runs {@code s} blocks along. */
        public Vec3d direction(double s) {
            Vec3d d = pointAt(s + 1.5).subtract(pointAt(s - 0.5));
            return d.lengthSquared() < 1e-6 ? new Vec3d(0, 0, 1) : d.normalize();
        }
    }

    /** Rounds the corners off a painted path (twice over) and spaces it out every half block. */
    public static Route route(List<Vec3d> painted) {
        List<Vec3d> pts = new ArrayList<>(painted);
        for (int pass = 0; pass < 2 && pts.size() > 2; pass++) {
            List<Vec3d> smooth = new ArrayList<>();
            smooth.add(pts.get(0));
            for (int i = 0; i < pts.size() - 1; i++) {
                Vec3d a = pts.get(i), b = pts.get(i + 1);
                smooth.add(a.lerp(b, 0.25));
                smooth.add(a.lerp(b, 0.75));
            }
            smooth.add(pts.get(pts.size() - 1));
            pts = smooth;
        }
        Route rough = new Route(pts);
        List<Vec3d> even = new ArrayList<>();
        double len = rough.length();
        for (double s = 0; s < len; s += 0.5) even.add(rough.pointAt(s));
        even.add(rough.pointAt(len));
        return new Route(even);
    }
}
