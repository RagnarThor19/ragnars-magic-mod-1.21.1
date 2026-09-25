package net.ragnar.ragnarsmagicmod.entity.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.feature.ArmorFeatureRenderer;
import net.minecraft.client.render.entity.feature.HeldItemFeatureRenderer;
import net.minecraft.client.render.entity.model.ArmorEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.ragnar.ragnarsmagicmod.entity.IllusionEntity;

import java.util.UUID;

/** Draws the Tome of Illusion decoy as its caster: same skin, same arm width, same gear. */
public class IllusionRenderer extends LivingEntityRenderer<IllusionEntity, PlayerEntityModel<IllusionEntity>> {
    private final PlayerEntityModel<IllusionEntity> wide;
    private final PlayerEntityModel<IllusionEntity> slim;

    public IllusionRenderer(EntityRendererFactory.Context context) {
        super(context, new PlayerEntityModel<>(context.getPart(EntityModelLayers.PLAYER), false), 0.5f);
        this.wide = this.model;
        this.slim = new PlayerEntityModel<>(context.getPart(EntityModelLayers.PLAYER_SLIM), true);
        this.addFeature(new ArmorFeatureRenderer<>(
                this,
                new ArmorEntityModel<>(context.getPart(EntityModelLayers.PLAYER_INNER_ARMOR)),
                new ArmorEntityModel<>(context.getPart(EntityModelLayers.PLAYER_OUTER_ARMOR)),
                context.getModelManager()
        ));
        this.addFeature(new HeldItemFeatureRenderer<>(this, context.getHeldItemRenderer()));
    }

    private static SkinTextures skinOf(IllusionEntity entity) {
        UUID owner = entity.getOwnerUuid().orElse(null);
        if (owner == null) return DefaultSkinHelper.getSkinTextures(entity.getUuid());
        ClientPlayNetworkHandler handler = MinecraftClient.getInstance().getNetworkHandler();
        PlayerListEntry entry = handler == null ? null : handler.getPlayerListEntry(owner);
        return entry != null ? entry.getSkinTextures() : DefaultSkinHelper.getSkinTextures(owner);
    }

    @Override
    public void render(IllusionEntity entity, float yaw, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light) {
        this.model = skinOf(entity).model() == SkinTextures.Model.SLIM ? slim : wide;
        super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);
    }

    @Override
    public Identifier getTexture(IllusionEntity entity) {
        return skinOf(entity).texture();
    }

    @Override
    protected void scale(IllusionEntity entity, MatrixStack matrices, float amount) {
        // Players are drawn at 15/16 scale
        matrices.scale(0.9375f, 0.9375f, 0.9375f);
    }
}
