package net.ragnar.ragnarsmagicmod.mixin.astral;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.astral.client.AstralClient;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tome of Astral Projection: a spirit has no hands to draw, aims at nothing (so your body can't reach anything near
 * it), and its view breathes wider and narrower as it leaves, drifts and is reeled back.
 */
@Mixin(GameRenderer.class)
public class AstralGameRendererMixin {
    @Shadow @Final MinecraftClient client;

    @Inject(method = "renderHand", at = @At("HEAD"), cancellable = true)
    private void ragnarsmagicmod$noSpiritHands(Camera camera, float tickDelta, Matrix4f matrix, CallbackInfo ci) {
        if (AstralClient.isActive()) ci.cancel();
    }

    @Inject(method = "updateCrosshairTarget", at = @At("TAIL"))
    private void ragnarsmagicmod$spiritAimsAtNothing(float tickDelta, CallbackInfo ci) {
        if (!AstralClient.isActive() || client.player == null) return;
        Vec3d at = client.player.getEyePos();
        client.crosshairTarget = BlockHitResult.createMissed(at, Direction.UP, BlockPos.ofFloored(at));
        client.targetedEntity = null;
    }

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void ragnarsmagicmod$spiritFov(Camera camera, float tickDelta, boolean changingFov, CallbackInfoReturnable<Double> cir) {
        if (!changingFov) return;
        double k = AstralClient.fovMultiplier(tickDelta);
        if (k != 1.0) cir.setReturnValue(cir.getReturnValue() * k);
    }
}
