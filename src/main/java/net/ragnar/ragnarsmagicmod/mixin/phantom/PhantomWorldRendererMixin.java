package net.ragnar.ragnarsmagicmod.mixin.phantom;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.WorldRenderer;
import net.ragnar.ragnarsmagicmod.phantom.Phantom;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Tome of the Phantom: with the camera inside solid blocks, the world is drawn the way it is for a spectator, so you
 * see out through the stone rather than the inside of it.
 */
@Mixin(WorldRenderer.class)
public class PhantomWorldRendererMixin {
    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/ClientPlayerEntity;isSpectator()Z"))
    private boolean ragnarsmagicmod$spectreSight(ClientPlayerEntity player, Operation<Boolean> original) {
        return original.call(player) || Phantom.isPhantom(player);
    }
}
