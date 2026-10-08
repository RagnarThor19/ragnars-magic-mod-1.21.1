package net.ragnar.ragnarsmagicmod.mixin.phantom;

import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.entity.Entity;
import net.ragnar.ragnarsmagicmod.phantom.Phantom;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Tome of the Phantom: a spectre's body isn't drawn at all (PhantomClient draws the spectre instead). */
@Mixin(EntityRenderDispatcher.class)
public class PhantomRenderMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void ragnarsmagicmod$hideSpectreBody(E entity, Frustum frustum, double x, double y, double z,
                                                                   CallbackInfoReturnable<Boolean> cir) {
        if (Phantom.isPhantom(entity)) cir.setReturnValue(false);
    }
}
