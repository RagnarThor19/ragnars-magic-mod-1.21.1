package net.ragnar.ragnarsmagicmod.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.ragnar.ragnarsmagicmod.gourmet.Gourmet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Tome of the Gourmet: food takes a single tick to eat, so it's eaten the moment you right-click it. */
@Mixin(ItemStack.class)
public abstract class GourmetItemStackMixin {
    @Inject(method = "getMaxUseTime", at = @At("RETURN"), cancellable = true)
    private void ragnarsmagicmod$eatInstantly(LivingEntity user, CallbackInfoReturnable<Integer> cir) {
        if (cir.getReturnValueI() > 1 && Gourmet.instant((ItemStack) (Object) this, user)) cir.setReturnValue(1);
    }
}
