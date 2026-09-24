package net.ragnar.ragnarsmagicmod.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.ragnar.ragnarsmagicmod.client.PossessionClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** While possessing a mob, left click uses its ability and right click returns you to your body. */
@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {
    @Inject(method = "doAttack", at = @At("HEAD"), cancellable = true)
    private void ragnarsmagicmod$hostAbility(CallbackInfoReturnable<Boolean> cir) {
        if (PossessionClient.onAttack()) cir.setReturnValue(false);
    }

    @Inject(method = "doItemUse", at = @At("HEAD"), cancellable = true)
    private void ragnarsmagicmod$leaveHost(CallbackInfo ci) {
        if (PossessionClient.onUse()) ci.cancel();
    }

    @Inject(method = "handleBlockBreaking", at = @At("HEAD"), cancellable = true)
    private void ragnarsmagicmod$noBreakingWhilePossessing(boolean breaking, CallbackInfo ci) {
        if (PossessionClient.isPossessing()) ci.cancel();
    }
}
