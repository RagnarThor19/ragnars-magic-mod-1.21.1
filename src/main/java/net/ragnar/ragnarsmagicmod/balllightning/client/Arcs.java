package net.ragnar.ragnarsmagicmod.balllightning.client;

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
 * Drawing for the Tome of Ball Lightning, in the Tome of Zap's look: glowing flat ribbons on top of the world, a
 * white-hot core in a yellow sheath and a soft amber glow. All points are relative to the camera, so each ribbon can
 * turn to face it.
 */
final class Arcs {
    private Arcs() {}

    static final RenderLayer LAYER = RenderLayer.of("ragnarsmagicmod_ball_lightning",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 16384, false, true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.LIGHTNING_TRANSPARENCY)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    private static final float CORE_W = 0.035f, MID_W = 0.075f, GLOW_W = 0.15f;

    /** A jagged path from {@code a} to {@code b}: {@code n} straight runs kicking from side to side. */
    static Vec3d[] path(Vec3d a, Vec3d b, Random r, int n, double amp) {
        Vec3d span = b.subtract(a);
        Vec3d dir = span.lengthSquared() > 1e-10 ? span.normalize() : new Vec3d(0, 1, 0);
        Vec3d u = perpendicular(dir), v = dir.crossProduct(u);
        Vec3d[] pts = new Vec3d[n + 1];
        pts[0] = a;
        pts[n] = b;
        for (int i = 1; i < n; i++) {
            double t = (i + (r.nextDouble() - 0.5) * 0.4) / n;
            double ang = r.nextDouble() * Math.PI * 2;
            // Pinned at both ends, wildest in the middle
            double k = amp * Math.sin(Math.PI * t) * (0.5 + r.nextDouble() * 0.7) * (i % 2 == 0 ? 1 : -1);
            pts[i] = a.add(span.multiply(t)).add(u.multiply(Math.cos(ang) * k)).add(v.multiply(Math.sin(ang) * k));
        }
        return pts;
    }

    /** A whole jagged bolt from {@code a} to {@code b}, with the odd little fork off it. */
    static void bolt(VertexConsumer vc, Matrix4f pose, Vec3d a, Vec3d b, Random r, float width, float alpha, boolean forks) {
        double len = a.distanceTo(b);
        if (len < 1e-4) return;
        int n = MathHelper.clamp((int) Math.round(len / 0.5), 2, 48);
        Vec3d[] pts = path(a, b, r, n, Math.min(0.5, 0.08 + len * 0.06));
        for (int i = 0; i < n; i++) segment(vc, pose, pts[i], pts[i + 1], width, alpha);
        if (!forks) return;
        int count = r.nextInt(3);
        for (int k = 0; k < count && n > 2; k++) {
            Vec3d base = pts[1 + r.nextInt(n - 1)];
            Vec3d out = new Vec3d(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).normalize().multiply(0.3 + r.nextDouble() * len * 0.25);
            Vec3d[] twig = path(base, base.add(out), r, 3, 0.08);
            for (int i = 0; i < twig.length - 1; i++) segment(vc, pose, twig[i], twig[i + 1], width * 0.55f, alpha * 0.75f);
        }
    }

    /** One straight piece of bolt: glow, sheath and core. */
    static void segment(VertexConsumer vc, Matrix4f pose, Vec3d a, Vec3d b, float width, float alpha) {
        ribbon(vc, pose, a, b, GLOW_W * width, 1f, 0.75f, 0.2f, alpha * 0.2f);
        ribbon(vc, pose, a, b, MID_W * width, 1f, 0.9f, 0.4f, alpha * 0.6f);
        ribbon(vc, pose, a, b, CORE_W * width, 1f, 1f, 0.92f, alpha);
    }

    /** A strip from {@code a} to {@code b}, a touch longer so the joints close up, turned to face the camera. */
    static void ribbon(VertexConsumer vc, Matrix4f pose, Vec3d a, Vec3d b, float width, float r, float g, float bl, float alpha) {
        Vec3d d = b.subtract(a);
        double l = d.length();
        if (l < 1e-5 || alpha <= 0.004f) return;
        Vec3d n = d.multiply(1.0 / l);
        Vec3d mid = a.add(b).multiply(0.5);
        float w = width * (float) MathHelper.clamp(mid.length() / 2.5, 0.4, 1.0); // thinner right up close
        Vec3d ext = n.multiply(w * 0.5);
        a = a.subtract(ext);
        b = b.add(ext);
        Vec3d side = n.crossProduct(mid);
        side = side.lengthSquared() < 1e-10 ? perpendicular(n) : side.normalize();
        side = side.multiply(w * 0.5);
        v(vc, pose, a.subtract(side), r, g, bl, alpha);
        v(vc, pose, b.subtract(side), r, g, bl, alpha);
        v(vc, pose, b.add(side), r, g, bl, alpha);
        v(vc, pose, a.add(side), r, g, bl, alpha);
    }

    /** A soft round-ish blob of light facing the camera: a few stacked squares, turned, fading out. */
    static void glow(VertexConsumer vc, Matrix4f pose, Vec3d c, Vec3d right, Vec3d up, float size,
                     float r, float g, float b, float alpha) {
        for (int layer = 0; layer < 3; layer++) {
            float s = size * (1f - layer * 0.28f);
            double turn = layer * Math.PI / 6;
            Vec3d x = right.multiply(Math.cos(turn) * s).add(up.multiply(Math.sin(turn) * s));
            Vec3d y = up.multiply(Math.cos(turn) * s).subtract(right.multiply(Math.sin(turn) * s));
            float a = alpha * (0.35f + layer * 0.3f);
            v(vc, pose, c.subtract(x).subtract(y), r, g, b, a);
            v(vc, pose, c.add(x).subtract(y), r, g, b, a);
            v(vc, pose, c.add(x).add(y), r, g, b, a);
            v(vc, pose, c.subtract(x).add(y), r, g, b, a);
        }
    }

    static Vec3d perpendicular(Vec3d d) {
        Vec3d p = Math.abs(d.y) < 0.9 ? d.crossProduct(new Vec3d(0, 1, 0)) : d.crossProduct(new Vec3d(1, 0, 0));
        return p.normalize();
    }

    static Vec3d randomDir(Random r) {
        return new Vec3d(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).normalize();
    }

    private static void v(VertexConsumer vc, Matrix4f pose, Vec3d p, float r, float g, float b, float a) {
        vc.vertex(pose, (float) p.x, (float) p.y, (float) p.z).color(r, g, b, MathHelper.clamp(a, 0f, 1f));
    }
}
