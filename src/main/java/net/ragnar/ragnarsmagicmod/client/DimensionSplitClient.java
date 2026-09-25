package net.ragnar.ragnarsmagicmod.client;

import com.mojang.blaze3d.systems.RenderSystem;
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
import net.ragnar.ragnarsmagicmod.network.DimensionSplitPayload;
import net.ragnar.ragnarsmagicmod.util.SplitPattern;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Everything you see and feel from a Tome of Dimension Split: the impact frames on screen (black flash with
 * a red slash, then the sever flash), the blood-red slashes cut into the world, the black rift they leave,
 * and the camera shake.
 */
public final class DimensionSplitClient {
    private DimensionSplitClient() {}

    private static final class Rift {
        final Vec3d pos;
        final List<SplitPattern.Slash> slashes;
        int age = 0;

        Rift(Vec3d pos, List<SplitPattern.Slash> slashes) {
            this.pos = pos;
            this.slashes = slashes;
        }
    }

    private static final List<Rift> RIFTS = new ArrayList<>();

    /** Light: added on top of what's behind, so overlapping cuts burn white-hot. */
    private static final RenderLayer GLOW = RenderLayer.of("ragnarsmagicmod_split_glow",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 16384, false, true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.LIGHTNING_TRANSPARENCY)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    /** The inside of the rift: plain black nothing. */
    private static final RenderLayer VOID = RenderLayer.of("ragnarsmagicmod_split_void",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 4096, false, true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.TRANSLUCENT_TRANSPARENCY)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    private static final int SEGMENTS = 40;
    // Camera position for this frame: vertices are sent relative to it so they stay precise far from spawn
    private static Vec3d camera = Vec3d.ZERO;

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(DimensionSplitPayload.ID, (payload, context) ->
                RIFTS.add(new Rift(payload.pos(), SplitPattern.generate(payload.pos(), payload.view(), payload.seed()))));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> RIFTS.clear());
        ClientTickEvents.END_CLIENT_TICK.register(DimensionSplitClient::tick);
        WorldRenderEvents.AFTER_ENTITIES.register(DimensionSplitClient::renderWorld);
        HudRenderCallback.EVENT.register(DimensionSplitClient::renderHud);
    }

    private static void tick(MinecraftClient client) {
        if (client.world == null) { RIFTS.clear(); return; }
        if (client.isPaused()) return;
        Iterator<Rift> it = RIFTS.iterator();
        while (it.hasNext()) {
            Rift r = it.next();
            if (++r.age > SplitPattern.END + 2) it.remove();
        }
    }

    // ---------------------------------------------------------------------
    // In the world
    // ---------------------------------------------------------------------

    private static void renderWorld(WorldRenderContext context) {
        if (RIFTS.isEmpty()) return;
        float tickDelta = context.tickCounter().getTickDelta(false);
        Vec3d cam = context.camera().getPos();
        camera = cam;
        MatrixStack matrices = context.matrixStack() != null ? context.matrixStack() : new MatrixStack();
        matrices.push();
        Matrix4f m = matrices.peek().getPositionMatrix();
        VertexConsumerProvider.Immediate buffers = MinecraftClient.getInstance().getBufferBuilders().getEntityVertexConsumers();

        // The black of the rift first, so the light can burn around its edges
        VertexConsumer black = buffers.getBuffer(VOID);
        for (Rift r : RIFTS) {
            float age = r.age + tickDelta;
            float open = SplitPattern.riftOpen(age);
            if (open <= 0f) continue;
            for (int i = 0; i < Math.min(2, r.slashes.size()); i++) {
                SplitPattern.Slash s = r.slashes.get(i);
                float h = voidWidth(open, age, i);
                band(black, m, s, 1f, 0f, h, 0.01f, 0f, 0f, 0.97f, 0.01f, 0f, 0f, 0.97f);
            }
        }
        buffers.draw(VOID);

        VertexConsumer glow = buffers.getBuffer(GLOW);
        for (Rift r : RIFTS) {
            float age = r.age + tickDelta;
            for (int i = 0; i < r.slashes.size(); i++) drawSlash(glow, m, r.slashes.get(i), age, i);

            // The rift's burning rim
            float open = SplitPattern.riftOpen(age);
            if (open > 0f) {
                for (int i = 0; i < Math.min(2, r.slashes.size()); i++) {
                    SplitPattern.Slash s = r.slashes.get(i);
                    float h = voidWidth(open, age, i);
                    float pulse = 0.8f + 0.2f * MathHelper.sin(age * 0.9f + i);
                    band(glow, m, s, 1f, h, h * 3.0f, 0.9f, 0.05f, 0.03f, 0.9f * pulse, 0.5f, 0f, 0f, 0f);
                    band(glow, m, s, 1f, h, h + 0.05f, 1f, 0.7f, 0.6f, pulse, 1f, 0.3f, 0.2f, 0.4f * pulse);
                }
            }
        }
        buffers.draw(GLOW);
        matrices.pop();
    }

    private static float voidWidth(float open, float age, int index) {
        float breathe = 1f + 0.08f * MathHelper.sin(age * 0.6f + index * 2f);
        return (index == 0 ? 0.5f : 0.4f) * open * breathe;
    }

    private static void drawSlash(VertexConsumer vc, Matrix4f m, SplitPattern.Slash s, float age, int index) {
        float a = age - s.start();
        if (a < 0f) return;
        float reveal = MathHelper.clamp(a / SplitPattern.SWEEP_TICKS, 0f, 1f);
        reveal = 1f - (1f - reveal) * (1f - reveal) * (1f - reveal);

        float width, intensity;
        if (age < SplitPattern.SEVER) {
            width = 1f;
            intensity = 0.9f + 0.1f * MathHelper.sin(age * 2.1f + index);
            if (a < SplitPattern.SWEEP_TICKS + 1) intensity *= 1.4f; // fresh cuts burn brightest
        } else if (age < SplitPattern.SEVER + 2) {
            width = 1.7f;
            intensity = 1.8f;
        } else {
            float f = MathHelper.clamp((age - SplitPattern.SEVER - 2) / 10f, 0f, 1f);
            width = 1.7f * (1f - f);
            intensity = 1.6f * (1f - f);
        }
        if (intensity <= 0.01f || width <= 0.01f) return;
        float i = Math.min(intensity, 1f);
        float over = Math.max(0f, intensity - 1f);

        // Red haze, the blade of light, and a white-hot core
        band(vc, m, s, reveal, 0f, (float) (3.2 * width), 0.75f, 0.0f, 0.02f, 0.45f * i, 0.4f, 0f, 0f, 0f);
        band(vc, m, s, reveal, 0f, width, 1.0f, 0.1f, 0.06f, 0.95f * i, 1.0f, 0.05f, 0.03f, 0.3f * i);
        band(vc, m, s, reveal, 0f, 0.3f * width, 1.0f, 0.85f, 0.8f, Math.min(1f, i + over), 1.0f, 0.4f, 0.3f, 0.4f * i);
    }

    /**
     * A strip along the slash (up to {@code reveal} of its length) from {@code inner} to {@code outer} times its
     * thickness on both sides, shading from the inner colour to the outer one.
     */
    private static void band(VertexConsumer vc, Matrix4f m, SplitPattern.Slash s, float reveal, float inner, float outer,
                             float r0, float g0, float b0, float a0, float r1, float g1, float b1, float a1) {
        int segs = Math.max(2, (int) (SEGMENTS * reveal));
        Vec3d v = s.v();
        for (int k = 0; k < segs; k++) {
            double t0 = reveal * k / segs;
            double t1 = reveal * (k + 1) / segs;
            Vec3d p0 = s.point(t0).subtract(camera), p1 = s.point(t1).subtract(camera);
            double h0 = s.halfWidthAt(t0), h1 = s.halfWidthAt(t1);
            for (int side = -1; side <= 1; side += 2) {
                Vec3d i0 = p0.add(v.multiply(side * h0 * inner)), i1 = p1.add(v.multiply(side * h1 * inner));
                Vec3d o0 = p0.add(v.multiply(side * h0 * outer)), o1 = p1.add(v.multiply(side * h1 * outer));
                vc.vertex(m, (float) i0.x, (float) i0.y, (float) i0.z).color(r0, g0, b0, a0);
                vc.vertex(m, (float) i1.x, (float) i1.y, (float) i1.z).color(r0, g0, b0, a0);
                vc.vertex(m, (float) o1.x, (float) o1.y, (float) o1.z).color(r1, g1, b1, a1);
                vc.vertex(m, (float) o0.x, (float) o0.y, (float) o0.z).color(r1, g1, b1, a1);
            }
        }
    }

    // ---------------------------------------------------------------------
    // On screen
    // ---------------------------------------------------------------------

    /** How hard this rift hits the viewer: full up close, nothing beyond ~80 blocks. */
    private static float presence(Rift r, Vec3d cam) {
        double d = cam.distanceTo(r.pos);
        return (float) MathHelper.clamp(1.0 - (d - 30.0) / 50.0, 0.0, 1.0);
    }

    private static void renderHud(DrawContext context, RenderTickCounter counter) {
        if (RIFTS.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.gameRenderer == null) return;
        Vec3d cam = client.gameRenderer.getCamera().getPos();
        float tickDelta = counter.getTickDelta(false);
        int w = context.getScaledWindowWidth();
        int h = context.getScaledWindowHeight();

        for (Rift r : RIFTS) {
            float p = presence(r, cam);
            if (p <= 0f) continue;
            float age = r.age + tickDelta;

            // 1) Impact frame: pitch black, then the world comes back dimmed and holding its breath
            float black;
            if (age < 1.5f) black = 1f;
            else if (age < 6f) black = MathHelper.lerp((age - 1.5f) / 4.5f, 1f, 0.35f);
            else if (age < SplitPattern.SEVER) black = 0.35f;
            // 2) The sever: another black frame, gone in a blink
            else if (age < SplitPattern.SEVER + 1.2f) black = 0.92f;
            else black = 0f;
            if (black > 0f) {
                context.fill(0, 0, w, h, argb(black * p, 0, 0, 0));
                context.draw(); // flush, so the slash is drawn on top of the black
            }

            // The red slash across the black
            if (age < 6f) {
                float reveal = MathHelper.clamp(age / 1.2f, 0f, 1f);
                float fade = age < 2f ? 1f : 1f - (age - 2f) / 4f;
                screenSlash(context, w, h, -0.08f, 0.8f, 1.08f, 0.2f, 0.09f, reveal, fade * p);
            }
            if (age >= SplitPattern.SEVER && age < SplitPattern.SEVER + 4f) {
                float fade = 1f - (age - SplitPattern.SEVER) / 4f;
                screenSlash(context, w, h, -0.08f, 0.8f, 1.08f, 0.2f, 0.09f, 1f, fade * p);
                screenSlash(context, w, h, 0.2f, -0.08f, 0.8f, 1.08f, -0.07f, 1f, fade * p);
            }

            // 3) Blood-red afterglow washing out
            if (age >= SplitPattern.SEVER + 1.2f && age < SplitPattern.SEVER + 10f) {
                float f = 1f - (age - SplitPattern.SEVER - 1.2f) / 8.8f;
                context.fill(0, 0, w, h, argb(0.45f * f * f * p, 200, 10, 10));
            }
        }
    }

    /** A crescent slash across the screen from (x0, y0) to (x1, y1), in fractions of the screen. */
    private static void screenSlash(DrawContext context, int w, int h, float x0, float y0, float x1, float y1,
                                    float bend, float reveal, float alpha) {
        if (alpha <= 0.01f || reveal <= 0f) return;
        Matrix4f m = context.getMatrices().peek().getPositionMatrix();
        float ax = x0 * w, ay = y0 * h, bx = x1 * w, by = y1 * h;
        float dx = bx - ax, dy = by - ay;
        float len = MathHelper.sqrt(dx * dx + dy * dy);
        float nx = -dy / len, ny = dx / len; // perpendicular
        float thick = h * 0.035f;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        BufferBuilder buf = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        float[][] layers = {
                // outer multiple, inner rgba, outer rgba
                {4.0f, 0.8f, 0.0f, 0.0f, 0.55f, 0.5f, 0f, 0f, 0f},
                {1.0f, 1.0f, 0.1f, 0.08f, 1.0f, 0.9f, 0.05f, 0.05f, 0.6f},
                {0.3f, 1.0f, 0.9f, 0.85f, 1.0f, 1.0f, 0.5f, 0.4f, 0.8f},
        };
        int segs = 48;
        for (float[] L : layers) {
            for (int k = 0; k < segs; k++) {
                float t0 = reveal * k / segs, t1 = reveal * (k + 1) / segs;
                float c0 = (float) (Math.sin(Math.PI * t0) - 0.5) * bend * h, c1 = (float) (Math.sin(Math.PI * t1) - 0.5) * bend * h;
                float px0 = ax + dx * t0 + nx * c0, py0 = ay + dy * t0 + ny * c0;
                float px1 = ax + dx * t1 + nx * c1, py1 = ay + dy * t1 + ny * c1;
                float h0 = thick * (float) Math.pow(Math.sin(Math.PI * t0), 0.7) * L[0];
                float h1 = thick * (float) Math.pow(Math.sin(Math.PI * t1), 0.7) * L[0];
                for (int side = -1; side <= 1; side += 2) {
                    buf.vertex(m, px0, py0, 0).color(L[1], L[2], L[3], L[4] * alpha);
                    buf.vertex(m, px0 + nx * h0 * side, py0 + ny * h0 * side, 0).color(L[5], L[6], L[7], L[8] * alpha);
                    buf.vertex(m, px1 + nx * h1 * side, py1 + ny * h1 * side, 0).color(L[5], L[6], L[7], L[8] * alpha);
                    buf.vertex(m, px1, py1, 0).color(L[1], L[2], L[3], L[4] * alpha);
                }
            }
        }
        BuiltBuffer built = buf.endNullable();
        if (built != null) BufferRenderer.drawWithGlobalProgram(built);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    private static int argb(float a, int r, int g, int b) {
        int ai = MathHelper.clamp((int) (a * 255), 0, 255);
        return (ai << 24) | (r << 16) | (g << 8) | b;
    }

    // ---------------------------------------------------------------------
    // Camera shake
    // ---------------------------------------------------------------------

    /** 0.. how hard the camera should shake right now. */
    public static float shake(float tickDelta) {
        if (RIFTS.isEmpty()) return 0f;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return 0f;
        Vec3d at = client.player.getEyePos();
        float total = 0f;
        for (Rift r : RIFTS) {
            float p = presence(r, at);
            if (p <= 0f) continue;
            float age = r.age + tickDelta;
            float k = 0f;
            if (age < 5f) k = 0.6f * (1f - age / 5f);
            else if (age < SplitPattern.SEVER) k = 0.06f; // the world trembling, holding still
            else if (age < SplitPattern.SEVER + 16f) {
                float f = 1f - (age - SplitPattern.SEVER) / 16f;
                k = 1.3f * f * f;
            } else if (age < SplitPattern.RIFT_CLOSE) k = 0.05f;
            total = Math.max(total, k * p);
        }
        return total;
    }
}
