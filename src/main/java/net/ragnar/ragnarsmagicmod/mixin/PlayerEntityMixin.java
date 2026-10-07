package net.ragnar.ragnarsmagicmod.mixin;

import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.player.PlayerEntity;
import net.ragnar.ragnarsmagicmod.util.FairyForm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A Tome of the Fairy fairy has a tiny hitbox, whatever pose it's in. A Tome of the Lightning Path runner moves
 * further and through more than the server's movement checks would allow, so for the length of the run the server
 * treats them as passing through blocks (their own client keeps them out of them).
 */
@Mixin(PlayerEntity.class)
public class PlayerEntityMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void ragnarsmagicmod$lightningRunner(CallbackInfo ci) {
        PlayerEntity self = (PlayerEntity) (Object) this;
        if (!self.getWorld().isClient && net.ragnar.ragnarsmagicmod.lightningpath.LightningPath.isRunning(self)) self.noClip = true;
    }

    @Inject(method = "getBaseDimensions", at = @At("HEAD"), cancellable = true)
    private void ragnarsmagicmod$fairySize(EntityPose pose, CallbackInfoReturnable<EntityDimensions> cir) {
        if (FairyForm.isFairy((PlayerEntity) (Object) this)) cir.setReturnValue(FairyForm.DIMENSIONS);
    }
}
