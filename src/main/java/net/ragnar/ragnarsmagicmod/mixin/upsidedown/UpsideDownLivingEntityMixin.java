package net.ragnar.ragnarsmagicmod.mixin.upsidedown;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.upsidedown.UpsideDown;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tome of Upside Down: a flipped player jumps "up" off the ceiling - which is down. */
@Mixin(LivingEntity.class)
public abstract class UpsideDownLivingEntityMixin {
    @Inject(method = "jump", at = @At("TAIL"))
    private void ragnarsmagicmod$flipJump(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (!UpsideDown.isFlipped(self)) return;
        Vec3d v = self.getVelocity();
        self.setVelocity(v.x, -v.y, v.z);
    }
}
