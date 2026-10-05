package net.ragnar.ragnarsmagicmod.mixin.upsidedown;

import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.ragnar.ragnarsmagicmod.upsidedown.client.UpsideDownClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tome of Upside Down: with the view rolled over, A and D still strafe left and right as it looks. */
@Mixin(KeyboardInput.class)
public abstract class UpsideDownInputMixin extends Input {
    @Inject(method = "tick", at = @At("TAIL"))
    private void ragnarsmagicmod$flipStrafe(boolean slowDown, float slowDownFactor, CallbackInfo ci) {
        if (UpsideDownClient.controlsFlipped()) movementSideways = -movementSideways;
    }
}
