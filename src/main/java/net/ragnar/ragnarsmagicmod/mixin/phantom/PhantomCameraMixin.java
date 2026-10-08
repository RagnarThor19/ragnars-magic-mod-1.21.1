package net.ragnar.ragnarsmagicmod.mixin.phantom;

import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.ragnar.ragnarsmagicmod.phantom.Phantom;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tome of the Phantom: the camera behind a spectre passes through walls too, instead of snapping in to your back
 * every time you slip into a block.
 */
@Mixin(Camera.class)
public abstract class PhantomCameraMixin {
    @Shadow private Entity focusedEntity;

    @Inject(method = "clipToSpace", at = @At("HEAD"), cancellable = true)
    private void ragnarsmagicmod$spectreCamera(float desiredDistance, CallbackInfoReturnable<Float> cir) {
        if (focusedEntity != null && Phantom.isPhantom(focusedEntity)) cir.setReturnValue(desiredDistance);
    }
}
