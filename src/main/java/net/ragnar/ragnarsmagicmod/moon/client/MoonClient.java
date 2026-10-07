package net.ragnar.ragnarsmagicmod.moon.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.ragnar.ragnarsmagicmod.moon.Moon;
import net.ragnar.ragnarsmagicmod.moon.MoonEntity;

/**
 * Client half of the Tome of the Moon (see Moon): the moon's renderer, and night closing in at the edges of your
 * screen as a moon comes down near you.
 */
public final class MoonClient {
    private MoonClient() {}

    private static final Identifier VIGNETTE = Identifier.ofVanilla("textures/misc/vignette.png");
    /** How near a falling moon has to be for its night to reach you. */
    private static final double REACH = 48;

    public static void init() {
        EntityRendererRegistry.register(Moon.MOON, MoonRenderer::new);
        HudRenderCallback.EVENT.register(MoonClient::renderHud);
    }

    private static void renderHud(DrawContext context, RenderTickCounter counter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null || client.options.hudHidden) return;
        float dark = 0f;
        for (Entity e : client.world.getEntities()) {
            if (!(e instanceof MoonEntity moon) || moon.stage() == MoonEntity.AFTERGLOW) continue;
            double d = moon.ground().distanceTo(client.player.getPos());
            if (d > REACH) continue;
            float near = (float) (1.0 - d / REACH);
            float progress = moon.stage() == MoonEntity.FORMING ? 0.25f
                    : 0.25f + 0.75f * MathHelper.clamp(moon.clientStageAge / (float) MoonEntity.FALL_TICKS, 0f, 1f);
            dark = Math.max(dark, near * progress);
        }
        if (dark <= 0.01f) return;

        // Darken the edges, more in red and green than blue: a cold blue night creeping in
        int w = context.getScaledWindowWidth(), h = context.getScaledWindowHeight();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SrcFactor.ZERO, GlStateManager.DstFactor.ONE_MINUS_SRC_COLOR);
        context.setShaderColor(0.85f * dark, 0.75f * dark, 0.45f * dark, 1f);
        context.drawTexture(VIGNETTE, 0, 0, -90, 0f, 0f, w, h, w, h);
        context.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }
}
