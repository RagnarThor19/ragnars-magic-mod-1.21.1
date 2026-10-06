package net.ragnar.ragnarsmagicmod.boulders.client;

import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.Identifier;
import net.ragnar.ragnarsmagicmod.boulders.BoulderEntity;

/** Draws the boulder: a big craggy lump of stone and moss, swelling into being, then tumbling and rolling. */
public class BoulderRenderer extends EntityRenderer<BoulderEntity> {
    public BoulderRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
        shadowRadius = 1.0f;
        shadowOpacity = 0.8f;
    }

    @Override
    public void render(BoulderEntity boulder, float yaw, float tickDelta, MatrixStack ms, VertexConsumerProvider buffers, int light) {
        float s = BoulderEntity.RADIUS * boulder.formScale(tickDelta);
        ms.push();
        ms.translate(0f, boulder.getHeight() / 2f, 0f);
        ms.multiply(boulder.tumble.get(tickDelta));
        ms.scale(s, s, s);
        RockModel.render(RockModel.BOULDER, ms, buffers, light);
        ms.pop();
        super.render(boulder, yaw, tickDelta, ms, buffers, light);
    }

    @Override
    public Identifier getTexture(BoulderEntity boulder) {
        return PlayerScreenHandler.BLOCK_ATLAS_TEXTURE;
    }
}
