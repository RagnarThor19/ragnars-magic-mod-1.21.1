package net.ragnar.ragnarsmagicmod.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.Identifier;
import net.ragnar.ragnarsmagicmod.network.AscendPayload;

/**
 * Tome of Ascend, client side: the camera sweeps up through the rock to where you come out, and the edges of the
 * screen glow green on the way, fading as you break the surface.
 */
public final class AscendClient {
    private AscendClient() {}

    private static final Identifier VIGNETTE = Identifier.ofVanilla("textures/misc/vignette.png");
    private static int glow;
    private static int glowTicks = 1;

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(AscendPayload.ID, (payload, context) -> {
            CameraGlide.start(payload.ticks());
            glowTicks = payload.ticks() + 10;
            glow = glowTicks;
            ScreenShake.kick(0.2f, 6);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> glow = 0);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!client.isPaused() && glow > 0) glow--;
        });
        HudRenderCallback.EVENT.register(AscendClient::render);
    }

    private static void render(DrawContext context, RenderTickCounter counter) {
        if (glow <= 0) return;
        float s = (glow - counter.getTickDelta(false)) / glowTicks;
        if (s <= 0f) return;
        float a = Math.min(1f, s * 1.6f);
        a *= a;
        int w = context.getScaledWindowWidth();
        int h = context.getScaledWindowHeight();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(com.mojang.blaze3d.platform.GlStateManager.SrcFactor.ONE,
                com.mojang.blaze3d.platform.GlStateManager.DstFactor.ONE);
        context.setShaderColor(0.3f * a, 1.0f * a, 0.45f * a, 1f);
        context.drawTexture(VIGNETTE, 0, 0, -90, 0f, 0f, w, h, w, h);
        context.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }
}
