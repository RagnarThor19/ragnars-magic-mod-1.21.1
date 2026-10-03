package net.ragnar.ragnarsmagicmod.jaunting.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.ragnar.ragnarsmagicmod.client.CameraGlide;
import net.ragnar.ragnarsmagicmod.client.ScreenShake;
import net.ragnar.ragnarsmagicmod.jaunting.JauntPayload;
import net.ragnar.ragnarsmagicmod.jaunting.Jaunting;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Client half of the Tome of Jaunting (see Jaunting for how to remove it): the kunai's renderer, the bolt of
 * lightning left hanging between where a jaunter was and where they landed, and for the jaunter a quick zip of the
 * camera and an electric-blue flash round the edges of the screen.
 */
public final class JauntingClient {
    private JauntingClient() {}

    private static final Identifier VIGNETTE = Identifier.ofVanilla("textures/misc/vignette.png");
    private static final int STREAK_TICKS = 9;
    private static final int FLASH_TICKS = 12;
    /** Past this, the camera just cuts to the new spot instead of zipping there. */
    private static final double GLIDE_RANGE = 48.0;

    private static final class Streak {
        final Vec3d from, to;
        final long seed;
        int age;

        Streak(Vec3d from, Vec3d to, long seed) {
            this.from = from;
            this.to = to;
            this.seed = seed;
        }
    }

    private static final List<Streak> STREAKS = new ArrayList<>();
    private static int flash;

    public static void init() {
        EntityRendererRegistry.register(Jaunting.KUNAI, KunaiRenderer::new);
        ClientPlayNetworking.registerGlobalReceiver(JauntPayload.ID, (payload, context) -> {
            Vec3d from = new Vec3d(payload.from()), to = new Vec3d(payload.to());
            if (payload.streak()) STREAKS.add(new Streak(from, to, context.client().world != null ? context.client().world.random.nextLong() : 0L));
            if (payload.self()) {
                if (payload.streak() && from.distanceTo(to) < GLIDE_RANGE) CameraGlide.start(4);
                flash = FLASH_TICKS;
                ScreenShake.kick(0.3f, 6);
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            STREAKS.clear();
            flash = 0;
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.isPaused()) return;
            if (flash > 0) flash--;
            Iterator<Streak> it = STREAKS.iterator();
            while (it.hasNext()) if (++it.next().age >= STREAK_TICKS) it.remove();
        });
        WorldRenderEvents.AFTER_TRANSLUCENT.register(JauntingClient::renderWorld);
        HudRenderCallback.EVENT.register(JauntingClient::renderHud);
    }

    private static void renderWorld(WorldRenderContext context) {
        if (STREAKS.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        float tickDelta = context.tickCounter().getTickDelta(false);
        Vec3d cam = context.camera().getPos();
        MatrixStack ms = context.matrixStack() != null ? context.matrixStack() : new MatrixStack();
        Matrix4f pose = ms.peek().getPositionMatrix();
        VertexConsumerProvider.Immediate buffers = client.getBufferBuilders().getEntityVertexConsumers();
        VertexConsumer vc = buffers.getBuffer(Bolts.LAYER);

        for (Streak s : STREAKS) {
            float t = (s.age + tickDelta) / STREAK_TICKS;
            // Full strength for a moment, then it fades, flickering as it goes
            float alpha = t < 0.25f ? 1f : (1f - t) / 0.75f;
            alpha *= 0.75f + 0.25f * MathHelper.sin((s.age + tickDelta) * 4f);
            Vec3d a = s.from.subtract(cam), b = s.to.subtract(cam);
            double len = a.distanceTo(b);
            int segments = MathHelper.clamp((int) (len / 1.2), 5, 64);
            double jitter = Math.min(0.55, 0.12 + len * 0.04);
            // A new shape every tick, like real lightning
            Random r = Random.create(s.seed + s.age * 341L);
            Bolts.bolt(vc, pose, a, b, r, segments, jitter, 0.045f, alpha, 3);
            Bolts.bolt(vc, pose, a, b, r, segments, jitter * 1.3, 0.02f, alpha * 0.5f, 0);
        }
        buffers.draw(Bolts.LAYER);
    }

    /** An electric-blue flash in from the edges of the screen as you land. */
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
        context.setShaderColor(0.35f * a, 0.7f * a, 1.0f * a, 1f);
        context.drawTexture(VIGNETTE, 0, 0, -90, 0f, 0f, w, h, w, h);
        context.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }
}
