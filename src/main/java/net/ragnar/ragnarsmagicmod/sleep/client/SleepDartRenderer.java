package net.ragnar.ragnarsmagicmod.sleep.client;

import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.ragnar.ragnarsmagicmod.sleep.Sleep;
import net.ragnar.ragnarsmagicmod.sleep.SleepDartEntity;

/** Draws the sleep dart as a small arrow (vanilla's arrow shape at about half size), tinted lavender and glowing. */
public class SleepDartRenderer extends EntityRenderer<SleepDartEntity> {
    private static final Identifier TEXTURE = Identifier.ofVanilla("textures/entity/projectiles/tipped_arrow.png");
    private static final float SCALE = 0.03f; // vanilla arrows are 0.05625
    private static final int TINT = 0xFF000000 | Sleep.COLOR;

    public SleepDartRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
    }

    @Override
    public void render(SleepDartEntity dart, float yaw, float tickDelta, MatrixStack ms, VertexConsumerProvider buffers, int light) {
        // Not drawn for its first moment, while it's still right in the caster's face
        if (dart.age < 2 && dispatcher.camera.getPos().squaredDistanceTo(dart.getPos()) < 4.0) return;
        ms.push();
        ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(MathHelper.lerp(tickDelta, dart.prevYaw, dart.getYaw()) - 90f));
        ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(MathHelper.lerp(tickDelta, dart.prevPitch, dart.getPitch())));
        ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(45f));
        ms.scale(SCALE, SCALE, SCALE);
        ms.translate(-4f, 0f, 0f);
        VertexConsumer vc = buffers.getBuffer(RenderLayer.getEntityCutout(TEXTURE));
        MatrixStack.Entry e = ms.peek();
        int glow = LightmapTextureManager.MAX_LIGHT_COORDINATE;

        // The fletching, seen end on
        vertex(e, vc, -7, -2, -2, 0f, 0.15625f, -1, 0, 0, glow);
        vertex(e, vc, -7, -2, 2, 0.15625f, 0.15625f, -1, 0, 0, glow);
        vertex(e, vc, -7, 2, 2, 0.15625f, 0.3125f, -1, 0, 0, glow);
        vertex(e, vc, -7, 2, -2, 0f, 0.3125f, -1, 0, 0, glow);
        vertex(e, vc, -7, 2, -2, 0f, 0.15625f, 1, 0, 0, glow);
        vertex(e, vc, -7, 2, 2, 0.15625f, 0.15625f, 1, 0, 0, glow);
        vertex(e, vc, -7, -2, 2, 0.15625f, 0.3125f, 1, 0, 0, glow);
        vertex(e, vc, -7, -2, -2, 0f, 0.3125f, 1, 0, 0, glow);
        // The shaft, as four quads turned round it
        for (int i = 0; i < 4; i++) {
            ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(90f));
            vertex(e, vc, -8, -2, 0, 0f, 0f, 0, 1, 0, glow);
            vertex(e, vc, 8, -2, 0, 0.5f, 0f, 0, 1, 0, glow);
            vertex(e, vc, 8, 2, 0, 0.5f, 0.15625f, 0, 1, 0, glow);
            vertex(e, vc, -8, 2, 0, 0f, 0.15625f, 0, 1, 0, glow);
        }
        ms.pop();
        super.render(dart, yaw, tickDelta, ms, buffers, light);
    }

    private static void vertex(MatrixStack.Entry e, VertexConsumer vc, int x, int y, int z, float u, float v,
                               int nx, int ny, int nz, int light) {
        vc.vertex(e, x, y, z).color(TINT).texture(u, v).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(e, nx, ny, nz);
    }

    @Override
    public Identifier getTexture(SleepDartEntity dart) {
        return TEXTURE;
    }
}
