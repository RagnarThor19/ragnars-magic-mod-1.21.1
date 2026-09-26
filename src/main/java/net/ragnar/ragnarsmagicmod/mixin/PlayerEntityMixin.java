package net.ragnar.ragnarsmagicmod.mixin;

import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.player.PlayerEntity;
import net.ragnar.ragnarsmagicmod.util.FairyForm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A Tome of the Fairy fairy has a tiny hitbox, whatever pose it's in. */
@Mixin(PlayerEntity.class)
public class PlayerEntityMixin {
    @Inject(method = "getBaseDimensions", at = @At("HEAD"), cancellable = true)
    private void ragnarsmagicmod$fairySize(EntityPose pose, CallbackInfoReturnable<EntityDimensions> cir) {
        if (FairyForm.isFairy((PlayerEntity) (Object) this)) cir.setReturnValue(FairyForm.DIMENSIONS);
    }
}
