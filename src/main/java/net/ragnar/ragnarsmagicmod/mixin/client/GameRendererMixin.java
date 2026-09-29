package net.ragnar.ragnarsmagicmod.mixin.client;

import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.ragnar.ragnarsmagicmod.client.GrappleClient;
import net.ragnar.ragnarsmagicmod.client.PossessionClient;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A possessed mob has no player hand to draw. Grappling widens the view with speed. */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Inject(method = "renderHand", at = @At("HEAD"), cancellable = true)
    private void ragnarsmagicmod$noHandInHost(Camera camera, float tickDelta, Matrix4f matrix, CallbackInfo ci) {
        if (PossessionClient.isPossessing()) ci.cancel();
    }

    /** Tome of Grappling: the view widens as you pick up speed on the chain. */
    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void ragnarsmagicmod$grappleRush(Camera camera, float tickDelta, boolean changingFov, CallbackInfoReturnable<Double> cir) {
        if (changingFov) cir.setReturnValue(cir.getReturnValue() * GrappleClient.fovMultiplier(tickDelta));
    }
}
