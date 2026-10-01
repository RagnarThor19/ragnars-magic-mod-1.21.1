package net.ragnar.ragnarsmagicmod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.block.Block;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.util.AbsoluteInvisibility;
import net.ragnar.ragnarsmagicmod.util.Slippery;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.ragnar.ragnarsmagicmod.item.spell.ControllingSpell;
import net.ragnar.ragnarsmagicmod.item.spell.IllusionSpell;
import net.ragnar.ragnarsmagicmod.item.spell.RewindSpell;
import net.ragnar.ragnarsmagicmod.util.WingsState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Nothing can pick the empty body of a possessing player, or a Tome of Illusion ghost, as a target.
 * Tome of Wings fliers glide without an elytra. Tome of Slipperiness targets slide around.
 */
@Mixin(LivingEntity.class)
public class LivingEntityMixin {
    /** Tome of Invisibility: armor doesn't give you away, mobs spot you as if you wore nothing. */
    @Inject(method = "getAttackDistanceScalingFactor", at = @At("RETURN"), cancellable = true)
    private void ragnarsmagicmod$unseenArmor(net.minecraft.entity.Entity looker, CallbackInfoReturnable<Double> cir) {
        if (AbsoluteInvisibility.isHidden((LivingEntity) (Object) this)) cir.setReturnValue(Math.min(cir.getReturnValue(), 0.07));
    }

    @Inject(method = "canTarget(Lnet/minecraft/entity/LivingEntity;)Z", at = @At("HEAD"), cancellable = true)
    private void ragnarsmagicmod$ignoreEmptyBody(LivingEntity target, CallbackInfoReturnable<Boolean> cir) {
        if (ControllingSpell.isPossessing(target) || IllusionSpell.isGhost(target)) cir.setReturnValue(false);
    }

    /** Nothing hurts you mid-rewind (your health is being wound back anyway) - except the void and /kill. */
    @Inject(method = "damage", at = @At("HEAD"), cancellable = true)
    private void ragnarsmagicmod$rewindImmunity(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof PlayerEntity player && RewindSpell.isRewinding(player)
                && !source.isIn(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            cir.setReturnValue(false);
        }
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

    /** Tome of Slipperiness: the ground under you feels like it's greased, whatever it is. */
    @WrapOperation(method = "travel", at = @At(value = "INVOKE", target = "Lnet/minecraft/block/Block;getSlipperiness()F"))
    private float ragnarsmagicmod$slipperyGround(Block block, Operation<Float> original) {
        float slipperiness = original.call(block);
        return Slippery.isSlippery((LivingEntity) (Object) this) ? Math.max(slipperiness, Slippery.SLIPPERINESS) : slipperiness;
    }

    /** ...and your feet barely grip it, so you speed up, turn and stop very slowly. */
    @WrapOperation(method = "travel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/LivingEntity;applyMovementInput(Lnet/minecraft/util/math/Vec3d;F)Lnet/minecraft/util/math/Vec3d;"))
    private Vec3d ragnarsmagicmod$slipperyGrip(LivingEntity self, Vec3d movementInput, float slipperiness, Operation<Vec3d> original) {
        if (self.isOnGround() && Slippery.isSlippery(self)) movementInput = movementInput.multiply(Slippery.INPUT_SCALE);
        return original.call(self, movementInput, slipperiness);
    }
}
