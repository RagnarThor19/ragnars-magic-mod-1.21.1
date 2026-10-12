package net.ragnar.ragnarsmagicmod.mixin.crows;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.ragnar.ragnarsmagicmod.crows.client.CrowsClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tome of Crows: from the cast until you've arrived, the movement keys do nothing. */
@Mixin(KeyboardInput.class)
public abstract class CrowsInputMixin extends Input {
    @Inject(method = "tick", at = @At("TAIL"))
    private void ragnarsmagicmod$amongCrows(boolean slowDown, float slowDownFactor, CallbackInfo ci) {
        var player = MinecraftClient.getInstance().player;
        if (player == null || !CrowsClient.rooted(player)) return;
        movementForward = 0;
        movementSideways = 0;
        pressingForward = pressingBack = pressingLeft = pressingRight = false;
        jumping = false;
        sneaking = false;
    }
}
