package net.ragnar.ragnarsmagicmod.bubbles.client;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/**
 * Drawing for the Tome of Bubbles. The film is a faceted ball, each face one flat colour picked from a stepped
 * soap-film rainbow - mostly clear face on, thickening into bands of colour toward the rim - with chunky square
 * highlights like Minecraft's own bubble particles. All points are relative to the camera.
 */
final class BubbleDraw {
    private BubbleDraw() {}

    static final RenderLayer LAYER = RenderLayer.of("ragnarsmagicmod_bubble",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 16384, false, true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.TRANSLUCENT_TRANSPARENCY)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    /** The soap-film bands, in order round the rainbow: aqua, sky, lilac, pink, peach, gold, mint. */
    private static final float[][] FILM = {
            {0.45f, 0.95f, 1.00f},
            {0.55f, 0.75f, 1.00f},
            {0.78f, 0.62f, 1.00f},
            {1.00f, 0.62f, 0.90f},
            {1.00f, 0.78f, 0.62f},
            {1.00f, 0.95f, 0.55f},
            {0.60f, 1.00f, 0.75f},
    };

    /** The film colour at {@code t} round the rainbow (wraps), in hard steps. */
    static float[] film(double t) {
        int i = Math.floorMod((int) Math.floor(t * FILM.length), FILM.length);
        return FILM[i];
    }

    static void quad(VertexConsumer vc, Matrix4f pose, Vec3d a, Vec3d b, Vec3d c, Vec3d d, float r, float g, float bl, float alpha) {
        if (alpha <= 0.004f) return;
        v(vc, pose, a, r, g, bl, alpha);
        v(vc, pose, b, r, g, bl, alpha);
        v(vc, pose, c, r, g, bl, alpha);
        v(vc, pose, d, r, g, bl, alpha);
    }

    /** A flat square facing the camera, {@code size} across. */
    static void square(VertexConsumer vc, Matrix4f pose, Vec3d c, Vec3d right, Vec3d up, float size,
                       float r, float g, float b, float alpha) {
        Vec3d x = right.multiply(size * 0.5), y = up.multiply(size * 0.5);
        quad(vc, pose, c.subtract(x).subtract(y), c.add(x).subtract(y), c.add(x).add(y), c.subtract(x).add(y), r, g, b, alpha);
    }

    /**
     * A hollow square facing the camera with a bright corner pixel: Minecraft's bubble, any size. {@code size} is
     * across the outside; the ring is an eighth of that thick.
     */
    static void ring(VertexConsumer vc, Matrix4f pose, Vec3d c, Vec3d right, Vec3d up, float size, float alpha) {
        float px = size / 8f;
        float h = size / 2f;
        // Top and bottom rows, then the sides between them
        bar(vc, pose, c, right, up, -h + px, h - px, h - px, h, alpha);
        bar(vc, pose, c, right, up, -h + px, h - px, -h, -h + px, alpha);
        bar(vc, pose, c, right, up, -h, -h + px, -h + px, h - px, alpha);
        bar(vc, pose, c, right, up, h - px, h, -h + px, h - px, alpha);
        // The glint in the top left
        Vec3d glint = c.add(right.multiply(-h + px * 2.5)).add(up.multiply(h - px * 2.5));
        square(vc, pose, glint, right, up, px, 1f, 1f, 1f, alpha);
    }

    private static void bar(VertexConsumer vc, Matrix4f pose, Vec3d c, Vec3d right, Vec3d up,
                            float x0, float x1, float y0, float y1, float alpha) {
        Vec3d a = c.add(right.multiply(x0)).add(up.multiply(y0));
        Vec3d b = c.add(right.multiply(x1)).add(up.multiply(y0));
        Vec3d d = c.add(right.multiply(x1)).add(up.multiply(y1));
        Vec3d e = c.add(right.multiply(x0)).add(up.multiply(y1));
        quad(vc, pose, a, b, d, e, 0.75f, 0.92f, 1f, alpha);
    }

    private static void v(VertexConsumer vc, Matrix4f pose, Vec3d p, float r, float g, float b, float a) {
        vc.vertex(pose, (float) p.x, (float) p.y, (float) p.z).color(r, g, b, MathHelper.clamp(a, 0f, 1f));
    }
}
