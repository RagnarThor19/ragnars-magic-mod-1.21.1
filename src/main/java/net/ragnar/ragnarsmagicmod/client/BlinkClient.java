package net.ragnar.ragnarsmagicmod.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.Identifier;
import net.ragnar.ragnarsmagicmod.network.BlinkPayload;

/** Tome of Blinking, client side: the camera zips to where you land, and the edges of the screen flash End-purple. */
public final class BlinkClient {
    private BlinkClient() {}

    private static final Identifier VIGNETTE = Identifier.ofVanilla("textures/misc/vignette.png");
    private static final int ZIP_TICKS = 4;
    private static final int FLASH_TICKS = 10;
    private static int flash;

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(BlinkPayload.ID, (payload, context) -> {
            CameraGlide.start(ZIP_TICKS);
            flash = FLASH_TICKS;
            ScreenShake.kick(0.25f, 5);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> flash = 0);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!client.isPaused() && flash > 0) flash--;
        });
        HudRenderCallback.EVENT.register(BlinkClient::render);
    }

    private static void render(DrawContext context, RenderTickCounter counter) {
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
        context.setShaderColor(0.7f * a, 0.3f * a, 1.0f * a, 1f);
        context.drawTexture(VIGNETTE, 0, 0, -90, 0f, 0f, w, h, w, h);
        context.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }
}
