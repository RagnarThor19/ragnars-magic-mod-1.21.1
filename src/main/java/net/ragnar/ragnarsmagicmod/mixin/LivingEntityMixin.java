package net.ragnar.ragnarsmagicmod.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.ragnar.ragnarsmagicmod.item.spell.ControllingSpell;
import net.ragnar.ragnarsmagicmod.item.spell.IllusionSpell;
import net.ragnar.ragnarsmagicmod.util.WingsState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Nothing can pick the empty body of a possessing player, or a Tome of Illusion ghost, as a target.
 * Tome of Wings fliers glide without an elytra.
 */
@Mixin(LivingEntity.class)
public class LivingEntityMixin {
    @Inject(method = "canTarget(Lnet/minecraft/entity/LivingEntity;)Z", at = @At("HEAD"), cancellable = true)
    private void ragnarsmagicmod$ignoreEmptyBody(LivingEntity target, CallbackInfoReturnable<Boolean> cir) {
        if (ControllingSpell.isPossessing(target) || IllusionSpell.isGhost(target)) cir.setReturnValue(false);
    }

    /** Vanilla stops any glide without an elytra on the chest; wings from the Tome of Wings count as one. */
    @Inject(method = "tickFallFlying", at = @At("HEAD"), cancellable = true)
    private void ragnarsmagicmod$wingsGlide(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!(self instanceof PlayerEntity player) || !WingsState.hasWings(player)) return;
        if (player.isOnGround() || player.hasVehicle() || player.isTouchingWater()
                || player.hasStatusEffect(StatusEffects.LEVITATION) || player.getAbilities().flying) return;
        player.startFallFlying();
        ci.cancel();
    }
}
