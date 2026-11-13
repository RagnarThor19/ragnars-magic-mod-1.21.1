package net.ragnar.ragnarsmagicmod.mixin.astral;

import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.ragnar.ragnarsmagicmod.astral.client.AstralClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tome of Astral Projection: the movement keys steer your spirit, and your body stays where it is. */
@Mixin(KeyboardInput.class)
public class AstralInputMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void ragnarsmagicmod$steerSpirit(boolean slowDown, float slowDownFactor, CallbackInfo ci) {
        AstralClient.captureInput((Input) (Object) this);
    }
}
