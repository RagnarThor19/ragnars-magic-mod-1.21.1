package net.ragnar.ragnarsmagicmod.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.Fluid;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.registry.tag.TagKey;
import net.ragnar.ragnarsmagicmod.fireresistance.FireResistance;
import net.ragnar.ragnarsmagicmod.swimming.Swimming;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * How fast players with the Tome of Swimming move through water, and players with the Tome of Fire Resistance through
 * lava. {@code travel} pushes a swimmer along with {@code updateVelocity}: the first call is the water branch, the second
 * the lava one. Swimming up (holding jump) gets the same boost. Runs on both sides, so the client (which moves the
 * player) and the server agree.
 */
@Mixin(LivingEntity.class)
public abstract class PassiveFluidMovementMixin {
    @ModifyArg(method = "travel", index = 0, at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/entity/LivingEntity;updateVelocity(FLnet/minecraft/util/math/Vec3d;)V"))
    private float ragnarsmagicmod$swimFaster(float speed) {
        return (Object) this instanceof PlayerEntity p ? Swimming.waterSpeed(p, speed) : speed;
    }

    @ModifyArg(method = "travel", index = 0, at = @At(value = "INVOKE", ordinal = 1,
            target = "Lnet/minecraft/entity/LivingEntity;updateVelocity(FLnet/minecraft/util/math/Vec3d;)V"))
    private float ragnarsmagicmod$swimThroughLava(float speed) {
        return (Object) this instanceof PlayerEntity p ? FireResistance.lavaSpeed(p, speed) : speed;
    }

    @Inject(method = "swimUpward", at = @At("TAIL"))
    private void ragnarsmagicmod$swimUpFaster(TagKey<Fluid> fluid, CallbackInfo ci) {
        if (!((Object) this instanceof PlayerEntity p)) return;
        double extra = fluid == FluidTags.WATER ? Swimming.extraUp(p) : fluid == FluidTags.LAVA ? FireResistance.extraUp(p) : 0.0;
        if (extra > 0) p.setVelocity(p.getVelocity().add(0, extra, 0));
    }
}
