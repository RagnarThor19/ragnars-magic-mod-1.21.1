package net.ragnar.ragnarsmagicmod.thunderstrike.client;

import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The shape of one Thunderstrike bolt, worked out the same on every client from the strike's seed: a thick jagged
 * trunk from the cloud's underside down to the struck ground, with branches forking off it, some with twigs of their
 * own. The stepped leaders of the warning creep down this very trunk, so the strike follows the path you watched form.
 */
final class BoltShape {
    /** One jagged line of the bolt, how thick it is next to the trunk, and where along the trunk it forks off (0 top, 1 ground). */
    record Line(Vec3d[] pts, float width, double forkAt) {}

    final Vec3d[] trunk;
    /** Distance along the trunk at each of its points, for drawing it part way down. */
    final double[] along;
    final double length;
    final List<Line> branches = new ArrayList<>();

    private BoltShape(Vec3d[] trunk) {
        this.trunk = trunk;
        this.along = new double[trunk.length];
        double l = 0;
        for (int i = 1; i < trunk.length; i++) {
            l += trunk[i].distanceTo(trunk[i - 1]);
            along[i] = l;
        }
        this.length = l;
    }

    static BoltShape make(Vec3d ground, double cloudY, long seed) {
        Random r = new Random(seed);
        double h = Math.max(4, cloudY - ground.y);
        Vec3d top = new Vec3d(ground.x + r.nextGaussian() * 2.5, cloudY, ground.z + r.nextGaussian() * 2.5);
        int n = Math.max(10, (int) (h / 1.6));
        Vec3d[] pts = new Vec3d[n + 1];
        double jx = 0, jz = 0;
        for (int i = 0; i <= n; i++) {
            double t = i / (double) n;
            // A wandering kink at every step, pulled back in toward the end so it lands right on the spot
            jx = jx * 0.82 + r.nextGaussian() * 1.15;
            jz = jz * 0.82 + r.nextGaussian() * 1.15;
            double settle = 1 - t * t * t;
            double y = cloudY - h * t + (i > 0 && i < n ? (r.nextDouble() - 0.5) * 0.9 : 0);
            pts[i] = new Vec3d(top.x + (ground.x - top.x) * t + jx * settle, y, top.z + (ground.z - top.z) * t + jz * settle);
        }
        pts[n] = ground;
        BoltShape b = new BoltShape(pts);

        // Branches: from the upper three-quarters of the trunk, reaching out and down
        int count = 6 + r.nextInt(5);
        for (int k = 0; k < count; k++) {
            int at = 1 + r.nextInt(Math.max(1, (int) (n * 0.75)));
            double fork = at / (double) n;
            List<Vec3d> line = new ArrayList<>();
            Vec3d p = pts[at];
            line.add(p);
            double ang = r.nextDouble() * Math.PI * 2;
            int steps = 3 + r.nextInt(7);
            for (int s = 0; s < steps; s++) {
                ang += (r.nextDouble() - 0.5) * 1.0;
                double out = 1.0 + r.nextDouble() * 0.9;
                p = p.add(Math.cos(ang) * out, -(0.7 + r.nextDouble() * 1.3), Math.sin(ang) * out);
                if (p.y < ground.y + 1) break;
                line.add(p);
            }
            if (line.size() < 2) continue;
            Vec3d[] arr = line.toArray(new Vec3d[0]);
            float w = (float) (0.46 - fork * 0.16);
            b.branches.add(new Line(arr, w, fork));
            // ...and now and then a twig off the branch
            if (arr.length > 2 && r.nextFloat() < 0.6f) {
                Vec3d q = arr[1 + r.nextInt(arr.length - 2)];
                List<Vec3d> twig = new ArrayList<>();
                twig.add(q);
                double ta = ang + (r.nextBoolean() ? 1 : -1) * (0.6 + r.nextDouble());
                for (int s = 0; s < 2 + r.nextInt(3); s++) {
                    q = q.add(Math.cos(ta) * 0.9, -(0.5 + r.nextDouble() * 0.9), Math.sin(ta) * 0.9);
                    ta += (r.nextDouble() - 0.5) * 0.8;
                    twig.add(q);
                }
                b.branches.add(new Line(twig.toArray(new Vec3d[0]), w * 0.5f, fork + 0.05));
            }
        }
        return b;
    }

    /** The trunk's point {@code distance} down from the top (for the leader's tip). */
    int indexAt(double distance) {
        for (int i = 1; i < along.length; i++) if (along[i] >= distance) return i;
        return along.length - 1;
    }
}
