package net.ragnar.ragnarsmagicmod.mixin.astral;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.ragnar.ragnarsmagicmod.astral.client.AstralClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tome of Astral Projection: while your spirit is out, your body can't hit, mine or use anything (casting the tome
 * again is the one exception - that's how you go back), and living things near the spirit glow through the walls.
 */
@Mixin(MinecraftClient.class)
public class AstralClientMixin {
    @Inject(method = "hasOutline", at = @At("HEAD"), cancellable = true)
    private void ragnarsmagicmod$spiritSense(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (AstralClient.hasOutline(entity)) cir.setReturnValue(true);
    }

    @Inject(method = "doAttack", at = @At("HEAD"), cancellable = true)
    private void ragnarsmagicmod$spiritCantHit(CallbackInfoReturnable<Boolean> cir) {
        if (AstralClient.isActive()) cir.setReturnValue(false);
    }

    @Inject(method = "doItemUse", at = @At("HEAD"), cancellable = true)
    private void ragnarsmagicmod$spiritCantUse(CallbackInfo ci) {
        if (AstralClient.isActive() && !AstralClient.allowItemUse()) ci.cancel();
    }

    @Inject(method = "handleBlockBreaking", at = @At("HEAD"), cancellable = true)
    private void ragnarsmagicmod$spiritCantMine(boolean breaking, CallbackInfo ci) {
        if (AstralClient.isActive()) ci.cancel();
    }
}
