package net.ragnar.ragnarsmagicmod.mixin.astral;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.entity.Entity;
import net.ragnar.ragnarsmagicmod.astral.client.AstralClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Tome of Astral Projection: with the spirit inside solid blocks, the world is drawn the way it is for a spectator, so
 * you see out through the stone; and the living things you sense glow in their own colours.
 */
@Mixin(WorldRenderer.class)
public class AstralWorldRendererMixin {
    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/ClientPlayerEntity;isSpectator()Z"))
    private boolean ragnarsmagicmod$spiritSight(ClientPlayerEntity player, Operation<Boolean> original) {
        return original.call(player) || AstralClient.isActive();
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;getTeamColorValue()I"))
    private int ragnarsmagicmod$spiritSenseColor(Entity entity, Operation<Integer> original) {
        int color = AstralClient.outlineColor(entity);
        return color >= 0 ? color : original.call(entity);
    }
}
