package net.ragnar.ragnarsmagicmod.mixin.client;

import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.entity.Entity;
import net.ragnar.ragnarsmagicmod.client.PossessionClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The body of a player possessing a mob is completely hidden: no armor, held items, name or shadow. */
@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void ragnarsmagicmod$hideEmptyBody(E entity, Frustum frustum, double x, double y, double z,
                                                                 CallbackInfoReturnable<Boolean> cir) {
        if (PossessionClient.isHidden(entity)) cir.setReturnValue(false);
    }
}
