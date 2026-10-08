package net.ragnar.ragnarsmagicmod.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.item.spell.DeflectionSpell;
import net.ragnar.ragnarsmagicmod.network.DeflectionPayload;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Tome of Deflection, client side: the pane of glass. It's worked out afresh every frame from where the caster's eyes
 * are and where they're looking - for your own pane, exactly where the camera is - so it holds perfectly still in
 * front of you instead of trailing a tick behind. It's clear glass: the faintest blue tint, bright edges and two
 * streaks of reflected light, so you can see straight through it. It fades in and out rather than growing, and
 * anything striking it sends a ripple out across the glass from where it hit.
 */
public final class DeflectionClient {
    private DeflectionClient() {}

    private static final int FADE_IN = 2;
    private static final int RIPPLE_TICKS = 10;

    private static RenderLayer layer(String name, RenderPhase.Transparency transparency) {
        return RenderLayer.of("ragnarsmagicmod_deflection_" + name,
                VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 4096, false, true,
                RenderLayer.MultiPhaseParameters.builder()
                        .program(RenderPhase.COLOR_PROGRAM)
                        .transparency(transparency)
                        .writeMaskState(RenderPhase.COLOR_MASK)
                        .cull(RenderPhase.DISABLE_CULLING)
                        .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                        .build(false));
    }

    /** The faint tint of the glass, and the additive light on it (edges, glints, ripples). */
    private static final RenderLayer TINT = layer("tint", RenderPhase.TRANSLUCENT_TRANSPARENCY);
    private static final RenderLayer LIGHT = layer("light", RenderPhase.LIGHTNING_TRANSPARENCY);

    private static final class Pane {
        int left, total, age;
        final List<float[]> ripples = new ArrayList<>(); // u, v, age

        Pane(int ticks) {
            left = total = ticks;
        }
    }

    private static final Map<Integer, Pane> PANES = new HashMap<>();

    // This frame
    private static Vec3d cam = Vec3d.ZERO;
    private static Matrix4f pose = new Matrix4f();

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(DeflectionPayload.ID, (payload, context) -> {
            int id = payload.entityId();
            if (payload.ticks() <= 0) {
                PANES.remove(id);
                return;
            }
            Pane pane = PANES.get(id);
            if (pane == null || !payload.hit()) {
                pane = new Pane(payload.ticks());
                PANES.put(id, pane);
            }
            // (The spell's axes point to the caster's left and down; ours to their right and up)
            if (payload.hit()) pane.ripples.add(new float[]{-payload.u(), -payload.v(), 0});
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> PANES.clear());
        ClientTickEvents.END_CLIENT_TICK.register(DeflectionClient::tick);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(DeflectionClient::render);
    }

    private static void tick(MinecraftClient client) {
        if (client.world == null) {
            PANES.clear();
            return;
        }
        if (client.isPaused()) return;
        Iterator<Pane> it = PANES.values().iterator();
        while (it.hasNext()) {
            Pane p = it.next();
            p.age++;
            if (--p.left <= 0) {
                it.remove();
                continue;
            }
            p.ripples.removeIf(r -> ++r[2] > RIPPLE_TICKS);
        }
    }

    /** How solid the pane is: easing in when it goes up, out over its last few ticks. */
    private static float presence(Pane p, float tickDelta) {
        float in = Math.min(1f, (p.age + tickDelta) / FADE_IN);
        float out = Math.min(1f, Math.max(0f, p.left - tickDelta) / DeflectionSpell.FADE_TICKS);
        return Math.min(in, out);
    }

