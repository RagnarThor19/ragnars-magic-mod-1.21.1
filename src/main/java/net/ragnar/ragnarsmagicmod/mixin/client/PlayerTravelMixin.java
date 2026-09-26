package net.ragnar.ragnarsmagicmod.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.client.FairyClient;
import net.ragnar.ragnarsmagicmod.util.FairyForm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** As a Tome of the Fairy fairy, your own movement follows fairy flight instead of walking. */
@Mixin(PlayerEntity.class)
public class PlayerTravelMixin {
    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void ragnarsmagicmod$fairyFlight(Vec3d movementInput, CallbackInfo ci) {
        if ((Object) this instanceof ClientPlayerEntity player && player == MinecraftClient.getInstance().player
                && FairyForm.isFairy(player)) {
            FairyClient.travel(player);
            ci.cancel();
        }
    }
}
