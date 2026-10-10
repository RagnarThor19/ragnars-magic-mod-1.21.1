package net.ragnar.ragnarsmagicmod.mixin.sleep;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.ragnar.ragnarsmagicmod.sleep.Sleep;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tome of Sleep Darts / Sleep Potions: while you're asleep the mouse can't turn your head (it hangs on its own). */
@Mixin(Entity.class)
public abstract class SleepLookMixin {
    @Inject(method = "changeLookDirection", at = @At("HEAD"), cancellable = true)
    private void ragnarsmagicmod$asleep(double cursorDeltaX, double cursorDeltaY, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self == MinecraftClient.getInstance().player && Sleep.isAsleep(self)) ci.cancel();
    }
}
