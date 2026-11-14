package net.ragnar.ragnarsmagicmod.gametest.mixin;

import net.minecraft.network.packet.Packet;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.ragnar.ragnarsmagicmod.gametest.ParticleBenchmark;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Test-only: counts every particle packet the server actually sends a player (for ParticleBenchmark). */
@Mixin(ServerWorld.class)
public abstract class ParticleCountMixin {
    @Inject(method = "sendToPlayerIfNearby", at = @At("RETURN"))
    private void ragnarsmagicmodtest$count(ServerPlayerEntity player, boolean force, double x, double y, double z,
                                           Packet<?> packet, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) ParticleBenchmark.packets++;
    }
}
