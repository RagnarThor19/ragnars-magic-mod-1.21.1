package net.ragnar.ragnarsmagicmod.balllightning.client;

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
import net.ragnar.ragnarsmagicmod.balllightning.BallLightning;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Client half of the Tome of Ball Lightning (see BallLightning): the ball's renderer, the arcs it throws at things,
 * and the blast when it goes off - a flash of yellow light swelling out with a burst of bolts racing to the edge of
 * its reach.
 */
public final class BallLightningClient {
    private BallLightningClient() {}

    private static final int ARC_TICKS = 4, HEAVY_ARC_TICKS = 7, BOOM_TICKS = 14;
    private static final int MAX = 64;

    private record Arc(Vec3d from, Vec3d to, boolean heavy, long seed, int[] age) {}
    private record Boom(Vec3d at, float radius, long seed, int[] age) {}

    private static final List<Arc> ARCS = new ArrayList<>();
    private static final List<Boom> BOOMS = new ArrayList<>();

    public static void init() {
        EntityRendererRegistry.register(BallLightning.BALL, BallLightningRenderer::new);
        ClientPlayNetworking.registerGlobalReceiver(BallLightning.ArcPayload.ID, (payload, context) -> {
            if (ARCS.size() >= MAX) ARCS.remove(0);
            ARCS.add(new Arc(new Vec3d(payload.from()), new Vec3d(payload.to()), payload.heavy(), seed(context.client()), new int[1]));
        });
        ClientPlayNetworking.registerGlobalReceiver(BallLightning.BoomPayload.ID, (payload, context) -> {
            if (BOOMS.size() >= 8) BOOMS.remove(0);
            BOOMS.add(new Boom(new Vec3d(payload.at()), payload.radius(), seed(context.client()), new int[1]));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ARCS.clear();
            BOOMS.clear();
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.isPaused()) return;
            ARCS.removeIf(a -> ++a.age()[0] > (a.heavy() ? HEAVY_ARC_TICKS : ARC_TICKS));
            BOOMS.removeIf(b -> ++b.age()[0] > BOOM_TICKS);
        });
        WorldRenderEvents.AFTER_TRANSLUCENT.register(BallLightningClient::render);
    }

    private static long seed(MinecraftClient client) {
        return client.world != null ? client.world.random.nextLong() : System.nanoTime();
    }

    private static void render(WorldRenderContext context) {
        if (ARCS.isEmpty() && BOOMS.isEmpty()) return;
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
        VertexConsumer vc = buffers.getBuffer(Arcs.LAYER);

        for (Arc a : ARCS) {
            int age = a.age()[0];
            float life = a.heavy() ? HEAVY_ARC_TICKS : ARC_TICKS;
            float k = 1f - MathHelper.clamp((age + tickDelta) / life, 0f, 1f);
            // Flickers, and takes a new shape every tick like real lightning
            float alpha = k * (age % 2 == 0 ? 1f : 0.7f);
            Random r = Random.create(a.seed() + age * 341L);
            Vec3d from = a.from().subtract(cam), to = a.to().subtract(cam);
            Arcs.bolt(vc, pose, from, to, r, a.heavy() ? 1.4f : 0.9f, alpha, true);
            if (a.heavy()) Arcs.bolt(vc, pose, from, to, r, 0.6f, alpha * 0.6f, false);
            Arcs.glow(vc, pose, to, right, up, (a.heavy() ? 0.35f : 0.2f) * k, 1f, 0.85f, 0.35f, 0.6f * k);
        }

        for (Boom b : BOOMS) {
            int age = b.age()[0];
            float t = MathHelper.clamp((age + tickDelta) / BOOM_TICKS, 0f, 1f);
            float grow = 1f - (1f - t) * (1f - t) * (1f - t);
            float fade = (1f - t) * (1f - t);
            Vec3d c = b.at().subtract(cam);
            // A swelling flash of light
            Arcs.glow(vc, pose, c, right, up, b.radius() * (0.35f + 0.65f * grow), 1f, 0.75f, 0.2f, 0.55f * fade);
            Arcs.glow(vc, pose, c, right, up, b.radius() * 0.45f * (0.4f + 0.6f * grow), 1f, 0.95f, 0.7f, 0.9f * fade);
            // Bolts racing out to the edge of the blast, crackling into new shapes each tick
            Random r = Random.create(b.seed() + age * 7919L);
            int bolts = t < 0.5f ? 18 : 10;
            for (int i = 0; i < bolts; i++) {
                Vec3d dir = Arcs.randomDir(r);
                double reach = b.radius() * grow * (0.6 + r.nextDouble() * 0.5);
                Arcs.bolt(vc, pose, c, c.add(dir.multiply(reach)), r, 1.2f * (1f - t * 0.5f), fade, true);
            }
        }
        buffers.draw(Arcs.LAYER);
    }
}
