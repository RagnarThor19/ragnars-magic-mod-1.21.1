package net.ragnar.ragnarsmagicmod.mixin.upsidedown;

import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.ragnar.ragnarsmagicmod.upsidedown.UpsideDown;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Tome of Upside Down: falling up into the sky isn't flying, so a flipped player isn't kicked for it. */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class UpsideDownNetworkHandlerMixin {
    @Inject(method = "isEntityOnAir", at = @At("HEAD"), cancellable = true)
    private void ragnarsmagicmod$notFlying(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (UpsideDown.isFlipped(entity)) cir.setReturnValue(false);
    }
}
