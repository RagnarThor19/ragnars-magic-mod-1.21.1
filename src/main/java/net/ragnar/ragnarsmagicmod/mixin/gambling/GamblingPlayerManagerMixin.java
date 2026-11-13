package net.ragnar.ragnarsmagicmod.mixin.gambling;

import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.ragnar.ragnarsmagicmod.gambling.Gambling;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tome of Gambling: a player leaving mid-spin (quit, kick, time-out, server shutdown) gets the spin settled into their
 * inventory right here, before {@code remove} saves them - so the winnings are saved with them, and a leave can never
 * pay out twice or let a stake vanish.
 */
@Mixin(PlayerManager.class)
public class GamblingPlayerManagerMixin {
    @Inject(method = "remove", at = @At("HEAD"))
    private void ragnarsmagicmod$settleSpin(ServerPlayerEntity player, CallbackInfo ci) {
        Gambling.onPlayerLeaving(player);
    }
}
