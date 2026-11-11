package net.ragnar.ragnarsmagicmod.mixin.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import net.ragnar.ragnarsmagicmod.client.SpellHud;
import net.ragnar.ragnarsmagicmod.leaping.client.LeapingClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The spell name uses the same spot as the held item name, so hide the vanilla one while it shows. And the Tome of
 * Leaping's wind-up bar stands in for the XP bar (and its level number) while it's showing, like a horse's jump bar.
 */
@Mixin(InGameHud.class)
public class InGameHudMixin {
    @Inject(method = "renderExperienceBar", at = @At("HEAD"), cancellable = true)
    private void ragnarsmagicmod$leapBar(DrawContext context, int x, CallbackInfo ci) {
        if (LeapingClient.showing()) {
            LeapingClient.drawBar(context, x);
            ci.cancel();
        }
    }

    @Inject(method = "renderExperienceLevel", at = @At("HEAD"), cancellable = true)
    private void ragnarsmagicmod$noLevelOverLeapBar(DrawContext context, RenderTickCounter counter, CallbackInfo ci) {
        if (LeapingClient.showing()) ci.cancel();
    }

    @Inject(method = "renderHeldItemTooltip", at = @At("HEAD"), cancellable = true)
    private void ragnarsmagicmod$yieldToSpellName(DrawContext context, CallbackInfo ci) {
        if (SpellHud.isShowingSpellName()) ci.cancel();
    }
}
