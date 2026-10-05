package net.ragnar.ragnarsmagicmod.mixin.upsidedown;

import net.minecraft.entity.Entity;
import net.minecraft.entity.MovementType;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.upsidedown.UpsideDown;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tome of Upside Down, for a flipped player: gravity pulls up, the ceiling counts as ground, falls are measured
 * upward, the eyes sit at the other end of the body (so the view is a head's height below the ceiling), and the sky
 * far above the world hurts like the void below it.
 */
@Mixin(Entity.class)
public abstract class UpsideDownEntityMixin {
    @Shadow private float standingEyeHeight;
    @Shadow private Vec3d pos;
    @Shadow public boolean verticalCollision;
    @Shadow public boolean groundCollision;

    @Shadow public abstract float getHeight();
    @Shadow public abstract double getY();
    @Shadow public abstract World getWorld();
    @Shadow protected abstract void tickInVoid();

    private boolean ragnarsmagicmod$flipped() {
        return UpsideDown.isFlipped((Entity) (Object) this);
    }

    @Inject(method = "getStandingEyeHeight", at = @At("HEAD"), cancellable = true)
    private void ragnarsmagicmod$flipEyes(CallbackInfoReturnable<Float> cir) {
        if (ragnarsmagicmod$flipped()) cir.setReturnValue(getHeight() - standingEyeHeight);
    }

    @Inject(method = "getEyeY", at = @At("HEAD"), cancellable = true)
    private void ragnarsmagicmod$flipEyeY(CallbackInfoReturnable<Double> cir) {
        if (ragnarsmagicmod$flipped()) cir.setReturnValue(pos.y + getHeight() - standingEyeHeight);
    }

    @Inject(method = "getFinalGravity", at = @At("RETURN"), cancellable = true)
    private void ragnarsmagicmod$flipGravity(CallbackInfoReturnable<Double> cir) {
        if (ragnarsmagicmod$flipped()) cir.setReturnValue(-cir.getReturnValueD());
    }

    /** Standing is bumping into something while moving up, not down. */
    @Inject(method = "move", at = @At(value = "FIELD", target = "Lnet/minecraft/entity/Entity;groundCollision:Z",
            opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER))
    private void ragnarsmagicmod$flipGround(MovementType type, Vec3d movement, CallbackInfo ci) {
        if (ragnarsmagicmod$flipped()) groundCollision = verticalCollision && movement.y > 0.0;
    }

    /** Falling is moving up: that's what builds up fall distance (and fall damage) now. */
    @ModifyVariable(method = "fall", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private double ragnarsmagicmod$flipFall(double heightDifference) {
        return ragnarsmagicmod$flipped() ? -heightDifference : heightDifference;
    }

    @Inject(method = "attemptTickInVoid", at = @At("TAIL"))
    private void ragnarsmagicmod$skyVoid(CallbackInfo ci) {
        if (ragnarsmagicmod$flipped() && getY() > getWorld().getTopY() + UpsideDown.SKY_VOID) tickInVoid();
    }
}
