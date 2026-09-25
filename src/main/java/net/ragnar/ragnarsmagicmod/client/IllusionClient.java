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
import net.ragnar.ragnarsmagicmod.network.IllusionPayload;

/** While you're a Tome of Illusion ghost, the edges of your view glow a cold, shimmering blue. */
public final class IllusionClient {
    private IllusionClient() {}

    private static final Identifier VIGNETTE = Identifier.ofVanilla("textures/misc/vignette.png");
    private static final int FADE_IN = 4;
    private static final int FADE_OUT = 8;

    private static int ticksLeft;
    private static float strength, prevStrength;
    private static int age;

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(IllusionPayload.ID, (payload, context) -> {
            if (payload.ticks() > 0 && ticksLeft <= 0) age = 0;
            ticksLeft = payload.ticks();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ticksLeft = 0;
            strength = prevStrength = 0;
        });
        ClientTickEvents.END_CLIENT_TICK.register(IllusionClient::tick);
        HudRenderCallback.EVENT.register(IllusionClient::render);
    }

    private static void tick(MinecraftClient client) {
        if (client.isPaused()) return;
        age++;
        if (ticksLeft > 0) ticksLeft--;
        prevStrength = strength;
        strength = ticksLeft > 0
                ? Math.min(1f, strength + 1f / FADE_IN)
                : Math.max(0f, strength - 1f / FADE_OUT);
    }

    private static void render(DrawContext context, RenderTickCounter counter) {
        float tickDelta = counter.getTickDelta(false);
        float s = MathHelper.lerp(tickDelta, prevStrength, strength);
        if (s <= 0.001f) return;

        // Slow shimmer, and it flickers faster in the last second as the spell wears off
        float t = age + tickDelta;
        float pulse = 0.8f + 0.2f * MathHelper.sin(t * (ticksLeft > 0 && ticksLeft < 20 ? 0.9f : 0.3f));
        float a = s * pulse * 0.85f;

        int w = context.getScaledWindowWidth();
        int h = context.getScaledWindowHeight();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        // Additive: the vignette's bright edges light up in blue, the centre stays clear
        RenderSystem.blendFunc(com.mojang.blaze3d.platform.GlStateManager.SrcFactor.ONE,
                com.mojang.blaze3d.platform.GlStateManager.DstFactor.ONE);
        context.setShaderColor(0.35f * a, 0.55f * a, 0.8f * a, 1f);
        context.drawTexture(VIGNETTE, 0, 0, -90, 0f, 0f, w, h, w, h);
        context.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }
}
