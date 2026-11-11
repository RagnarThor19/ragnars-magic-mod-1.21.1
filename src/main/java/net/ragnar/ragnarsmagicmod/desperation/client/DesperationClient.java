package net.ragnar.ragnarsmagicmod.desperation.client;

import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import org.joml.Matrix4f;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.MathHelper;
import net.ragnar.ragnarsmagicmod.desperation.Desperation;

/**
 * Client half of the Tome of Desperation (see Desperation): while you're desperate, the edges of the screen pulse red
 * in time with a soft heartbeat. The beat quickens and the red deepens the lower you go - about one beat a second at 3
 * hearts, nearly two at half a heart - and both fade away the moment you heal out of it.
 * <p>
 * The red is four soft bands fading in from the screen's edges, like the glow of being hurt, never covering the middle.
 */
public final class DesperationClient {
    private DesperationClient() {}

    /** Ticks between beats at the threshold and at the very bottom. */
    private static final int SLOW_BEAT = 22, FAST_BEAT = 12;

    /** Ticks since the last beat; how strongly it shows (eases in and out). */
    private static int sinceBeat = 1000;
    private static float presence;

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(DesperationClient::tick);
        HudRenderCallback.EVENT.register((ctx, counter) -> render(ctx, counter.getTickDelta(false)));
    }

    /** How close to the bottom {@code p} is, from 0 (at the threshold) to 1 (half a heart). */
    private static float depth(ClientPlayerEntity p) {
        return MathHelper.clamp((Desperation.THRESHOLD - p.getHealth()) / (Desperation.THRESHOLD - 1f), 0f, 1f);
    }

    private static void tick(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p == null || client.isPaused()) return;
        boolean desperate = Desperation.desperate(p);
        presence = MathHelper.clamp(presence + (desperate ? 0.1f : -0.08f), 0f, 1f);
        if (!desperate) {
            sinceBeat = 1000;
            return;
        }
        int beat = Math.round(MathHelper.lerp(depth(p), SLOW_BEAT, FAST_BEAT));
        if (++sinceBeat >= beat) {
            sinceBeat = 0;
            // A soft thump, deeper and a touch louder the closer you are
            p.playSound(SoundEvents.ENTITY_WARDEN_HEARTBEAT, 0.35f + 0.25f * depth(p), 0.9f);
        }
    }

    private static void render(DrawContext ctx, float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        if (p == null || presence <= 0f || mc.options.hudHidden) return;
        // Each beat flashes up fast and fades slowly; there's always a little red while it lasts
        float t = sinceBeat + tickDelta;
        float pulse = (float) Math.exp(-t / 5.0);
        float strength = presence * (0.35f + 0.25f * depth(p) + 0.4f * pulse);
        int a = (int) (MathHelper.clamp(strength, 0f, 1f) * MAX_ALPHA);
        if (a <= 0) return;

        // Four soft bands of red, deepest at the very edge and gone a fifth of the way in
        float w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
        float side = w * 0.2f, band = h * 0.25f;
        Matrix4f m = ctx.getMatrices().peek().getPositionMatrix();
        BufferBuilder b = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        quad(b, m, 0, 0, side, h, a, 0, 0, a);          // left: strong at x = 0
        quad(b, m, w - side, 0, w, h, 0, a, a, 0);      // right: strong at x = w
        quad(b, m, 0, 0, w, band, a, a, 0, 0);          // top: strong at y = 0
        quad(b, m, 0, h - band, w, h, 0, 0, a, a);      // bottom: strong at y = h

        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        BufferRenderer.drawWithGlobalProgram(b.end());
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
    }

    /** At most this opaque (of 255), at the strongest beat. */
    private static final int MAX_ALPHA = 210;

    /**
     * One band, from ({@code x1}, {@code y1}) to ({@code x2}, {@code y2}), with its corners' alphas given top-left,
     * top-right, bottom-right, bottom-left.
     */
    private static void quad(BufferBuilder b, Matrix4f m, float x1, float y1, float x2, float y2, int tl, int tr, int br, int bl) {
        b.vertex(m, x1, y1, 0).color(190, 10, 10, tl);
        b.vertex(m, x1, y2, 0).color(190, 10, 10, bl);
        b.vertex(m, x2, y2, 0).color(190, 10, 10, br);
        b.vertex(m, x2, y1, 0).color(190, 10, 10, tr);
    }
}
