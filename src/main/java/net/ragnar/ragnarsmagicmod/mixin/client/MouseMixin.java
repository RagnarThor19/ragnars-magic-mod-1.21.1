package net.ragnar.ragnarsmagicmod.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.ragnar.ragnarsmagicmod.client.SpellSwitcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public class MouseMixin {
    @Shadow @Final private MinecraftClient client;

    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    private void ragnarsmagicmod$scrollSpells(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (window == client.getWindow().getHandle() && SpellSwitcher.onScroll(vertical)) {
            ci.cancel();
        }
    }
}
