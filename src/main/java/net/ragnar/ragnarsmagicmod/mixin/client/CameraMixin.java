package net.ragnar.ragnarsmagicmod.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import net.ragnar.ragnarsmagicmod.client.DayShiftClient;
import net.ragnar.ragnarsmagicmod.client.DimensionSplitClient;
import net.ragnar.ragnarsmagicmod.client.PossessionClient;
import net.ragnar.ragnarsmagicmod.client.ScreenShake;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While possessing a mob, the view turns with your mouse right away instead of waiting on the server.
 * Also shakes the camera for the Tome of Dimension Split and the Tome of Dusk and Dawn.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow protected abstract void setRotation(float yaw, float pitch);
    @Shadow protected abstract void setPos(Vec3d pos);
    @Shadow public abstract Vec3d getPos();
    @Shadow public abstract float getYaw();
    @Shadow public abstract float getPitch();

    @Inject(method = "update", at = @At("TAIL"))
    private void ragnarsmagicmod$splitShake(BlockView area, Entity focusedEntity, boolean thirdPerson, boolean inverseView,
                                             float tickDelta, CallbackInfo ci) {
        float k = Math.max(Math.max(DimensionSplitClient.shake(tickDelta), DayShiftClient.shake(tickDelta)),
                ScreenShake.current(tickDelta));
        if (k <= 0f || focusedEntity == null) return;
        float t = (focusedEntity.age + tickDelta) * 2.7f;
        float yaw = k * 2.4f * (MathHelper.sin(t * 1.9f) + 0.5f * MathHelper.sin(t * 4.7f + 1.3f));
        float pitch = k * 2.0f * (MathHelper.cos(t * 2.3f) + 0.5f * MathHelper.sin(t * 5.1f + 0.4f));
        setRotation(getYaw() + yaw, MathHelper.clamp(getPitch() + pitch, -90f, 90f));
        double j = k * 0.12;
        setPos(getPos().add(MathHelper.sin(t * 3.3f) * j, MathHelper.cos(t * 3.9f) * j, MathHelper.sin(t * 2.9f + 2f) * j));
    }

    @WrapOperation(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;getYaw(F)F"))
    private float ragnarsmagicmod$possessedYaw(Entity entity, float tickDelta, Operation<Float> original) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && PossessionClient.isHost(entity)) return client.player.getYaw(tickDelta);
        return original.call(entity, tickDelta);
    }

    @WrapOperation(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;getPitch(F)F"))
    private float ragnarsmagicmod$possessedPitch(Entity entity, float tickDelta, Operation<Float> original) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && PossessionClient.isHost(entity)) return client.player.getPitch(tickDelta);
        return original.call(entity, tickDelta);
    }
}
