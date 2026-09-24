package net.ragnar.ragnarsmagicmod.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.ragnar.ragnarsmagicmod.client.PossessionClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** While possessing a mob, the view turns with your mouse right away instead of waiting on the server. */
@Mixin(Camera.class)
public class CameraMixin {
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
