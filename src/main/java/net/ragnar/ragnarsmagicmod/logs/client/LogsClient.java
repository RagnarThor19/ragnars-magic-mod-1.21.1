package net.ragnar.ragnarsmagicmod.logs.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.Identifier;
import net.ragnar.ragnarsmagicmod.client.CameraGlide;
import net.ragnar.ragnarsmagicmod.logs.Logs;

/**
 * Client half of the Tome of Logs (see Logs): when you're substituted, the camera whips round from where you were to
 * where you come out behind them, and the screen flashes white from the edges like you've come out of the smoke.
 */
public final class LogsClient {
    private LogsClient() {}

    private static final Identifier VIGNETTE = Identifier.ofVanilla("textures/misc/vignette.png");
    private static final int FLASH_TICKS = 14;
    private static int flash;

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(Logs.SwapPayload.ID, (payload, context) -> {
            CameraGlide.start(5);
            flash = FLASH_TICKS;
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> flash = 0);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (flash > 0 && !client.isPaused()) flash--;
        });
        HudRenderCallback.EVENT.register(LogsClient::renderHud);
    }

    private static void renderHud(DrawContext context, RenderTickCounter counter) {
        if (flash <= 0) return;
        float s = (flash - counter.getTickDelta(false)) / FLASH_TICKS;
        if (s <= 0f) return;
        float a = s * s;
        int w = context.getScaledWindowWidth();
        int h = context.getScaledWindowHeight();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(com.mojang.blaze3d.platform.GlStateManager.SrcFactor.ONE,
                com.mojang.blaze3d.platform.GlStateManager.DstFactor.ONE);
        context.setShaderColor(0.9f * a, 0.9f * a, 0.9f * a, 1f);
        context.drawTexture(VIGNETTE, 0, 0, -90, 0f, 0f, w, h, w, h);
        context.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }
}
