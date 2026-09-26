package net.ragnar.ragnarsmagicmod.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.network.CloneSwapPayload;
import net.ragnar.ragnarsmagicmod.network.CloneTimerPayload;

/**
 * Tome of Clones, client side. The body swap: instead of snapping to the clone's spot, the camera glides from where
 * you were into your new body (read by CameraMixin), with a cold flash around the edges of the screen.
 * Also a small countdown under the crosshair for how long the clones have left.
 */
public final class CloneClient {
    private CloneClient() {}

    private static final Identifier VIGNETTE = Identifier.ofVanilla("textures/misc/vignette.png");
    private static final int GLIDE_TICKS = 6;
    private static final int FLASH_TICKS = 12;

    private static Vec3d from;
    private static int glide = -1; // ticks since the swap, -1 when not gliding
    private static int flash;

    private static final int TIMER_FADE_TICKS = 6;
    private static int timerLeft, timerTotal;
    private static float timerAlpha, prevTimerAlpha;

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(CloneSwapPayload.ID, (payload, context) -> {
            // The camera hasn't been moved since the last frame, so it's still where the old body was
            from = context.client().gameRenderer.getCamera().getPos();
            glide = 0;
            flash = FLASH_TICKS;
        });
        ClientPlayNetworking.registerGlobalReceiver(CloneTimerPayload.ID, (payload, context) -> {
            timerLeft = payload.ticks();
            if (payload.ticks() > 0) timerTotal = payload.ticks();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            glide = -1;
            flash = 0;
            timerLeft = 0;
            timerAlpha = prevTimerAlpha = 0;
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.isPaused()) return;
            if (glide >= 0 && ++glide >= GLIDE_TICKS) glide = -1;
            if (flash > 0) flash--;
            if (timerLeft > 0) timerLeft--;
            prevTimerAlpha = timerAlpha;
            timerAlpha = timerLeft > 0
                    ? Math.min(1f, timerAlpha + 1f / TIMER_FADE_TICKS)
                    : Math.max(0f, timerAlpha - 1f / TIMER_FADE_TICKS);
        });
        HudRenderCallback.EVENT.register(CloneClient::render);
        HudRenderCallback.EVENT.register(CloneClient::renderTimer);
    }

    /** Where the camera should be this frame, given where it would normally be. */
    public static Vec3d glide(Vec3d cameraPos, float tickDelta) {
        if (glide < 0 || from == null) return cameraPos;
        float t = MathHelper.clamp((glide + tickDelta) / GLIDE_TICKS, 0f, 1f);
        float eased = 1f - (1f - t) * (1f - t) * (1f - t);
        return from.lerp(cameraPos, eased);
    }

    /** A thin draining bar and the seconds left, just under the crosshair. Red and blinking for the last 3 seconds. */
    private static void renderTimer(DrawContext context, RenderTickCounter counter) {
        MinecraftClient client = MinecraftClient.getInstance();
        float alpha = MathHelper.lerp(counter.getTickDelta(false), prevTimerAlpha, timerAlpha);
        if (alpha <= 0.02f || timerTotal <= 0 || client.options.hudHidden || PossessionClient.isPossessing()) return;

        float left = Math.max(0f, timerLeft - counter.getTickDelta(false));
        boolean ending = timerLeft > 0 && timerLeft <= 60;
        if (ending && (timerLeft / 4) % 2 == 0) alpha *= 0.45f;
        int a = (int) (alpha * 255) << 24;
        int color = ending ? 0xFF5060 : 0x8CC8FF;

        int cx = context.getScaledWindowWidth() / 2;
        int y = context.getScaledWindowHeight() / 2 + 10;
        int half = 16;
        context.fill(cx - half - 1, y - 1, cx + half + 1, y + 2, (int) (alpha * 0x88) << 24);
        context.fill(cx - half, y, cx - half + Math.round(2 * half * left / timerTotal), y + 1, a | color);

        String secs = timerLeft > 0 ? (timerLeft + 19) / 20 + "s" : "0s";
        context.getMatrices().push();
        context.getMatrices().translate(cx, y + 4, 0);
        context.getMatrices().scale(0.75f, 0.75f, 1f);
        context.drawTextWithShadow(client.textRenderer, secs, -client.textRenderer.getWidth(secs) / 2, 0, a | color);
        context.getMatrices().pop();
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
        // Additive, like the Illusion ghost glow: the edges light up, the middle stays clear
        RenderSystem.blendFunc(com.mojang.blaze3d.platform.GlStateManager.SrcFactor.ONE,
                com.mojang.blaze3d.platform.GlStateManager.DstFactor.ONE);
        context.setShaderColor(0.45f * a, 0.7f * a, 1.0f * a, 1f);
        context.drawTexture(VIGNETTE, 0, 0, -90, 0f, 0f, w, h, w, h);
        context.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }
}
