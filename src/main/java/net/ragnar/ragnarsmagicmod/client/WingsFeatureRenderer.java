package net.ragnar.ragnarsmagicmod.client;

import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.*;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix4f;

/**
 * Tome of Wings wings: two wings of glowing feathers on the player's back. Long flight feathers fan out along
 * each wing with a row of shorter coverts over them, white at the root and fading to sky-blue at the tips.
 */
public class WingsFeatureRenderer extends FeatureRenderer<AbstractClientPlayerEntity, PlayerEntityModel<AbstractClientPlayerEntity>> {

    private static final RenderLayer FEATHERS = RenderLayer.of("ragnarsmagicmod_wing_feathers",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 4096, false, true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.TRANSLUCENT_TRANSPARENCY)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    private static final RenderLayer HALO = RenderLayer.of("ragnarsmagicmod_wing_halo",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 4096, false, true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.LIGHTNING_TRANSPARENCY)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    private static final float SPAN = 1.25f;
    private static final int PRIMARIES = 9;
    private static final int COVERTS = 7;

    public WingsFeatureRenderer(FeatureRendererContext<AbstractClientPlayerEntity, PlayerEntityModel<AbstractClientPlayerEntity>> context) {
        super(context);
    }

    @Override
    public void render(MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, AbstractClientPlayerEntity player,
                       float limbAngle, float limbDistance, float tickDelta, float animationProgress, float headYaw, float headPitch) {
        WingsClient.Wings wings = WingsClient.get(player);
        if (wings == null || player.isInvisible()) return;
        float open = WingsClient.openness(wings, tickDelta);
        if (open <= 0.01f) return;
        float time = WingsClient.age(wings, tickDelta);

        // One big beat as they snap open, then a steady gliding tremble
        float beat = time < 10f ? MathHelper.sin(time * 0.9f) * 38f * (1f - time / 10f) : 0f;
        float raise = 18f + beat + MathHelper.sin(time * 0.45f) * 6f;
        float sweep = MathHelper.lerp(open, 75f, 22f); // folded right back when closing
        float scale = Math.max(0.001f, Math.min(open, 1.08f));

        matrices.push();
        this.getContextModel().body.rotate(matrices);
        for (int side = -1; side <= 1; side += 2) {
            matrices.push();
            matrices.translate(side * 0.1f, 0.22f, 0.16f);
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-side * sweep));
            matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(-side * raise));
            matrices.scale(scale, scale, scale);
            Matrix4f m = matrices.peek().getPositionMatrix();

            float fade = Math.min(1f, open * 1.2f);
            drawWing(vertexConsumers.getBuffer(HALO), m, side, 1.35f, 0.25f * fade, true);
            drawWing(vertexConsumers.getBuffer(FEATHERS), m, side, 1f, fade, false);
            matrices.pop();
        }
        matrices.pop();
    }

    /**
     * One wing in its own plane: the arm runs out along x (mirrored by {@code side}), feathers hang down (+y)
     * and sweep outward toward the tip.
     */
    private static void drawWing(VertexConsumer vc, Matrix4f m, int side, float grow, float alpha, boolean halo) {
        // Flight feathers: short and pointing down at the root, long and pointing out at the tip
        for (int i = 0; i < PRIMARIES; i++) {
            float t = 0.12f + 0.88f * i / (PRIMARIES - 1);
            float angle = MathHelper.lerp((float) Math.pow(t, 1.4), 95f, 12f) * MathHelper.RADIANS_PER_DEGREE;
            float length = MathHelper.lerp(t, 0.45f, 0.95f) * (1f - 0.18f * t * t * t * t) * grow;
            float width = 0.14f * grow;
            float z = 0.012f * i;
            feather(vc, m, side, armX(t), armY(t), z, angle, length, width,
                    halo ? new float[]{0.6f, 0.85f, 1f} : new float[]{1f, 1f, 1f},
                    halo ? new float[]{0.4f, 0.7f, 1f} : new float[]{0.62f, 0.84f, 1f},
                    alpha * (halo ? 1f : 0.88f), alpha * (halo ? 0f : 0.3f));
        }
        if (halo) return;

        // Coverts: a softer, whiter row laid over the base of the flight feathers
        for (int i = 0; i < COVERTS; i++) {
            float t = 0.04f + 0.8f * i / (COVERTS - 1);
            float angle = MathHelper.lerp(t, 100f, 38f) * MathHelper.RADIANS_PER_DEGREE;
            feather(vc, m, side, armX(t), armY(t), -0.02f - 0.004f * i, angle, 0.32f, 0.16f,
                    new float[]{1f, 1f, 1f}, new float[]{0.88f, 0.95f, 1f}, alpha * 0.95f, alpha * 0.6f);
        }

        // The arm of the wing: a bright ridge along the top edge
        int segs = 10;
        for (int k = 0; k < segs; k++) {
            float t0 = k / (float) segs, t1 = (k + 1) / (float) segs;
            float w0 = 0.05f * (1f - t0 * 0.7f), w1 = 0.05f * (1f - t1 * 0.7f);
            quad(vc, m,
                    side * armX(t0), armY(t0) - w0, -0.03f,
                    side * armX(t1), armY(t1) - w1, -0.03f,
                    side * armX(t1), armY(t1) + w1, -0.03f,
                    side * armX(t0), armY(t0) + w0, -0.03f,
                    1f, 1f, 1f, alpha, 1f, 1f, 1f, alpha);
        }
    }

    private static float armX(float t) {
        return 0.04f + t * SPAN;
    }

    /** The arm arches up a little (y is down in model space). */
    private static float armY(float t) {
        return -0.16f * MathHelper.sin(t * MathHelper.PI);
    }

    /** A leaf-shaped feather from (x, y) pointing along {@code angle} (0 = straight out, 90 = straight down). */
    private static void feather(VertexConsumer vc, Matrix4f m, int side, float x, float y, float z, float angle,
                                float length, float width, float[] root, float[] tip, float rootAlpha, float tipAlpha) {
        float dx = side * MathHelper.cos(angle), dy = MathHelper.sin(angle);
        float px = -dy, py = dx; // across the feather
        float bx = side * x, by = y;
        float mx = bx + dx * length * 0.4f, my = by + dy * length * 0.4f;
        float tx = bx + dx * length, ty = by + dy * length;
        float hw = width / 2;
        // Diamond: root, one edge, tip, other edge
        vc.vertex(m, bx, by, z).color(root[0], root[1], root[2], rootAlpha);
        vc.vertex(m, mx + px * hw, my + py * hw, z).color(root[0], root[1], root[2], (rootAlpha + tipAlpha) * 0.5f);
        vc.vertex(m, tx, ty, z).color(tip[0], tip[1], tip[2], tipAlpha);
        vc.vertex(m, mx - px * hw, my - py * hw, z).color(root[0], root[1], root[2], (rootAlpha + tipAlpha) * 0.5f);
    }

    private static void quad(VertexConsumer vc, Matrix4f m,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz,
                             float r0, float g0, float b0, float a0, float r1, float g1, float b1, float a1) {
        vc.vertex(m, ax, ay, az).color(r0, g0, b0, a0);
        vc.vertex(m, bx, by, bz).color(r0, g0, b0, a0);
        vc.vertex(m, cx, cy, cz).color(r1, g1, b1, a1);
        vc.vertex(m, dx, dy, dz).color(r1, g1, b1, a1);
    }
}
