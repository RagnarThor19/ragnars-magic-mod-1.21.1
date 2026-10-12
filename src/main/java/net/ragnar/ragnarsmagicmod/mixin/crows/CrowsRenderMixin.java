package net.ragnar.ragnarsmagicmod.mixin.crows;

import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.entity.Entity;
import net.ragnar.ragnarsmagicmod.crows.Crows;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Tome of Crows: while you're among the crows you're not drawn at all - not your body, shadow, armor or name. */
@Mixin(EntityRenderDispatcher.class)
public class CrowsRenderMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void ragnarsmagicmod$hideAmongCrows(E entity, Frustum frustum, double x, double y, double z,
                                                                  CallbackInfoReturnable<Boolean> cir) {
        if (Crows.isHidden(entity)) cir.setReturnValue(false);
    }
}
