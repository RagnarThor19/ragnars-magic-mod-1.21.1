package net.ragnar.ragnarsmagicmod.mixin;

import net.minecraft.entity.Entity;
import net.ragnar.ragnarsmagicmod.util.AbsoluteInvisibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Tome of Invisibility: no dust kicked up when sprinting, so nothing gives you away. */
@Mixin(Entity.class)
public class EntityMixin {
    @Inject(method = "shouldSpawnSprintingParticles", at = @At("HEAD"), cancellable = true)
    private void ragnarsmagicmod$noInvisibleDust(CallbackInfoReturnable<Boolean> cir) {
        if (AbsoluteInvisibility.isHidden((Entity) (Object) this)) cir.setReturnValue(false);
    }
}
