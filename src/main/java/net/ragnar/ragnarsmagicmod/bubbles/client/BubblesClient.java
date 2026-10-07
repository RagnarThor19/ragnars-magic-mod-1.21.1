package net.ragnar.ragnarsmagicmod.bubbles.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.ragnar.ragnarsmagicmod.bubbles.Bubbles;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Client half of the Tome of Bubbles (see Bubbles): the bubble's renderer, and the pop - the film flashing out in a
 * ring as it goes, and breaking into a spray of little coloured flakes that fly off and fall away.
 */
public final class BubblesClient {
    private BubblesClient() {}

    private static final int FLAKE_TICKS = 12, RING_TICKS = 5;

    private static final class Flake {
        Vec3d pos, prev, vel;
        final float[] col;
        final float size;
        int age;

        Flake(Vec3d pos, Vec3d vel, float[] col, float size) {
            this.pos = this.prev = pos;
            this.vel = vel;
            this.col = col;
            this.size = size;
        }
    }

    private record Ring(Vec3d at, float radius, int[] age) {}

    private static final List<Flake> FLAKES = new ArrayList<>();
    private static final List<Ring> RINGS = new ArrayList<>();

    public static void init() {
        EntityRendererRegistry.register(Bubbles.BUBBLE, BubbleRenderer::new);
        ClientPlayNetworking.registerGlobalReceiver(Bubbles.PopPayload.ID, (payload, context) -> {
            Vec3d at = new Vec3d(payload.at());
            float r = payload.radius();
            Random rand = Random.create();
            if (RINGS.size() >= 8) RINGS.remove(0);
            RINGS.add(new Ring(at, r, new int[1]));
            for (int i = 0; i < 70 && FLAKES.size() < 400; i++) {
                Vec3d d = new Vec3d(rand.nextGaussian(), rand.nextGaussian(), rand.nextGaussian()).normalize();
                FLAKES.add(new Flake(at.add(d.multiply(r)), d.multiply(0.12 + rand.nextDouble() * 0.18).add(0, 0.05, 0),
                        BubbleDraw.film(rand.nextDouble()), 0.06f + rand.nextFloat() * 0.08f));
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            FLAKES.clear();
            RINGS.clear();
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.isPaused()) return;
            FLAKES.removeIf(f -> {
                f.prev = f.pos;
                f.pos = f.pos.add(f.vel);
                f.vel = f.vel.multiply(0.86).add(0, -0.025, 0);
                return ++f.age > FLAKE_TICKS;
            });
            RINGS.removeIf(g -> ++g.age()[0] > RING_TICKS);
        });
        WorldRenderEvents.AFTER_TRANSLUCENT.register(BubblesClient::render);
    }

    private static void render(WorldRenderContext context) {
        if (FLAKES.isEmpty() && RINGS.isEmpty()) return;
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
        VertexConsumer vc = buffers.getBuffer(BubbleDraw.LAYER);

        for (Flake f : FLAKES) {
            float k = (f.age + tickDelta) / FLAKE_TICKS;
            Vec3d p = f.prev.lerp(f.pos, tickDelta).subtract(cam);
            BubbleDraw.square(vc, pose, p, right, up, f.size * (1f - k * 0.5f), f.col[0], f.col[1], f.col[2], 0.85f * (1f - k));
        }
        // The film flashing outward as it bursts: a blocky ring of light facing you
        for (Ring g : RINGS) {
            float k = MathHelper.clamp((g.age()[0] + tickDelta) / RING_TICKS, 0f, 1f);
            float rad = g.radius() * (1f + 0.5f * k);
            Vec3d c = g.at().subtract(cam);
            int n = 28;
            for (int i = 0; i < n; i++) {
                double a = Math.PI * 2 * i / n;
                Vec3d p = c.add(right.multiply(Math.cos(a) * rad)).add(up.multiply(Math.sin(a) * rad));
                float[] col = BubbleDraw.film(i / (double) n);
                BubbleDraw.square(vc, pose, p, right, up, 0.22f * (1f - k * 0.6f), col[0], col[1], col[2], 0.8f * (1f - k));
            }
        }
        buffers.draw(BubbleDraw.LAYER);
    }
}