    private static void render(WorldRenderContext context) {
        if (PANES.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;
        float tickDelta = context.tickCounter().getTickDelta(false);
        Camera camera = context.camera();
        cam = camera.getPos();
        MatrixStack matrices = context.matrixStack() != null ? context.matrixStack() : new MatrixStack();
        pose = matrices.peek().getPositionMatrix();
        VertexConsumerProvider.Immediate buffers = client.getBufferBuilders().getEntityVertexConsumers();

        for (int pass = 0; pass < 2; pass++) {
            RenderLayer layer = pass == 0 ? TINT : LIGHT;
            VertexConsumer vc = buffers.getBuffer(layer);
            for (Map.Entry<Integer, Pane> entry : PANES.entrySet()) {
                Entity e = client.world.getEntityById(entry.getKey());
                if (e == null) continue;
                // Your own pane hangs off the camera itself, so it can't drift against the view by even a frame
                boolean ownEyes = e == client.getCameraEntity() && !camera.isThirdPerson();
                Vec3d eye = ownEyes ? camera.getPos() : e.getCameraPosVec(tickDelta);
                Vec3d look = e.getRotationVec(tickDelta).normalize();
                Vec3d c = eye.add(look.multiply(DeflectionSpell.DISTANCE)).add(0, -DeflectionSpell.DROP, 0);
                // The spell's axes point to the viewer's left and down; turn them to their right and up
                Vec3d across = DeflectionSpell.paneRight(look);
                Vec3d right = across.multiply(-1);
                Vec3d up = across.crossProduct(look).normalize().multiply(-1);
                Pane p = entry.getValue();
                if (pass == 0) drawTint(vc, c, right, up, presence(p, tickDelta));
                else drawLight(vc, c, right, up, look, p, presence(p, tickDelta), tickDelta);
            }
            buffers.draw(layer);
        }
    }

    private static void drawTint(VertexConsumer vc, Vec3d c, Vec3d right, Vec3d up, float a) {
        quad(vc, c, right.multiply(DeflectionSpell.HALF_WIDTH), up.multiply(DeflectionSpell.HALF_HEIGHT), 0.78f, 0.93f, 1f, 0.09f * a);
    }

    private static void drawLight(VertexConsumer vc, Vec3d c, Vec3d right, Vec3d up, Vec3d look, Pane p, float a, float tickDelta) {
        double hw = DeflectionSpell.HALF_WIDTH, hh = DeflectionSpell.HALF_HEIGHT;
        // Just in front of the tint, so the light sits on the glass
        Vec3d f = c.subtract(look.multiply(0.002));

        // Struck a moment ago: the whole pane brightens
        float flash = 0f;
        for (float[] r : p.ripples) flash = Math.max(flash, 1f - (r[2] + tickDelta) / 4f);
        float edge = (0.5f + 0.4f * flash) * a;

        // Bright edges, with a softer glow just inside them
        double t = 0.035, soft = 0.12;
        strip(vc, f, right, up, -hw, hh - t, hw, hh, 0.6f, 0.88f, 1f, edge);
        strip(vc, f, right, up, -hw, -hh, hw, -hh + t, 0.6f, 0.88f, 1f, edge);
        strip(vc, f, right, up, -hw, -hh + t, -hw + t, hh - t, 0.6f, 0.88f, 1f, edge);
        strip(vc, f, right, up, hw - t, -hh + t, hw, hh - t, 0.6f, 0.88f, 1f, edge);
        float inner = 0.1f * a;
        strip(vc, f, right, up, -hw + t, hh - t - soft, hw - t, hh - t, 0.5f, 0.8f, 1f, inner);
        strip(vc, f, right, up, -hw + t, -hh + t, hw - t, -hh + t + soft, 0.5f, 0.8f, 1f, inner);
        strip(vc, f, right, up, -hw + t, -hh + t + soft, -hw + t + soft, hh - t - soft, 0.5f, 0.8f, 1f, inner);
        strip(vc, f, right, up, hw - t - soft, -hh + t + soft, hw - t, hh - t - soft, 0.5f, 0.8f, 1f, inner);
        // Brighter corners
        double k = 0.09;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                double x = sx * (hw - k), y = sy * (hh - k);
                strip(vc, f, right, up, x - k, y - k, x + k, y + k, 0.85f, 0.97f, 1f, 0.45f * a);
            }
        }

        // Two streaks of reflected light across the top left, as on a window
        glint(vc, f, right, up, -0.95, 0.55, 0.16, 0.7, 0.13f * a);
        glint(vc, f, right, up, -0.55, 0.45, 0.06, 0.55, 0.1f * a);

