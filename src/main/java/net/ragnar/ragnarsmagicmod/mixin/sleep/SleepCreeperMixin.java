package net.ragnar.ragnarsmagicmod.mixin.sleep;

import net.minecraft.entity.mob.CreeperEntity;
import net.ragnar.ragnarsmagicmod.sleep.Sleep;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tome of Sleep Darts / Sleep Potions: a sleeping creeper can't go off, even one lit with flint and steel. */
@Mixin(CreeperEntity.class)
public abstract class SleepCreeperMixin {
    @Inject(method = "explode", at = @At("HEAD"), cancellable = true)
    private void ragnarsmagicmod$asleep(CallbackInfo ci) {
        if (Sleep.isAsleep((CreeperEntity) (Object) this)) ci.cancel();
    }
}
