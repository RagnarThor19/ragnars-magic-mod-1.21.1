package net.ragnar.ragnarsmagicmod.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.item.spell.RewindSpell;
import net.ragnar.ragnarsmagicmod.network.RewindPayload;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/**
 * What the caster sees while rewinding: a tape-rewind tint with scanlines and a ◀◀ counter, the path still to
 * walk back along glowing ahead of them, and their movement smoothed between the server's steps.
 */
public final class RewindClient {
    private RewindClient() {}

    private static final RenderLayer GLOW = RenderLayer.of("ragnarsmagicmod_rewind_path",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 4096, false, true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.LIGHTNING_TRANSPARENCY)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    private static List<Vec3d> path = List.of();
    private static boolean active;
    private static int age;
    private static int fadeOut; // ticks left of the tint fading after it ends

    // Where we were at the end of the last tick, to glide between the server's teleports
    private static Vec3d lastPos;
    private static float lastYaw, lastPitch;

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(RewindPayload.ID, (payload, context) -> {
            active = payload.active();
            if (active) {
                path = payload.path();
                age = 0;
                lastPos = null;
            } else {
                fadeOut = 8;
                path = List.of();
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { active = false; fadeOut = 0; });
        ClientTickEvents.END_CLIENT_TICK.register(RewindClient::tick);
        WorldRenderEvents.AFTER_ENTITIES.register(RewindClient::render);
        HudRenderCallback.EVENT.register(RewindClient::renderHud);
    }

    private static void tick(MinecraftClient client) {
        if (fadeOut > 0) fadeOut--;
        ClientPlayerEntity player = client.player;
        if (!active || player == null) { lastPos = null; return; }
        age++;
        // The server moves us with a teleport each tick, which would snap; glide from last tick's spot instead
        if (lastPos != null && lastPos.squaredDistanceTo(player.getPos()) < 16.0) {
            player.prevX = lastPos.x;
            player.prevY = lastPos.y;
            player.prevZ = lastPos.z;
            player.lastRenderX = lastPos.x;
            player.lastRenderY = lastPos.y;
            player.lastRenderZ = lastPos.z;
            player.prevYaw = lastYaw;
            player.prevPitch = lastPitch;
        }
        lastPos = player.getPos();
        lastYaw = player.getYaw();
        lastPitch = player.getPitch();
    }

    /** How far through the rewind we are, 0..1. */
    private static float progress(float tickDelta) {
        if (path.isEmpty()) return 1f;
        return MathHelper.clamp((age + tickDelta) * RewindSpell.SPEED / path.size(), 0f, 1f);
    }

    /** The steps still to be walked back: a trail of glowing motes. */
    private static void render(WorldRenderContext context) {
        if (!active || path.size() < 2) return;
        float tickDelta = context.tickCounter().getTickDelta(false);
        int from = Math.min(path.size() - 1, (int) ((age + tickDelta) * RewindSpell.SPEED));
        Vec3d cam = context.camera().getPos();
        Quaternionf rot = context.camera().getRotation();
        Vector3f right = rot.transform(new Vector3f(1, 0, 0));
        Vector3f up = rot.transform(new Vector3f(0, 1, 0));

        MatrixStack matrices = context.matrixStack() != null ? context.matrixStack() : new MatrixStack();
        matrices.push();
        Matrix4f m = matrices.peek().getPositionMatrix();
        VertexConsumerProvider.Immediate buffers = MinecraftClient.getInstance().getBufferBuilders().getEntityVertexConsumers();
        VertexConsumer vc = buffers.getBuffer(GLOW);
        float t = age + tickDelta;
        for (int i = from + 2; i < path.size(); i += 2) {
            Vec3d p = path.get(i).add(0, 0.1, 0).subtract(cam);
            float along = (i - from) / (float) (path.size() - from);
            float twinkle = 0.7f + 0.3f * MathHelper.sin(t * 0.6f + i * 0.9f);
            float a = (1f - along * 0.6f) * 0.8f * twinkle;
            float size = 0.09f;
            float x = (float) p.x, y = (float) p.y, z = (float) p.z;
            vc.vertex(m, x - right.x * size - up.x * size, y - right.y * size - up.y * size, z - right.z * size - up.z * size).color(0.6f, 0.85f, 1f, a);
            vc.vertex(m, x + right.x * size - up.x * size, y + right.y * size - up.y * size, z + right.z * size - up.z * size).color(0.6f, 0.85f, 1f, a);
            vc.vertex(m, x + right.x * size + up.x * size, y + right.y * size + up.y * size, z + right.z * size + up.z * size).color(1f, 0.9f, 0.6f, a);
            vc.vertex(m, x - right.x * size + up.x * size, y - right.y * size + up.y * size, z - right.z * size + up.z * size).color(1f, 0.9f, 0.6f, a);
        }
        buffers.draw(GLOW);
        matrices.pop();
    }

    /** Tape-rewind look: a cool tint, rolling scanlines, a ◀◀ and how much time is left to wind back. */
    private static void renderHud(DrawContext context, RenderTickCounter counter) {
        float strength = active ? 1f : fadeOut / 8f;
        if (strength <= 0f) return;
        MinecraftClient client = MinecraftClient.getInstance();
        float tickDelta = counter.getTickDelta(false);
        int w = context.getScaledWindowWidth(), h = context.getScaledWindowHeight();
        float t = age + tickDelta;

        context.fill(0, 0, w, h, argb(0.18f * strength, 60, 110, 170));
        // Scanlines rolling upward
        int offset = (int) (t * 3) % 6;
        for (int y = h - offset; y > 0; y -= 6) {
            context.fill(0, y, w, y + 1, argb(0.10f * strength, 0, 0, 0));
        }
        // A tracking band sweeping up the screen
        int band = h - (int) ((t * 9) % (h + 40));
        context.fill(0, band, w, band + 14, argb(0.07f * strength, 255, 255, 255));

        if (!active) return;
        float p = progress(tickDelta);
        float secondsLeft = (1f - p) * path.size() / 20f;
        boolean blink = ((int) (t / 6)) % 2 == 0;
        int color = 0xFFFFFF | ((int) (255 * strength) << 24);
        if (blink) context.drawTextWithShadow(client.textRenderer, Text.literal("◀◀ REWIND"), 12, 12, color);
        context.drawTextWithShadow(client.textRenderer, Text.literal(String.format("-%.1fs", secondsLeft)), 12, 24, 0xCCE6FF | (0xFF << 24));
        // Progress bar emptying right to left
        int barW = 80;
        context.fill(12, 36, 12 + barW, 38, argb(0.35f, 255, 255, 255));
        context.fill(12, 36, 12 + (int) (barW * (1f - p)), 38, argb(0.9f, 150, 210, 255));
    }

    private static int argb(float a, int r, int g, int b) {
        return (MathHelper.clamp((int) (a * 255), 0, 255) << 24) | (r << 16) | (g << 8) | b;
    }
}
