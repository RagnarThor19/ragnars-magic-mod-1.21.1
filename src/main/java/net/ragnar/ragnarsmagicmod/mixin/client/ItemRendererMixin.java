package net.ragnar.ragnarsmagicmod.mixin.client;

import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.ragnar.ragnarsmagicmod.client.StaffModels;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Staffs: flat sprite in the inventory, item frames and on the ground; 3D model in hand (see StaffModels). */
@Mixin(ItemRenderer.class)
public class ItemRendererMixin {
    @ModifyVariable(method = "renderItem(Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/render/model/json/ModelTransformationMode;ZLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;IILnet/minecraft/client/render/model/BakedModel;)V",
            at = @At("HEAD"), argsOnly = true)
    private BakedModel ragnarsmagicmod$flatStaffInInventory(BakedModel model, ItemStack stack, ModelTransformationMode mode,
                                                            boolean leftHanded, MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                                                            int light, int overlay) {
        BakedModel flat = StaffModels.flatModel(stack, mode);
        return flat != null ? flat : model;
    }
}
