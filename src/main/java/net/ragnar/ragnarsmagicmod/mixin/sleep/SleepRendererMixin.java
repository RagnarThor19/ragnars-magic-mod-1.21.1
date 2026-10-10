package net.ragnar.ragnarsmagicmod.mixin.sleep;

import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.ragnar.ragnarsmagicmod.sleep.client.SleepClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tome of Sleep Darts / Sleep Potions: drowsy creatures nod and sway, sleeping ones slump over to one side. */
@Mixin(LivingEntityRenderer.class)
public abstract class SleepRendererMixin {
    @Inject(method = "setupTransforms", at = @At("TAIL"))
    private void ragnarsmagicmod$slump(LivingEntity entity, MatrixStack matrices, float animationProgress, float bodyYaw,
                                       float tickDelta, float scale, CallbackInfo ci) {
        SleepClient.slump(entity, matrices, tickDelta);
    }
}