        // Ripples from wherever it was struck
        for (float[] r : p.ripples) {
            float age = r[2] + tickDelta;
            float life = 1f - age / RIPPLE_TICKS;
            if (life <= 0) continue;
            double radius = 0.12 + age * 0.2;
            ring(vc, f, right, up, r[0], r[1], radius, 0.06, 0.75f, 0.95f, 1f, 0.75f * life * a);
            if (age > 2) ring(vc, f, right, up, r[0], r[1], radius * 0.55, 0.04, 0.75f, 0.95f, 1f, 0.4f * life * a);
            if (age < 3) strip(vc, f, right, up, r[0] - 0.18, r[1] - 0.18, r[0] + 0.18, r[1] + 0.18, 0.9f, 1f, 1f, (1f - age / 3f) * 0.6f * a);
        }
    }

    /** A rectangle on the pane, from (u0, v0) to (u1, v1) across and up from its middle. */
    private static void strip(VertexConsumer vc, Vec3d c, Vec3d right, Vec3d up, double u0, double v0, double u1, double v1,
                              float r, float g, float b, float a) {
        if (a <= 0.003f) return;
        vertex(vc, at(c, right, up, u0, v0), r, g, b, a);
        vertex(vc, at(c, right, up, u1, v0), r, g, b, a);
        vertex(vc, at(c, right, up, u1, v1), r, g, b, a);
        vertex(vc, at(c, right, up, u0, v1), r, g, b, a);
    }

    /** A slanted streak of light, clipped to the pane. */
    private static void glint(VertexConsumer vc, Vec3d c, Vec3d right, Vec3d up, double u, double v, double width, double length, float a) {
        double hw = DeflectionSpell.HALF_WIDTH, hh = DeflectionSpell.HALF_HEIGHT;
        double slant = length * 0.6;
        double[][] pts = {{u, v - length / 2}, {u + width, v - length / 2}, {u + width + slant, v + length / 2}, {u + slant, v + length / 2}};
        for (double[] pt : pts) {
            pt[0] = MathHelper.clamp(pt[0], -hw, hw);
            pt[1] = MathHelper.clamp(pt[1], -hh, hh);
            vertex(vc, at(c, right, up, pt[0], pt[1]), 0.9f, 0.98f, 1f, a);
        }
    }

    /** A ring on the pane around (u, v), clipped to the glass. */
    private static void ring(VertexConsumer vc, Vec3d c, Vec3d right, Vec3d up, double u, double v, double radius, double width,
                             float r, float g, float b, float a) {
        if (a <= 0.003f) return;
        double hw = DeflectionSpell.HALF_WIDTH, hh = DeflectionSpell.HALF_HEIGHT;
        int segments = 32;
        for (int i = 0; i < segments; i++) {
            double a0 = i * Math.PI * 2 / segments, a1 = (i + 1) * Math.PI * 2 / segments;
            double[][] pts = {
                    {u + Math.cos(a0) * radius, v + Math.sin(a0) * radius},
                    {u + Math.cos(a1) * radius, v + Math.sin(a1) * radius},
                    {u + Math.cos(a1) * (radius + width), v + Math.sin(a1) * (radius + width)},
                    {u + Math.cos(a0) * (radius + width), v + Math.sin(a0) * (radius + width)},
            };
            boolean outside = false;
            for (double[] pt : pts) outside |= Math.abs(pt[0]) > hw || Math.abs(pt[1]) > hh;
            if (outside) continue;
            for (double[] pt : pts) vertex(vc, at(c, right, up, pt[0], pt[1]), r, g, b, a);
        }
    }

    private static void quad(VertexConsumer vc, Vec3d c, Vec3d u, Vec3d v, float r, float g, float b, float a) {
        vertex(vc, c.subtract(u).subtract(v), r, g, b, a);
        vertex(vc, c.add(u).subtract(v), r, g, b, a);
        vertex(vc, c.add(u).add(v), r, g, b, a);
        vertex(vc, c.subtract(u).add(v), r, g, b, a);
    }

    private static Vec3d at(Vec3d c, Vec3d right, Vec3d up, double u, double v) {
        return c.add(right.multiply(u)).add(up.multiply(v));
    }

    private static void vertex(VertexConsumer vc, Vec3d p, float r, float g, float b, float a) {
        vc.vertex(pose, (float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z)).color(r, g, b, a);
    }
}
