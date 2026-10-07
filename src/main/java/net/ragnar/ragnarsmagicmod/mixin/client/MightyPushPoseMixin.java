package net.ragnar.ragnarsmagicmod.mixin.client;

import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.entity.LivingEntity;
import net.ragnar.ragnarsmagicmod.mightypush.client.MightyPushClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tome of Mighty Pushing: a caster's arms spread into a cross. Runs after the usual pose is worked out, and before a
 * player model copies its arms onto its sleeves.
 */
@Mixin(BipedEntityModel.class)
public abstract class MightyPushPoseMixin {
    @Inject(method = "setAngles(Lnet/minecraft/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void ragnarsmagicmod$mightyPushPose(LivingEntity entity, float limbAngle, float limbDistance, float animationProgress,
                                                float headYaw, float headPitch, CallbackInfo ci) {
        MightyPushClient.pose(entity, (BipedEntityModel<?>) (Object) this, animationProgress - entity.age);
    }
}
