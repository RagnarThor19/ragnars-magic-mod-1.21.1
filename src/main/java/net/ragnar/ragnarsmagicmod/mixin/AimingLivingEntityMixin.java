package net.ragnar.ragnarsmagicmod.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.ragnar.ragnarsmagicmod.aiming.Aiming;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tome of Aiming: extra ticks of drawing while a bow or crossbow is held drawn (see Aiming). */
@Mixin(LivingEntity.class)
public abstract class AimingLivingEntityMixin {
    @Shadow protected int itemUseTimeLeft;
    @Shadow protected ItemStack activeItemStack;

    @Shadow public abstract boolean isUsingItem();

    /** Real ticks this draw has been held, and the extra ticks already added, counted on each side alike. */
    @Unique private int ragnarsmagicmod$drawTicks, ragnarsmagicmod$extraTicks;

    @Inject(method = "tickActiveItemStack", at = @At("TAIL"))
    private void ragnarsmagicmod$drawFaster(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        float speed = isUsingItem() ? Aiming.speed(self, activeItemStack) : 1f;
        if (speed <= 1f) {
            ragnarsmagicmod$drawTicks = 0;
            ragnarsmagicmod$extraTicks = 0;
            return;
        }
        // Worked out from the whole draw so far, so it never drifts: after n ticks, n * speed ticks drawn in all
        int owed = (int) (++ragnarsmagicmod$drawTicks * (speed - 1f)) - ragnarsmagicmod$extraTicks;
        for (; owed > 0 && itemUseTimeLeft > 1; owed--) { // never down to the last tick, where items finish on their own
            itemUseTimeLeft--;
            ragnarsmagicmod$extraTicks++;
        }
    }
}
