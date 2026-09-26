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
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.entity.PlayerCopy;

import java.util.UUID;

/** Draws a mob as the player it copies (Illusion decoys, clones): same skin, same arm width, same gear, same crouch. */
public class PlayerCopyRenderer<T extends MobEntity & PlayerCopy> extends LivingEntityRenderer<T, PlayerEntityModel<T>> {
    private final PlayerEntityModel<T> wide;
    private final PlayerEntityModel<T> slim;

    public PlayerCopyRenderer(EntityRendererFactory.Context context) {
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

    private static SkinTextures skinOf(MobEntity entity, UUID owner) {
        if (owner == null) return DefaultSkinHelper.getSkinTextures(entity.getUuid());
        ClientPlayNetworkHandler handler = MinecraftClient.getInstance().getNetworkHandler();
        PlayerListEntry entry = handler == null ? null : handler.getPlayerListEntry(owner);
        return entry != null ? entry.getSkinTextures() : DefaultSkinHelper.getSkinTextures(owner);
    }

    private static SkinTextures skinOf(PlayerCopy copy) {
        return skinOf((MobEntity) copy, copy.getOwnerUuid().orElse(null));
    }

    private static BipedEntityModel.ArmPose armPose(ItemStack stack) {
        return stack.isEmpty() ? BipedEntityModel.ArmPose.EMPTY : BipedEntityModel.ArmPose.ITEM;
    }

    @Override
    public void render(T entity, float yaw, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light) {
        this.model = skinOf(entity).model() == SkinTextures.Model.SLIM ? slim : wide;
        this.model.sneaking = entity.isInSneakingPose();
        this.model.rightArmPose = armPose(entity.getMainHandStack());
        this.model.leftArmPose = armPose(entity.getOffHandStack());
        super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);
    }

    @Override
    public Vec3d getPositionOffset(T entity, float tickDelta) {
        // Crouching players are drawn a little lower
        return entity.isInSneakingPose() ? new Vec3d(0, -0.125, 0) : super.getPositionOffset(entity, tickDelta);
    }

    @Override
    public Identifier getTexture(T entity) {
        return skinOf(entity).texture();
    }

    @Override
    protected void scale(T entity, MatrixStack matrices, float amount) {
        // Players are drawn at 15/16 scale
        matrices.scale(0.9375f, 0.9375f, 0.9375f);
    }
}
