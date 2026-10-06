package net.ragnar.ragnarsmagicmod.entity.client;

import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.Identifier;
import net.ragnar.ragnarsmagicmod.boulders.client.RockModel;
import net.ragnar.ragnarsmagicmod.entity.RockEntity;

/** Draws the Tome of Rocks' rock as a little 3D lump of stone, tumbling as it flies. */
public class RockRenderer extends EntityRenderer<RockEntity> {
    public RockRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
        shadowRadius = 0.2f;
    }

    @Override
    public void render(RockEntity rock, float yaw, float tickDelta, MatrixStack ms, VertexConsumerProvider buffers, int light) {
        // Like a thrown snowball: not drawn for its first moment, while it's still in the caster's face
        if (rock.age < 2 && dispatcher.camera.getPos().squaredDistanceTo(rock.getPos()) < 12.25) return;
        ms.push();
        ms.translate(0f, rock.getHeight() / 2f, 0f);
        ms.multiply(rock.tumble.get(tickDelta));
        ms.scale(RockEntity.RADIUS, RockEntity.RADIUS, RockEntity.RADIUS);
        RockModel.render(RockModel.ROCK, ms, buffers, light);
        ms.pop();
        super.render(rock, yaw, tickDelta, ms, buffers, light);
    }

    @Override
    public Identifier getTexture(RockEntity rock) {
        return PlayerScreenHandler.BLOCK_ATLAS_TEXTURE;
    }
}
