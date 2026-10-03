package net.ragnar.ragnarsmagicmod.jaunting.client;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import org.joml.Matrix4f;

/**
 * Little jagged lightning bolts, drawn like the vanilla bolt: straight, glowing segments added on top of the world,
 * each one two crossed strips so it looks solid from any side.
 */
final class Bolts {
    private Bolts() {}

    static final RenderLayer LAYER = RenderLayer.of("ragnarsmagicmod_jaunting_bolts",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 8192, false, true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.LIGHTNING_TRANSPARENCY)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    /** A bolt from {@code a} to {@code b} (in {@code pose}'s space): a white-blue core in a soft blue glow. */
    static void bolt(VertexConsumer vc, Matrix4f pose, Vec3d a, Vec3d b, Random r, int segments, double jitter,
                     float width, float alpha, int branches) {
        if (alpha <= 0.004f) return;
        Vec3d span = b.subtract(a);
        double len = span.length();
        if (len < 1e-4) return;
        Vec3d dir = span.multiply(1.0 / len);
        Vec3d p1 = perpendicular(dir), p2 = dir.crossProduct(p1);

        Vec3d[] pts = new Vec3d[segments + 1];
        for (int i = 0; i <= segments; i++) {
            double t = i / (double) segments;
            // Pinned at both ends, wildest in the middle
            double j = (i == 0 || i == segments) ? 0 : jitter * (0.4 + 0.6 * Math.sin(Math.PI * t));
            pts[i] = a.add(span.multiply(t)).add(p1.multiply(r.nextGaussian() * j)).add(p2.multiply(r.nextGaussian() * j));
        }
        for (int i = 0; i < segments; i++) {
            strip(vc, pose, pts[i], pts[i + 1], width * 3.2f, 0.25f, 0.45f, 1.0f, alpha * 0.35f);
            strip(vc, pose, pts[i], pts[i + 1], width, 0.82f, 0.93f, 1.0f, alpha);
        }
        for (int k = 0; k < branches; k++) {
            int from = 1 + r.nextInt(Math.max(1, segments - 1));
            Vec3d start = pts[Math.min(from, segments)];
            Vec3d off = dir.add(p1.multiply(r.nextGaussian() * 0.8)).add(p2.multiply(r.nextGaussian() * 0.8)).normalize();
            Vec3d end = start.add(off.multiply(len * (0.15 + r.nextDouble() * 0.2)));
            bolt(vc, pose, start, end, r, Math.max(2, segments / 3), jitter * 0.5, width * 0.6f, alpha * 0.7f, 0);
        }
    }

    private static Vec3d perpendicular(Vec3d d) {
        Vec3d p = Math.abs(d.y) < 0.9 ? d.crossProduct(new Vec3d(0, 1, 0)) : d.crossProduct(new Vec3d(1, 0, 0));
        return p.normalize();
    }

    /** One straight piece of bolt: two crossed strips {@code width} across. */
    private static void strip(VertexConsumer vc, Matrix4f pose, Vec3d from, Vec3d to, float width,
                              float r, float g, float b, float a) {
        Vec3d d = to.subtract(from);
        if (d.lengthSquared() < 1e-8) return;
        Vec3d n = d.normalize();
        Vec3d s1 = perpendicular(n).multiply(width / 2);
        Vec3d s2 = n.crossProduct(s1);
        quad(vc, pose, from, to, s1, r, g, b, a);
        quad(vc, pose, from, to, s2, r, g, b, a);
    }

    private static void quad(VertexConsumer vc, Matrix4f pose, Vec3d from, Vec3d to, Vec3d side,
                             float r, float g, float b, float a) {
        v(vc, pose, from.subtract(side), r, g, b, a);
        v(vc, pose, to.subtract(side), r, g, b, a);
        v(vc, pose, to.add(side), r, g, b, a);
        v(vc, pose, from.add(side), r, g, b, a);
    }

    private static void v(VertexConsumer vc, Matrix4f pose, Vec3d p, float r, float g, float b, float a) {
        vc.vertex(pose, (float) p.x, (float) p.y, (float) p.z).color(r, g, b, MathHelper.clamp(a, 0f, 1f));
    }
}
