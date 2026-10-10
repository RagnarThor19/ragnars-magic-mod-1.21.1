package net.ragnar.ragnarsmagicmod.mixin.sleep;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.ragnar.ragnarsmagicmod.sleep.Sleep;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tome of Sleep Darts / Sleep Potions: while you're asleep the movement keys do nothing. */
@Mixin(KeyboardInput.class)
public abstract class SleepInputMixin extends Input {
    @Inject(method = "tick", at = @At("TAIL"))
    private void ragnarsmagicmod$asleep(boolean slowDown, float slowDownFactor, CallbackInfo ci) {
        var player = MinecraftClient.getInstance().player;
        if (player == null || !Sleep.isAsleep(player)) return;
        movementForward = 0;
        movementSideways = 0;
        pressingForward = pressingBack = pressingLeft = pressingRight = false;
        jumping = false;
        sneaking = false;
    }
}
