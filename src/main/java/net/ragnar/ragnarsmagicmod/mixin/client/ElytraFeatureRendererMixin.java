package net.ragnar.ragnarsmagicmod.mixin.client;

import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.feature.ElytraFeatureRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.ragnar.ragnarsmagicmod.client.WingsClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While Tome of Wings wings are out, they replace the elytra on your back instead of sprouting on top of it. */
@Mixin(ElytraFeatureRenderer.class)
public class ElytraFeatureRendererMixin {
    @Inject(method = "render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;ILnet/minecraft/entity/LivingEntity;FFFFFF)V",
            at = @At("HEAD"), cancellable = true)
    private void ragnarsmagicmod$hideUnderWings(MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light,
                                                LivingEntity entity, float limbAngle, float limbDistance, float tickDelta,
                                                float animationProgress, float headYaw, float headPitch, CallbackInfo ci) {
        if (WingsClient.get(entity) != null) ci.cancel();
    }
}
