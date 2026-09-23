package net.ragnar.ragnarsmagicmod.mixin.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.ragnar.ragnarsmagicmod.client.SpellHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The spell name uses the same spot as the held item name, so hide the vanilla one while it shows. */
@Mixin(InGameHud.class)
public class InGameHudMixin {
    @Inject(method = "renderHeldItemTooltip", at = @At("HEAD"), cancellable = true)
    private void ragnarsmagicmod$yieldToSpellName(DrawContext context, CallbackInfo ci) {
        if (SpellHud.isShowingSpellName()) ci.cancel();
    }
}
