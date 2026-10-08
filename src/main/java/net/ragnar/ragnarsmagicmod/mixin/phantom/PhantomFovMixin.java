package net.ragnar.ragnarsmagicmod.mixin.phantom;

import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.ragnar.ragnarsmagicmod.phantom.client.PhantomClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Tome of the Phantom: the view widens the faster your spectre flies. */
@Mixin(AbstractClientPlayerEntity.class)
public class PhantomFovMixin {
    @Inject(method = "getFovMultiplier", at = @At("RETURN"), cancellable = true)
    private void ragnarsmagicmod$spectreSpeed(CallbackInfoReturnable<Float> cir) {
        float k = PhantomClient.fovMultiplier((AbstractClientPlayerEntity) (Object) this);
        if (k != 1f) cir.setReturnValue(cir.getReturnValueF() * k);
    }
}
