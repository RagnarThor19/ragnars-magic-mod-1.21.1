package net.ragnar.ragnarsmagicmod.mixin.upsidedown;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.ragnar.ragnarsmagicmod.upsidedown.client.UpsideDownClient;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tome of Upside Down: rolls the local player's view over, so the ceiling they stand on looks like the floor. */
@Mixin(Camera.class)
public abstract class UpsideDownCameraMixin {
    @Shadow private Entity focusedEntity;
    @Shadow @Final private Quaternionf rotation;
    @Shadow @Final private Vector3f horizontalPlane;
    @Shadow @Final private Vector3f verticalPlane;
    @Shadow @Final private Vector3f diagonalPlane;
    @Shadow @Final private static Vector3f HORIZONTAL;
    @Shadow @Final private static Vector3f VERTICAL;
    @Shadow @Final private static Vector3f DIAGONAL;

    @Inject(method = "setRotation", at = @At("TAIL"))
    private void ragnarsmagicmod$roll(float yaw, float pitch, CallbackInfo ci) {
        if (focusedEntity == null || focusedEntity != MinecraftClient.getInstance().player) return;
        float roll = UpsideDownClient.roll();
        if (roll <= 0f) return;
        rotation.rotateZ((float) Math.PI * roll);
        HORIZONTAL.rotate(rotation, horizontalPlane);
        VERTICAL.rotate(rotation, verticalPlane);
        DIAGONAL.rotate(rotation, diagonalPlane);
    }
}
