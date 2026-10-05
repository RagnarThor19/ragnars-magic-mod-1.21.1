package net.ragnar.ragnarsmagicmod.shadowhands.client;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/**
 * Drawing for the Tome of Unseen Hands: solid black boxes for the hands, a soft additive purple for their aura, and
 * see-through panes for the pool. Every point is relative to the camera.
 */
final class Shapes {
    private Shapes() {}

    /** The hands themselves: opaque, and they hide what's behind them. */
    static final RenderLayer FLESH = RenderLayer.of("ragnarsmagicmod_shadow_flesh",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 65536, false, false,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.NO_TRANSPARENCY)
                    .writeMaskState(RenderPhase.ALL_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    /** The pool of shadow on the ground: darkens what it covers. */
    static final RenderLayer POOL = RenderLayer.of("ragnarsmagicmod_shadow_pool",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 65536, false, true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.TRANSLUCENT_TRANSPARENCY)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    /** The purple aura and the glowing edge: adds light. */
    static final RenderLayer AURA = RenderLayer.of("ragnarsmagicmod_shadow_aura",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 65536, false, true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.LIGHTNING_TRANSPARENCY)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    private static final Vec3d LIGHT = new Vec3d(0.3, 1.0, 0.45).normalize();

    /**
     * A box from {@code a} to {@code b}, {@code w} by {@code h} across, its {@code h} side along {@code upHint} (as far
     * as it can be). Overlaps a little past both ends so a chain of them reads as one limb. Faces facing the light
     * come out a touch lighter, so the black still has shape.
     */
    static void bar(VertexConsumer vc, Matrix4f pose, Vec3d a, Vec3d b, float w, float h, Vec3d upHint,
                    float r, float g, float bl, float alpha, float overlap) {
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
        face(vc, pose, c[0], c[1], c[5], c[4], up.negate(), r, g, bl, alpha);
        face(vc, pose, c[3], c[2], c[6], c[7], up, r, g, bl, alpha);
        face(vc, pose, c[0], c[3], c[7], c[4], side.negate(), r, g, bl, alpha);
        face(vc, pose, c[1], c[2], c[6], c[5], side, r, g, bl, alpha);
        face(vc, pose, c[0], c[1], c[2], c[3], dir.negate(), r, g, bl, alpha);
        face(vc, pose, c[4], c[5], c[6], c[7], dir, r, g, bl, alpha);
    }

    private static void face(VertexConsumer vc, Matrix4f pose, Vec3d p0, Vec3d p1, Vec3d p2, Vec3d p3, Vec3d n,
                             float r, float g, float b, float a) {
        float lit = 0.55f + 0.45f * (float) Math.max(0, n.dotProduct(LIGHT));
        vertex(vc, pose, p0, r * lit, g * lit, b * lit, a);
        vertex(vc, pose, p1, r * lit, g * lit, b * lit, a);
        vertex(vc, pose, p2, r * lit, g * lit, b * lit, a);
        vertex(vc, pose, p3, r * lit, g * lit, b * lit, a);
    }

    /** A flat horizontal square at {@code y}, {@code s} across, centred on {@code x}, {@code z}. */
    static void tile(VertexConsumer vc, Matrix4f pose, double x, double y, double z, double s,
                     float r, float g, float b, float a) {
        double h = s / 2;
        vertex(vc, pose, new Vec3d(x - h, y, z - h), r, g, b, a);
        vertex(vc, pose, new Vec3d(x + h, y, z - h), r, g, b, a);
        vertex(vc, pose, new Vec3d(x + h, y, z + h), r, g, b, a);
        vertex(vc, pose, new Vec3d(x - h, y, z + h), r, g, b, a);
    }

    static void vertex(VertexConsumer vc, Matrix4f pose, Vec3d p, float r, float g, float b, float a) {
        vc.vertex(pose, (float) p.x, (float) p.y, (float) p.z).color(MathHelper.clamp(r, 0f, 1f), MathHelper.clamp(g, 0f, 1f),
                MathHelper.clamp(b, 0f, 1f), MathHelper.clamp(a, 0f, 1f));
    }
}
