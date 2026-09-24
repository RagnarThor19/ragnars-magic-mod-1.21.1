package net.ragnar.ragnarsmagicmod.mixin.client;

import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.ragnar.ragnarsmagicmod.client.PossessionClient;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A possessed mob has no player hand to draw. */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Inject(method = "renderHand", at = @At("HEAD"), cancellable = true)
    private void ragnarsmagicmod$noHandInHost(Camera camera, float tickDelta, Matrix4f matrix, CallbackInfo ci) {
        if (PossessionClient.isPossessing()) ci.cancel();
    }
}
