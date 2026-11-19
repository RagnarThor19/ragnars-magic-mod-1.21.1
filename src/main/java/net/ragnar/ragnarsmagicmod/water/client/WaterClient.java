package net.ragnar.ragnarsmagicmod.water.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.ragnar.ragnarsmagicmod.water.Water;

import java.util.ArrayList;
import java.util.List;

/**
 * Client half of the Tome of Water (see Water): the ball's renderer, and its pop - the sphere swells and fades in an
 * instant while its voxels burst outward, arc down under gravity and shrink away.
 */
public final class WaterClient {
    private WaterClient() {}

    private static final int BLOOM_TICKS = 5;
    private static final int MAX_BITS = 240;

    private static final class Bit {
        Vec3d pos, prev, vel;
        final float size, spinX, spinY;
        final int life;
        int age;

        Bit(Vec3d pos, Vec3d vel, float size, float spinX, float spinY, int life) {
            this.pos = this.prev = pos;
            this.vel = vel;
            this.size = size;
            this.spinX = spinX;
            this.spinY = spinY;
            this.life = life;
        }
    }

    private record Bloom(Vec3d at, int[] age) {}

    private static final List<Bit> BITS = new ArrayList<>();
    private static final List<Bloom> BLOOMS = new ArrayList<>();

    public static void init() {
        EntityRendererRegistry.register(Water.ORB, WaterOrbRenderer::new);
        ClientPlayNetworking.registerGlobalReceiver(Water.PopPayload.ID, (payload, context) -> burst(new Vec3d(payload.at()), context.client()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            BITS.clear();
            BLOOMS.clear();
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.isPaused()) return;
            BLOOMS.removeIf(b -> ++b.age()[0] > BLOOM_TICKS);
            BITS.removeIf(b -> {
                b.prev = b.pos;
                if (client.world != null) move(client, b);
                b.vel = b.vel.multiply(0.94).add(0, -0.035, 0);
                return ++b.age > b.life;
            });
        });
        WorldRenderEvents.AFTER_TRANSLUCENT.register(WaterClient::render);
    }

    /** Breaks the ball into its surface voxels, flung outward and a little up. */
    private static void burst(Vec3d at, MinecraftClient client) {
        Random r = client.world != null ? client.world.random : Random.create();
        if (BLOOMS.size() >= 8) BLOOMS.remove(0);
        BLOOMS.add(new Bloom(at, new int[1]));
        for (float[] v : WaterVoxels.SURFACE) {
            if (r.nextFloat() < 0.35f) continue;
            Vec3d off = new Vec3d(v[0], v[1], v[2]);
            Vec3d out = off.normalize();
            double speed = 0.22 + r.nextDouble() * 0.28;
            Vec3d vel = out.multiply(speed).add((r.nextDouble() - 0.5) * 0.12, 0.12 + r.nextDouble() * 0.12, (r.nextDouble() - 0.5) * 0.12);
            float size = WaterVoxels.VOXEL * (0.8f + r.nextFloat() * 0.6f);
            if (BITS.size() >= MAX_BITS) BITS.remove(0);
            BITS.add(new Bit(at.add(off), vel, size, (r.nextFloat() - 0.5f) * 40f, (r.nextFloat() - 0.5f) * 40f, 12 + r.nextInt(8)));
        }
    }

    /** Moves a bit one axis at a time, splatting off any block it runs into instead of going through it. */
    private static void move(MinecraftClient client, Bit b) {
        double[] v = {b.vel.x, b.vel.y, b.vel.z};
        Vec3d at = b.pos;
        for (int axis = 0; axis < 3; axis++) {
            Vec3d next = at.add(axis == 0 ? v[0] : 0, axis == 1 ? v[1] : 0, axis == 2 ? v[2] : 0);
            BlockPos bp = BlockPos.ofFloored(next);
            if (client.world.getBlockState(bp).isSolidBlock(client.world, bp)) {
                v[axis] *= -0.25; // a wet slap: it barely bounces
                for (int o = 0; o < 3; o++) if (o != axis) v[o] *= 0.6;
            } else {
                at = next;
            }
        }
        b.pos = at;
        b.vel = new Vec3d(v[0], v[1], v[2]);
    }

    private static void render(WorldRenderContext context) {
        if (BITS.isEmpty() && BLOOMS.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;
        float tickDelta = context.tickCounter().getTickDelta(false);
        Vec3d cam = context.camera().getPos();
        MatrixStack ms = context.matrixStack() != null ? context.matrixStack() : new MatrixStack();
        VertexConsumerProvider.Immediate buffers = client.getBufferBuilders().getEntityVertexConsumers();
        VertexConsumer vc = buffers.getBuffer(WaterVoxels.LAYER);
        Sprite water = WaterVoxels.water();
        float[] w = WaterVoxels.WATER, p = WaterVoxels.PALE;

        for (Bloom b : BLOOMS) {
            float t = MathHelper.clamp((b.age()[0] + tickDelta) / BLOOM_TICKS, 0f, 1f);
            float grow = 1f + 1.6f * (1f - (1f - t) * (1f - t));
            Vec3d c = b.at().subtract(cam);
            ms.push();
            ms.translate(c.x, c.y, c.z);
            ms.scale(grow, grow, grow);
            int light = WorldRenderer.getLightmapCoordinates(client.world, BlockPos.ofFloored(b.at()));
            WaterVoxels.sphere(vc, ms.peek(), light, water, p[0], p[1], p[2], 0.55f * (1f - t) * (1f - t));
            ms.pop();
        }

        for (Bit b : BITS) {
            float k = MathHelper.clamp((b.age + tickDelta) / b.life, 0f, 1f);
            float h = b.size * 0.5f * (1f - k * k);
            if (h < 0.005f) continue;
            Vec3d at = b.prev.lerp(b.pos, tickDelta);
            Vec3d c = at.subtract(cam);
            float spin = b.age + tickDelta;
            ms.push();
            ms.translate(c.x, c.y, c.z);
            ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(spin * b.spinX));
            ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(spin * b.spinY));
            int light = WorldRenderer.getLightmapCoordinates(client.world, BlockPos.ofFloored(at));
            float pale = 0.35f * k;
            WaterVoxels.cube(vc, ms.peek(), light, water, 0, 0, 0, h,
                    MathHelper.lerp(pale, w[0], p[0]), MathHelper.lerp(pale, w[1], p[1]), MathHelper.lerp(pale, w[2], p[2]), 0.8f);
            ms.pop();
        }
        buffers.draw(WaterVoxels.LAYER);
    }
}
