package net.ragnar.ragnarsmagicmod.crows.client;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/**
 * Drawing for the Tome of Crows: a crow built from little boxes, blocky like everything else in Minecraft - body,
 * head, beak, a fanned tail and two jointed wings ending in splayed primaries - and the black feathers they shed.
 * Every point is relative to the camera.
 */
final class CrowDraw {
    private CrowDraw() {}

    /** Opaque, and it hides what's behind it. */
    static final RenderLayer SOLID = RenderLayer.of("ragnarsmagicmod_crows",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 65536, false, false,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.NO_TRANSPARENCY)
                    .writeMaskState(RenderPhase.ALL_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    private static final Vec3d LIGHT = new Vec3d(0.3, 1.0, 0.45).normalize();

    // Glossy black, the wings with a blue-violet sheen where the light catches them
    private static final float[] BODY = {0.075f, 0.072f, 0.09f};
    private static final float[] WING = {0.07f, 0.075f, 0.115f};
    private static final float[] BEAK = {0.17f, 0.16f, 0.16f};
    private static final float[] EYE = {0.95f, 0.72f, 0.22f};

    /**
     * A crow at {@code c}, facing {@code f}, with {@code s} its right and {@code u} its up (all unit length and at right
     * angles). {@code flap} is how far the wings are raised, in radians (negative: the downstroke).
     */
    static void crow(VertexConsumer vc, Matrix4f pose, Vec3d c, Vec3d f, Vec3d s, Vec3d u, float scale, float flap) {
        Vec3d F = f.multiply(scale), S = s.multiply(scale), U = u.multiply(scale);
        // Body, head, beak
        bar(vc, pose, at(c, F, U, -0.16, 0.0), at(c, F, U, 0.11, 0.01), 0.15f * scale, 0.13f * scale, u, BODY, 0.0f);
        bar(vc, pose, at(c, F, U, 0.08, 0.035), at(c, F, U, 0.22, 0.05), 0.11f * scale, 0.11f * scale, u, BODY, 0.0f);
        bar(vc, pose, at(c, F, U, 0.21, 0.04), at(c, F, U, 0.31, 0.02), 0.045f * scale, 0.04f * scale, u, BEAK, 0.0f);
        // Eyes: a glint either side of the head
        for (int side = -1; side <= 1; side += 2) {
            Vec3d e = at(c, F, U, 0.185, 0.07).add(S.multiply(side * 0.056));
            bar(vc, pose, e.subtract(F.multiply(0.014)), e.add(F.multiply(0.014)), 0.012f * scale, 0.026f * scale, u, EYE, 0.0f);
        }
        // Tail: a fan, wider at the end
        Vec3d tailRoot = at(c, F, U, -0.13, 0.015);
        Vec3d tailEnd = at(c, F, U, -0.33, -0.03);
        bar(vc, pose, tailRoot, tailEnd, 0.1f * scale, 0.025f * scale, u, BODY, 0.0f);
        bar(vc, pose, tailRoot.lerp(tailEnd, 0.55), tailEnd, 0.15f * scale, 0.022f * scale, u, BODY, 0.0f);

        // Wings: shoulder to wrist, wrist to tip (bent further and swept back), and the primaries splayed at the end
        for (int side = -1; side <= 1; side += 2) {
            Vec3d out = S.multiply(side);
            float a1 = flap, a2 = flap * 1.45f;
            Vec3d d1 = out.multiply(Math.cos(a1)).add(U.multiply(Math.sin(a1))).normalize();
            Vec3d n1 = U.multiply(Math.cos(a1)).subtract(out.multiply(Math.sin(a1))).normalize();
            Vec3d d2 = out.multiply(Math.cos(a2)).add(U.multiply(Math.sin(a2))).normalize().subtract(f.multiply(0.32)).normalize();
            Vec3d n2 = U.multiply(Math.cos(a2)).subtract(out.multiply(Math.sin(a2))).normalize();
            Vec3d shoulder = at(c, F, U, 0.0, 0.04).add(out.multiply(0.06));
            Vec3d wrist = shoulder.add(d1.multiply(0.21 * scale));
            Vec3d tip = wrist.add(d2.multiply(0.22 * scale));
            bar(vc, pose, shoulder, wrist, 0.19f * scale, 0.022f * scale, n1, WING, 0.01f * scale);
            bar(vc, pose, wrist.subtract(f.multiply(0.012 * scale)), tip, 0.14f * scale, 0.02f * scale, n2, WING, 0.01f * scale);
            for (int i = 0; i < 3; i++) {
                Vec3d root = tip.subtract(f.multiply((0.015 + i * 0.04) * scale)).subtract(d2.multiply(0.03 * scale));
                Vec3d dir = d2.subtract(f.multiply(0.25 + i * 0.4)).normalize();
                bar(vc, pose, root, root.add(dir.multiply((0.1 - i * 0.012) * scale)), 0.03f * scale, 0.014f * scale, n2, WING, 0.0f);
            }
        }
    }

    /** A feather at {@code p}: a long thin diamond along {@code axis}, {@code width} across in the {@code across} direction. */
    static void feather(VertexConsumer vc, Matrix4f pose, Vec3d p, Vec3d axis, Vec3d across, float length, float width) {
        Vec3d a = axis.multiply(length / 2), w = across.multiply(width / 2);
        Vec3d tip = p.add(a), quill = p.subtract(a);
        Vec3d l = p.add(a.multiply(0.15)).add(w), r = p.add(a.multiply(0.15)).subtract(w);
        float lit = 0.6f + 0.4f * (float) Math.abs(axis.crossProduct(across).normalize().dotProduct(LIGHT));
        vertex(vc, pose, quill, WING[0] * lit, WING[1] * lit, WING[2] * lit);
        vertex(vc, pose, l, WING[0] * lit, WING[1] * lit, WING[2] * lit);
        vertex(vc, pose, tip, WING[0] * lit, WING[1] * lit, WING[2] * lit);
        vertex(vc, pose, r, WING[0] * lit, WING[1] * lit, WING[2] * lit);
    }

    /** {@code c} moved {@code fwd} along {@code F} and {@code up} along {@code U}. */
    private static Vec3d at(Vec3d c, Vec3d F, Vec3d U, double fwd, double up) {
        return c.add(F.multiply(fwd)).add(U.multiply(up));
    }

    /**
     * A box from {@code a} to {@code b}, {@code w} by {@code h} across, its {@code h} side along {@code upHint} (as far
     * as it can be). Faces turned to the light come out a touch lighter, so the black still has shape.
     */
    private static void bar(VertexConsumer vc, Matrix4f pose, Vec3d a, Vec3d b, float w, float h, Vec3d upHint,
                            float[] rgb, float overlap) {
        Vec3d d = b.subtract(a);
        double len = d.length();
        if (len < 1e-5) return;
        Vec3d dir = d.multiply(1 / len);
        Vec3d side = dir.crossProduct(upHint);
        if (side.lengthSquared() < 1e-6) side = dir.crossProduct(new Vec3d(1, 0, 0));
        if (side.lengthSquared() < 1e-6) side = dir.crossProduct(new Vec3d(0, 0, 1));
        side = side.normalize();
        Vec3d up = side.crossProduct(dir).normalize();
        Vec3d a2 = a.subtract(dir.multiply(overlap)), b2 = b.add(dir.multiply(overlap));
        Vec3d sx = side.multiply(w / 2), uy = up.multiply(h / 2);
        Vec3d[] c = {
                a2.subtract(sx).subtract(uy), a2.add(sx).subtract(uy), a2.add(sx).add(uy), a2.subtract(sx).add(uy),
                b2.subtract(sx).subtract(uy), b2.add(sx).subtract(uy), b2.add(sx).add(uy), b2.subtract(sx).add(uy)};
        face(vc, pose, c[0], c[1], c[5], c[4], up.negate(), rgb);
        face(vc, pose, c[3], c[2], c[6], c[7], up, rgb);
        face(vc, pose, c[0], c[3], c[7], c[4], side.negate(), rgb);
        face(vc, pose, c[1], c[2], c[6], c[5], side, rgb);
        face(vc, pose, c[0], c[1], c[2], c[3], dir.negate(), rgb);
        face(vc, pose, c[4], c[5], c[6], c[7], dir, rgb);
    }

    private static void face(VertexConsumer vc, Matrix4f pose, Vec3d p0, Vec3d p1, Vec3d p2, Vec3d p3, Vec3d n, float[] rgb) {
        float lit = 0.55f + 0.45f * (float) Math.max(0, n.dotProduct(LIGHT));
        // The sheen: lit faces pick up a little more blue than red
        float r = rgb[0] * lit, g = rgb[1] * lit, b = rgb[2] * (lit + 0.25f * (lit - 0.55f));
        vertex(vc, pose, p0, r, g, b);
        vertex(vc, pose, p1, r, g, b);
        vertex(vc, pose, p2, r, g, b);
        vertex(vc, pose, p3, r, g, b);
    }

    private static void vertex(VertexConsumer vc, Matrix4f pose, Vec3d p, float r, float g, float b) {
        vc.vertex(pose, (float) p.x, (float) p.y, (float) p.z).color(MathHelper.clamp(r, 0f, 1f), MathHelper.clamp(g, 0f, 1f),
                MathHelper.clamp(b, 0f, 1f), 1f);
    }
}
