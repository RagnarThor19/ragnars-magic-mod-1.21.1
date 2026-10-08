package net.ragnar.ragnarsmagicmod.mixin.phantom;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.phantom.Phantom;
import net.ragnar.ragnarsmagicmod.phantom.client.PhantomClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tome of the Phantom: as a spectre, your own movement is spectre flight instead of walking. */
@Mixin(PlayerEntity.class)
public class PhantomTravelMixin {
    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void ragnarsmagicmod$spectreFlight(Vec3d movementInput, CallbackInfo ci) {
        if ((Object) this instanceof ClientPlayerEntity player && player == MinecraftClient.getInstance().player
                && Phantom.isPhantom(player)) {
            PhantomClient.travel(player);
            ci.cancel();
        }
    }
}
