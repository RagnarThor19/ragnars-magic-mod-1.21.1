package net.ragnar.ragnarsmagicmod.mixin.upsidedown;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.ragnar.ragnarsmagicmod.upsidedown.client.UpsideDownClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Tome of Upside Down: with the view rolled over, the mouse turns and tilts it the way it looks like it should. */
@Mixin(Entity.class)
public abstract class UpsideDownLookMixin {
    private boolean ragnarsmagicmod$flippedView() {
        return (Object) this == MinecraftClient.getInstance().player && UpsideDownClient.controlsFlipped();
    }

    @ModifyVariable(method = "changeLookDirection", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private double ragnarsmagicmod$flipYaw(double cursorDeltaX) {
        return ragnarsmagicmod$flippedView() ? -cursorDeltaX : cursorDeltaX;
    }

    @ModifyVariable(method = "changeLookDirection", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private double ragnarsmagicmod$flipPitch(double cursorDeltaY) {
        return ragnarsmagicmod$flippedView() ? -cursorDeltaY : cursorDeltaY;
    }
}
