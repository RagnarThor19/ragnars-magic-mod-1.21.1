package net.ragnar.ragnarsmagicmod.entity.client;

import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.entity.feature.ArmorFeatureRenderer;
import net.minecraft.client.render.entity.feature.EyesFeatureRenderer;
import net.minecraft.client.render.entity.feature.HeldItemFeatureRenderer;
import net.minecraft.client.render.entity.model.ArmorEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.util.Identifier;
import net.ragnar.ragnarsmagicmod.entity.SteveEntity;

public class SteveRenderer extends MobEntityRenderer<SteveEntity, PlayerEntityModel<SteveEntity>> {

    private static final Identifier STEVE_TEXTURE =
            Identifier.of("ragnarsmagicmod", "textures/entity/steve.png");
    /** Summoned Steves' eyes glow a cold cyan, light or dark. */
    private static final RenderLayer EYES = RenderLayer.getEyes(Identifier.of("ragnarsmagicmod", "textures/entity/steve_eyes.png"));

    public SteveRenderer(EntityRendererFactory.Context context) {
        super(
                context,
                new PlayerEntityModel<>(context.getPart(EntityModelLayers.PLAYER), false),
                0.5F
        );

        // IMPORTANT: This MUST be added for held items to render
        this.addFeature(new HeldItemFeatureRenderer<>(this, context.getHeldItemRenderer()));

        // 2. Render Armor
        this.addFeature(new ArmorFeatureRenderer<>(
                this,
                new ArmorEntityModel<>(context.getPart(EntityModelLayers.PLAYER_INNER_ARMOR)),
                new ArmorEntityModel<>(context.getPart(EntityModelLayers.PLAYER_OUTER_ARMOR)),
                context.getModelManager()
        ));

        // 3. Glowing eyes
        this.addFeature(new EyesFeatureRenderer<>(this) {
            @Override
            public RenderLayer getEyesTexture() {
                return EYES;
            }
        });
    }

    @Override
    public Identifier getTexture(SteveEntity entity) {
        return STEVE_TEXTURE;
    }
}
