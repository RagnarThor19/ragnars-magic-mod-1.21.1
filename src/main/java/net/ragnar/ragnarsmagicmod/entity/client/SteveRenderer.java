package net.ragnar.ragnarsmagicmod.entity.client;

import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.feature.HeldItemFeatureRenderer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.util.Identifier;
import net.ragnar.ragnarsmagicmod.entity.SteveEntity;

public class SteveRenderer extends MobEntityRenderer<SteveEntity, PlayerEntityModel<SteveEntity>> {

    private static final Identifier STEVE_TEXTURE =
            Identifier.of("ragnarsmagicmod", "textures/entity/steve.png");

    public SteveRenderer(EntityRendererFactory.Context context) {
        super(
                context,
                new PlayerEntityModel<>(context.getPart(EntityModelLayers.PLAYER), false),
                0.5F
        );

        // IMPORTANT: This MUST be added for held items to render
        this.addFeature(new HeldItemFeatureRenderer<>(this, context.getHeldItemRenderer()));
    }

    @Override
    public Identifier getTexture(SteveEntity entity) {
        return STEVE_TEXTURE;
    }
}
