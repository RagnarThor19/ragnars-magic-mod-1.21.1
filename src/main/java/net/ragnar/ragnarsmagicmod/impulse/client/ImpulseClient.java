package net.ragnar.ragnarsmagicmod.impulse.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.impulse.Impulse;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Client half of the Tome of Impulse (see Impulse): the cube's renderer, and the blast - a flash of blue light and a
 * ring of force racing out flat along the surface it was stuck to (or around the camera's view, if it went off in
 * the air), fading as it reaches the edge of the push.
 */
public final class ImpulseClient {
    private ImpulseClient() {}

    static final RenderLayer GLOW = RenderLayer.of("ragnarsmagicmod_impulse_glow",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 8192, false, true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.LIGHTNING_TRANSPARENCY)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    private static final int BLAST_TICKS = 12, SEGMENTS = 48;

    private record Blast(Vec3d at, Vec3d normal, float radius, int[] age) {}

    private static final List<Blast> BLASTS = new ArrayList<>();

    public static void init() {
        EntityRendererRegistry.register(Impulse.CUBE, ImpulseCubeRenderer::new);
        ClientPlayNetworking.registerGlobalReceiver(Impulse.BlastPayload.ID, (payload, context) -> {
            if (BLASTS.size() >= 8) BLASTS.remove(0);
            BLASTS.add(new Blast(new Vec3d(payload.at()), new Vec3d(payload.normal()), payload.radius(), new int[1]));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> BLASTS.clear());
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!client.isPaused()) BLASTS.removeIf(b -> ++b.age()[0] > BLAST_TICKS);
        });
        WorldRenderEvents.AFTER_TRANSLUCENT.register(ImpulseClient::render);
    }

    private static void render(WorldRenderContext context) {
        if (BLASTS.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        float tickDelta = context.tickCounter().getTickDelta(false);
        Vec3d cam = context.camera().getPos();
        Vec3d fwd = Vec3d.fromPolar(context.camera().getPitch(), context.camera().getYaw());
        Vec3d right = fwd.crossProduct(new Vec3d(0, 1, 0));
        right = right.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : right.normalize();
        Vec3d up = right.crossProduct(fwd).normalize();

        MatrixStack ms = context.matrixStack() != null ? context.matrixStack() : new MatrixStack();
        Matrix4f pose = ms.peek().getPositionMatrix();
        VertexConsumerProvider.Immediate buffers = client.getBufferBuilders().getEntityVertexConsumers();
        VertexConsumer vc = buffers.getBuffer(GLOW);

        for (Blast b : BLASTS) {
            float t = MathHelper.clamp((b.age()[0] + tickDelta) / BLAST_TICKS, 0f, 1f);
            float grow = 1f - (1f - t) * (1f - t) * (1f - t);
            float fade = (1f - t) * (1f - t);
            Vec3d c = b.at().subtract(cam);

            // The flash
            glow(vc, pose, c, right, up, b.radius() * 0.5f * (0.4f + 0.6f * grow), 0.15f, 0.3f, 1f, 0.6f * fade);
            glow(vc, pose, c, right, up, b.radius() * 0.2f * (0.5f + 0.5f * grow), 0.6f, 0.85f, 1f, 0.9f * fade);

            // The ring of force: flat along the surface it was stuck to, or facing the camera in mid-air
            Vec3d u, v;
            if (b.normal().lengthSquared() > 0.5) {
                Vec3d n = b.normal();
                u = Math.abs(n.y) < 0.9 ? n.crossProduct(new Vec3d(0, 1, 0)).normalize() : n.crossProduct(new Vec3d(1, 0, 0)).normalize();
                v = n.crossProduct(u).normalize();
                c = c.add(n.multiply(0.05));
            } else {
                u = right;
                v = up;
            }
            float r = b.radius() * grow;
            ring(vc, pose, c, u, v, r, 0.5f + 0.6f * (1f - t), 0.2f, 0.4f, 1f, 0.7f * fade);
            ring(vc, pose, c, u, v, r * 0.98f, 0.18f, 0.7f, 0.9f, 1f, 0.9f * fade);
            ring(vc, pose, c, u, v, r * 0.6f, 0.3f, 0.15f, 0.3f, 0.9f, 0.4f * fade);
        }
        buffers.draw(GLOW);
    }

    /** A flat band from {@code r - width} out to {@code r}, around {@code c} in the plane of {@code u} and {@code v}. */
    private static void ring(VertexConsumer vc, Matrix4f pose, Vec3d c, Vec3d u, Vec3d v, float r, float width,
                             float red, float green, float blue, float alpha) {
        float in = Math.max(0f, r - width);
        for (int i = 0; i < SEGMENTS; i++) {
            double a0 = Math.PI * 2 * i / SEGMENTS, a1 = Math.PI * 2 * (i + 1) / SEGMENTS;
            Vec3d d0 = u.multiply(Math.cos(a0)).add(v.multiply(Math.sin(a0)));
            Vec3d d1 = u.multiply(Math.cos(a1)).add(v.multiply(Math.sin(a1)));
            // Bright at the outer edge, fading to nothing inside
            vertex(vc, pose, c.add(d0.multiply(in)), red, green, blue, 0f);
            vertex(vc, pose, c.add(d0.multiply(r)), red, green, blue, alpha);
            vertex(vc, pose, c.add(d1.multiply(r)), red, green, blue, alpha);
            vertex(vc, pose, c.add(d1.multiply(in)), red, green, blue, 0f);
        }
    }

    /** A soft glow facing the camera: a few turned squares stacked up. Points are relative to the camera. */
    static void glow(VertexConsumer vc, Matrix4f pose, Vec3d c, Vec3d right, Vec3d up, float size,
                     float r, float g, float b, float alpha) {
        for (int layer = 0; layer < 3; layer++) {
            float s = size * (1f - layer * 0.28f);
            double turn = layer * Math.PI / 6;
            Vec3d x = right.multiply(Math.cos(turn) * s).add(up.multiply(Math.sin(turn) * s));
            Vec3d y = up.multiply(Math.cos(turn) * s).subtract(right.multiply(Math.sin(turn) * s));
            float a = alpha * (0.35f + layer * 0.3f);
            vertex(vc, pose, c.subtract(x).subtract(y), r, g, b, a);
            vertex(vc, pose, c.add(x).subtract(y), r, g, b, a);
            vertex(vc, pose, c.add(x).add(y), r, g, b, a);
            vertex(vc, pose, c.subtract(x).add(y), r, g, b, a);
        }
    }

    private static void vertex(VertexConsumer vc, Matrix4f pose, Vec3d p, float r, float g, float b, float a) {
        vc.vertex(pose, (float) p.x, (float) p.y, (float) p.z).color(r, g, b, MathHelper.clamp(a, 0f, 1f));
    }
}
