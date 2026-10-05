package net.ragnar.ragnarsmagicmod.mixin.upsidedown;

import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.entity.LivingEntity;
import net.ragnar.ragnarsmagicmod.upsidedown.UpsideDown;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Tome of Upside Down: everyone sees a flipped player hanging upside down - vanilla's own Dinnerbone flip. */
@Mixin(LivingEntityRenderer.class)
public abstract class UpsideDownRendererMixin {
    @Inject(method = "shouldFlipUpsideDown", at = @At("HEAD"), cancellable = true)
    private static void ragnarsmagicmod$flipModel(LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
        if (UpsideDown.isFlipped(entity)) cir.setReturnValue(true);
    }
}
