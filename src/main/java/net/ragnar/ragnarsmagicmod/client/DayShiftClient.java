package net.ragnar.ragnarsmagicmod.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.network.DayShiftPayload;
import net.ragnar.ragnarsmagicmod.util.DayShift;
import org.joml.Matrix4f;

/**
 * The Tome of Dusk and Dawn as seen by everyone in the world: the sky wheels smoothly from one time to the
 * next (moved every frame, not every tick), a pillar of light stands over the caster, the view washes gold
 * or deep blue, and the world trembles.
 */
public final class DayShiftClient {
    private DayShiftClient() {}

    private static final RenderLayer GLOW = RenderLayer.of("ragnarsmagicmod_dayshift_glow",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 8192, false, true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.LIGHTNING_TRANSPARENCY)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    private static final int FADE_OUT = 30;
    private static final float PILLAR_HEIGHT = 320f;

    private static DayShiftPayload shift;
    private static int age;
    private static boolean settled;

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(DayShiftPayload.ID, (payload, context) -> {
            shift = payload;
            age = 0;
            settled = false;
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> shift = null);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (shift == null) return;
            if (client.world == null) { shift = null; return; }
            if (client.isPaused()) return;
            if (++age > DayShift.DURATION + FADE_OUT) shift = null;
        });
        WorldRenderEvents.START.register(DayShiftClient::turnSky);
        WorldRenderEvents.AFTER_ENTITIES.register(DayShiftClient::render);
        HudRenderCallback.EVENT.register(DayShiftClient::renderHud);
    }

    private static float progress(float tickDelta) {
        return (age + tickDelta) / DayShift.DURATION;
    }

    /** Moves this client's sky every frame, so the sun and moon glide instead of stepping once a tick. */
    private static void turnSky(WorldRenderContext context) {
        if (shift == null || settled || context.world() == null) return;
        float p = progress(context.tickCounter().getTickDelta(false));
        if (p >= 1f) {
            context.world().setTimeOfDay(shift.endTime());
            settled = true;
            return;
        }
        context.world().setTimeOfDay(Math.round(DayShift.timeAt(shift.startTime(), shift.endTime(), p)));
    }

    /** 0..1 how strongly the pillar is showing. */
    private static float presence(float t) {
        float in = MathHelper.clamp(t / 12f, 0f, 1f);
        float out = MathHelper.clamp((DayShift.DURATION + FADE_OUT - t) / (FADE_OUT + 10f), 0f, 1f);
        return Math.min(in, out);
    }

    // ---------------------------------------------------------------------
    // The pillar
    // ---------------------------------------------------------------------

    private static void render(WorldRenderContext context) {
        if (shift == null) return;
        float tickDelta = context.tickCounter().getTickDelta(false);
        float t = age + tickDelta;
        float show = presence(t);
        if (show <= 0.01f) return;

        boolean night = shift.toNight();
        float r = night ? 0.45f : 1.0f, g = night ? 0.55f : 0.78f, b = night ? 1.0f : 0.35f;
        Vec3d cam = context.camera().getPos();
        Vec3d base = shift.pos().subtract(cam);

        MatrixStack matrices = context.matrixStack() != null ? context.matrixStack() : new MatrixStack();
        matrices.push();
        Matrix4f m = matrices.peek().getPositionMatrix();
        VertexConsumerProvider.Immediate buffers = MinecraftClient.getInstance().getBufferBuilders().getEntityVertexConsumers();
        VertexConsumer vc = buffers.getBuffer(GLOW);

        // Pillar: rises out of the ground, then thins away at the end
        float rise = MathHelper.clamp(t / 10f, 0f, 1f);
        float height = PILLAR_HEIGHT * rise * rise;
        float pulse = 0.85f + 0.15f * MathHelper.sin(t * 0.5f);
        // Face the camera around the vertical axis
        double dx = -base.x, dz = -base.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        float sx = len < 1e-4 ? 1f : (float) (-dz / len), sz = len < 1e-4 ? 0f : (float) (dx / len);
        pillar(vc, m, base, sx, sz, 3.2f * show, height, r, g, b, 0.18f * show * pulse);
        pillar(vc, m, base, sx, sz, 1.1f * show, height, r, g, b, 0.55f * show * pulse);
        pillar(vc, m, base, sx, sz, 0.3f * show, height, 1f, 1f, 1f, 0.9f * show);

        buffers.draw(GLOW);
        matrices.pop();
    }

    private static void pillar(VertexConsumer vc, Matrix4f m, Vec3d base, float sx, float sz, float halfWidth, float height,
                               float r, float g, float b, float a) {
        float x = (float) base.x, y = (float) base.y, z = (float) base.z;
        float ox = sx * halfWidth, oz = sz * halfWidth;
        float mid = Math.min(height, 40f);
        // Bright near the ground, fading into the sky
        vc.vertex(m, x - ox, y, z - oz).color(r, g, b, 0f);
        vc.vertex(m, x, y, z).color(r, g, b, a);
        vc.vertex(m, x, y + mid, z).color(r, g, b, a);
        vc.vertex(m, x - ox, y + mid, z - oz).color(r, g, b, 0f);
        vc.vertex(m, x, y, z).color(r, g, b, a);
        vc.vertex(m, x + ox, y, z + oz).color(r, g, b, 0f);
        vc.vertex(m, x + ox, y + mid, z + oz).color(r, g, b, 0f);
        vc.vertex(m, x, y + mid, z).color(r, g, b, a);
        if (height > mid) {
            vc.vertex(m, x - ox, y + mid, z - oz).color(r, g, b, 0f);
            vc.vertex(m, x, y + mid, z).color(r, g, b, a);
            vc.vertex(m, x, y + height, z).color(r, g, b, 0f);
            vc.vertex(m, x - ox, y + height, z - oz).color(r, g, b, 0f);
            vc.vertex(m, x, y + mid, z).color(r, g, b, a);
            vc.vertex(m, x + ox, y + mid, z + oz).color(r, g, b, 0f);
            vc.vertex(m, x + ox, y + height, z + oz).color(r, g, b, 0f);
            vc.vertex(m, x, y + height, z).color(r, g, b, 0f);
        }
    }

    // ---------------------------------------------------------------------
    // On screen
    // ---------------------------------------------------------------------

    /** A wash of gold (dawn) or deep blue (night) as the spell takes hold, and again as it settles. */
    private static void renderHud(DrawContext context, RenderTickCounter counter) {
        if (shift == null) return;
        float t = age + counter.getTickDelta(false);
        float wash = 0f;
        if (t < 25f) wash = 0.28f * (1f - t / 25f);
        float end = t - DayShift.DURATION;
        if (end >= 0f && end < 20f) wash = Math.max(wash, 0.18f * (1f - end / 20f));
        if (wash <= 0.005f) return;
        int a = (int) (wash * 255);
        int color = shift.toNight() ? 0x1A2A80 : 0xFFC040;
        context.fill(0, 0, context.getScaledWindowWidth(), context.getScaledWindowHeight(), (a << 24) | color);
    }

    /** How much the ground is trembling: a lurch as it starts, then a low rumble while the sky turns. */
    public static float shake(float tickDelta) {
        if (shift == null) return 0f;
        float t = age + tickDelta;
        if (t > DayShift.DURATION) return 0f;
        float lurch = t < 8f ? 0.45f * (1f - t / 8f) : 0f;
        float rumble = 0.14f * MathHelper.sin(MathHelper.PI * t / DayShift.DURATION);
        MinecraftClient client = MinecraftClient.getInstance();
        float near = 1f;
        if (client.player != null) {
            double d = client.player.getPos().distanceTo(shift.pos());
            near = (float) MathHelper.clampedLerp(1.6, 1.0, d / 48.0);
        }
        return Math.max(lurch, rumble) * near;
    }
}
