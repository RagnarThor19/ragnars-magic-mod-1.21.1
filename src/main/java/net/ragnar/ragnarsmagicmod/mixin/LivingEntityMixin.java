package net.ragnar.ragnarsmagicmod.mixin;

import net.minecraft.entity.LivingEntity;
import net.ragnar.ragnarsmagicmod.item.spell.ControllingSpell;
import net.ragnar.ragnarsmagicmod.item.spell.IllusionSpell;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Nothing can pick the empty body of a possessing player, or a Tome of Illusion ghost, as a target. */
@Mixin(LivingEntity.class)
public class LivingEntityMixin {
    @Inject(method = "canTarget(Lnet/minecraft/entity/LivingEntity;)Z", at = @At("HEAD"), cancellable = true)
    private void ragnarsmagicmod$ignoreEmptyBody(LivingEntity target, CallbackInfoReturnable<Boolean> cir) {
        if (ControllingSpell.isPossessing(target) || IllusionSpell.isGhost(target)) cir.setReturnValue(false);
    }
}
