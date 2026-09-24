package net.ragnar.ragnarsmagicmod.mixin.client;

import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.ragnar.ragnarsmagicmod.client.PossessionClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While possessing a mob, the movement keys steer it and your own body stays put. */
@Mixin(KeyboardInput.class)
public class KeyboardInputMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void ragnarsmagicmod$steerHost(boolean slowDown, float slowDownFactor, CallbackInfo ci) {
        PossessionClient.captureInput((Input) (Object) this);
    }
}
