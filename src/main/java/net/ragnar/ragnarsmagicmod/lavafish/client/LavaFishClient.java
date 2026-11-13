package net.ragnar.ragnarsmagicmod.lavafish.client;

import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.feature.EyesFeatureRenderer;
import net.minecraft.client.render.entity.model.CodEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.lavafish.LavaFish;
import net.ragnar.ragnarsmagicmod.lavafish.LavaFishEntity;

/** The lava fish is drawn on the cod's own model, with its lava cracks and eyes glowing in the dark. */
public final class LavaFishClient {
    private LavaFishClient() {}

    public static void init() {
        EntityRendererRegistry.register(LavaFish.LAVA_FISH, Renderer::new);
    }

    static final class Renderer extends MobEntityRenderer<LavaFishEntity, CodEntityModel<LavaFishEntity>> {
        private static final Identifier TEXTURE = Identifier.of(RagnarsMagicMod.MOD_ID, "textures/entity/lava_fish.png");
        private static final RenderLayer GLOW = RenderLayer.getEyes(Identifier.of(RagnarsMagicMod.MOD_ID, "textures/entity/lava_fish_glow.png"));

        Renderer(EntityRendererFactory.Context ctx) {
            super(ctx, new CodEntityModel<>(ctx.getPart(EntityModelLayers.COD)), 0.3f);
            addFeature(new EyesFeatureRenderer<>(this) {
                @Override
                public RenderLayer getEyesTexture() {
                    return GLOW;
                }
            });
        }

        @Override
        public Identifier getTexture(LavaFishEntity entity) {
            return TEXTURE;
        }

        /** As the cod's: a wiggle, and on its side when out of the water - or, here, the lava. */
        @Override
        protected void setupTransforms(LavaFishEntity entity, MatrixStack matrices, float animationProgress, float bodyYaw, float tickDelta, float scale) {
            super.setupTransforms(entity, matrices, animationProgress, bodyYaw, tickDelta, scale);
            float wiggle = 4.3f * MathHelper.sin(0.6f * animationProgress);
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(wiggle));
            if (!entity.isTouchingWater() && !entity.isInLava()) {
                matrices.translate(0.1f, 0.1f, -0.1f);
                matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(90.0f));
            }
        }
    }
}
