package net.ragnar.ragnarsmagicmod.mixin.sleep;

import net.minecraft.entity.mob.MobEntity;
import net.ragnar.ragnarsmagicmod.sleep.Sleep;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tome of Sleep Darts / Sleep Potions: a sleeping mob doesn't think - no goals, no brain, no looking around. */
@Mixin(MobEntity.class)
public abstract class SleepMobMixin {
    @Inject(method = "tickNewAi", at = @At("HEAD"), cancellable = true)
    private void ragnarsmagicmod$asleep(CallbackInfo ci) {
        if (Sleep.isAsleep((MobEntity) (Object) this)) ci.cancel();
    }
}
