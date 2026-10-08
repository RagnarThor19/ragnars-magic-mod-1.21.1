package net.ragnar.ragnarsmagicmod.mixin.phantom;

import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.ragnar.ragnarsmagicmod.phantom.Phantom;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Tome of the Phantom: a spectre floating for eight seconds isn't kicked for flying. */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class PhantomNetworkHandlerMixin {
    @Inject(method = "isEntityOnAir", at = @At("HEAD"), cancellable = true)
    private void ragnarsmagicmod$spectreFloats(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (Phantom.isPhantom(entity)) cir.setReturnValue(false);
    }
}
