package net.ragnar.ragnarsmagicmod.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.ragnar.ragnarsmagicmod.groundpound.GroundPound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Tome of Ground Pound: a pounding player's fall hurts half as much (server side, where fall damage is dealt). */
@Mixin(LivingEntity.class)
public abstract class GroundPoundFallMixin {
    @Inject(method = "computeFallDamage", at = @At("RETURN"), cancellable = true)
    private void ragnarsmagicmod$halveFall(float fallDistance, float damageMultiplier, CallbackInfoReturnable<Integer> cir) {
        if ((Object) this instanceof PlayerEntity p && !p.getWorld().isClient && cir.getReturnValueI() > 0 && GroundPound.isPounding(p)) {
            cir.setReturnValue((int) Math.floor(cir.getReturnValueI() * (1f - GroundPound.FALL_REDUCTION)));
        }
    }
}
