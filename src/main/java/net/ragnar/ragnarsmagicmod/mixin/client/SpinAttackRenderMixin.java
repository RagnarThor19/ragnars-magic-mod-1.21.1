package net.ragnar.ragnarsmagicmod.mixin.client;

import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.RotationAxis;
import net.ragnar.ragnarsmagicmod.spinattack.client.SpinAttackClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tome of Spin Attack: turns a spinning player's whole body round (see SpinAttackClient). */
@Mixin(LivingEntityRenderer.class)
public abstract class SpinAttackRenderMixin {
    @Inject(method = "setupTransforms", at = @At("TAIL"))
    private void ragnarsmagicmod$spin(LivingEntity entity, MatrixStack matrices, float animationProgress, float bodyYaw,
                                      float tickDelta, float scale, CallbackInfo ci) {
        float angle = SpinAttackClient.angle(entity, tickDelta);
        if (angle != 0f) matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-angle));
    }
}
