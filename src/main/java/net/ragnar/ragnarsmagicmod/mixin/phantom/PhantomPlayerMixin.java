package net.ragnar.ragnarsmagicmod.mixin.phantom;

import net.minecraft.entity.player.PlayerEntity;
import net.ragnar.ragnarsmagicmod.phantom.Phantom;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tome of the Phantom: a spectre passes through blocks on both sides. Set straight after the player's tick resets it,
 * so it's in place before the client would push you out of a block, and stays for the server's movement checks.
 */
@Mixin(PlayerEntity.class)
public class PhantomPlayerMixin {
    @Inject(method = "tick", at = @At(value = "FIELD", target = "Lnet/minecraft/entity/player/PlayerEntity;noClip:Z",
            opcode = org.objectweb.asm.Opcodes.PUTFIELD, ordinal = 0, shift = At.Shift.AFTER))
    private void ragnarsmagicmod$phantomNoClip(CallbackInfo ci) {
        PlayerEntity self = (PlayerEntity) (Object) this;
        if (Phantom.isPhantom(self)) self.noClip = true;
    }
}
