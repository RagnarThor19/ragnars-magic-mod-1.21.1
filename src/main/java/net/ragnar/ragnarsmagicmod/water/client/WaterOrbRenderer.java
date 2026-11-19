package net.ragnar.ragnarsmagicmod.water.client;

import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.ragnar.ragnarsmagicmod.water.WaterOrbEntity;

/**
 * Draws the ball: a little voxel sphere of Minecraft water, slowly tumbling and wobbling like a blob of liquid, with a
 * paler cube of water glinting at its heart. It swells out of the staff over its first few ticks.
 */
public class WaterOrbRenderer extends EntityRenderer<WaterOrbEntity> {
    public WaterOrbRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
    }

    @Override
    public void render(WaterOrbEntity orb, float yaw, float tickDelta, MatrixStack ms, VertexConsumerProvider buffers, int light) {
        float t = orb.age + tickDelta;
        float grow = MathHelper.clamp(t / 4f, 0f, 1f);
        grow = 0.35f + 0.65f * (1f - (1f - grow) * (1f - grow));
        Sprite water = WaterVoxels.water();
        VertexConsumer vc = buffers.getBuffer(WaterVoxels.LAYER);

        ms.push();
        ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(t * 3.5f + orb.getId() * 37f));
        ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(MathHelper.sin(t * 0.09f + orb.getId()) * 25f));
        // A gentle jiggle, squashing one way and then the other like a drop of water
        float j = MathHelper.sin(t * 0.45f) * 0.07f, j2 = MathHelper.sin(t * 0.62f + 1.3f) * 0.05f;
        ms.scale(grow * (1 + j), grow * (1 - j + j2), grow * (1 - j2));

        float[] w = WaterVoxels.WATER, p = WaterVoxels.PALE;
        WaterVoxels.sphere(vc, ms.peek(), light, water, w[0], w[1], w[2], 0.78f);
        ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(t * 6f));
        WaterVoxels.cube(vc, ms.peek(), light, water, 0, 0, 0, 0.17f, p[0], p[1], p[2], 0.55f);
        ms.pop();
        super.render(orb, yaw, tickDelta, ms, buffers, light);
    }

    @Override
    public Identifier getTexture(WaterOrbEntity orb) {
        return PlayerScreenHandler.BLOCK_ATLAS_TEXTURE;
    }
}
