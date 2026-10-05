package net.ragnar.ragnarsmagicmod.shadowhands.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.ragnar.ragnarsmagicmod.shadowhands.Grasp;
import net.ragnar.ragnarsmagicmod.shadowhands.ShadowHands;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Client half of the Tome of Unseen Hands (see ShadowHands). The pool: a stain of black creeping block by block over
 * the real ground, its edge reaching out in tendrils and burning purple, faint rings of light rolling through it. As
 * the edge passes, stray hands claw up out of it at nothing. When the server says who's caught, three to seven arms
 * burst up around each of them and close on them (see Hand), squeezing in time with the damage. Then they sink and the
 * pool drains away.
 */
public final class ShadowHandsClient {
    private ShadowHandsClient() {}

    private static final double CELL = 0.5;
    private static final int AMBIENT_HANDS = 8;

    private record Cell(double x, double z, double y, double dist, double angle) {}

    private static final class Pool {
        final ClientWorld world;
        final Vec3d center;
        final float radius;
        final List<Cell> cells = new ArrayList<>();
        final Map<Long, Double> ground = new HashMap<>();
        final List<Hand> hands = new ArrayList<>();
        final List<double[]> pendingAmbient = new ArrayList<>(); // {dist, angle, delay}
        final long seed;
        int age;

        Pool(ClientWorld world, Vec3d center, float radius, long seed) {
            this.world = world;
            this.center = center;
            this.radius = radius;
            this.seed = seed;
        }

        int releaseAt() {
            return ShadowHands.WAVE_TICKS + ShadowHands.SQUEEZE_TICKS;
        }
    }

    private static final Map<Integer, Pool> POOLS = new HashMap<>();

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(ShadowHands.StartPayload.ID, (payload, context) -> {
            ClientWorld world = context.client().world;
            if (world == null) return;
            Pool pool = new Pool(world, new Vec3d(payload.center()), payload.radius(), world.random.nextLong());
            mapGround(pool);
            Random r = Random.create(pool.seed);
            for (int i = 0; i < AMBIENT_HANDS; i++) {
                pool.pendingAmbient.add(new double[]{1.5 + r.nextDouble() * (pool.radius - 2.5), r.nextDouble() * Math.PI * 2, r.nextInt(20)});
            }
            POOLS.put(payload.id(), pool);
        });
        ClientPlayNetworking.registerGlobalReceiver(ShadowHands.GrabPayload.ID, (payload, context) -> {
            Pool pool = POOLS.get(payload.id());
            if (pool == null) return;
            for (int id : payload.victims()) {
                Entity e = pool.world.getEntityById(id);
                if (e != null) grab(pool, e);
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> POOLS.clear());
        ClientTickEvents.END_CLIENT_TICK.register(ShadowHandsClient::tick);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(ShadowHandsClient::render);
    }

    // ---------------------------------------------------------------------
    // Setting up
    // ---------------------------------------------------------------------

    /** Finds the floor under every cell of the pool, so the shadow lies on the real ground, steps and all. */
    private static void mapGround(Pool pool) {
        int n = (int) Math.ceil((pool.radius + 1.5) / CELL);
        BlockPos.Mutable m = new BlockPos.Mutable();
        int cy = MathHelper.floor(pool.center.y + 0.01);
        for (int i = -n; i <= n; i++) {
            for (int j = -n; j <= n; j++) {
                double x = pool.center.x + i * CELL, z = pool.center.z + j * CELL;
                double dx = x - pool.center.x, dz = z - pool.center.z;
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > pool.radius + 1.5) continue;
                Double y = floorAt(pool.world, m, x, cy, z);
                if (y == null) continue;
                pool.ground.put(key(x, z), y);
                pool.cells.add(new Cell(x, z, y, d, Math.atan2(dz, dx)));
            }
        }
    }

    /** The top of the highest solid thing a few blocks above or below {@code cy} at x, z, with open space over it. */
    private static Double floorAt(ClientWorld world, BlockPos.Mutable m, double x, int cy, double z) {
        int bx = MathHelper.floor(x), bz = MathHelper.floor(z);
        for (int y = cy + 3; y >= cy - 4; y--) {
            m.set(bx, y, bz);
            BlockState s = world.getBlockState(m);
            VoxelShape shape = s.getCollisionShape(world, m);
            if (shape.isEmpty()) continue;
            m.set(bx, y + 1, bz);
            if (!world.getBlockState(m).getCollisionShape(world, m).isEmpty() && y < cy + 3) continue;
            return y + shape.getMax(Direction.Axis.Y);
        }
        return null;
    }

    private static long key(double x, double z) {
        return ((long) MathHelper.floor(x / CELL) << 32) ^ (MathHelper.floor(z / CELL) & 0xFFFFFFFFL);
    }

    private static double groundAt(Pool pool, double x, double z, double fallback) {
        // Nearest mapped cell
        double cx = pool.center.x + Math.round((x - pool.center.x) / CELL) * CELL;
        double cz = pool.center.z + Math.round((z - pool.center.z) / CELL) * CELL;
        Double y = pool.ground.get(key(cx, cz));
        return y != null ? y : fallback;
    }

    /** Three to seven arms around {@code e}, more for bigger prey, each grabbing it at a different height. */
    private static void grab(Pool pool, Entity e) {
        Random r = Random.create(pool.seed ^ e.getId() * 341873128712L);
        float w = e.getWidth(), h = e.getHeight();
        int n = MathHelper.clamp(Math.round(1.5f + w * 1.5f + h * 0.6f), 3, 6);
        float k = MathHelper.clamp(0.75f + w * 0.3f + h * 0.1f, 0.85f, 1.7f);
        double base = r.nextDouble() * Math.PI * 2;
        int[] pulses = new int[ShadowHands.PULSES];
        for (int i = 0; i < pulses.length; i++) pulses[i] = pool.age + ShadowHands.PULSE_EVERY * (i + 1);
        for (int i = 0; i < n; i++) {
            double theta = base + Math.PI * 2 * i / n + (r.nextDouble() - 0.5) * 0.5;
            double reach = w / 2 + 0.9 + r.nextDouble() * 0.7 * k;
            double ax = e.getX() + Math.cos(theta) * reach, az = e.getZ() + Math.sin(theta) * reach;
            Vec3d anchor = new Vec3d(ax, groundAt(pool, ax, az, e.getY()), az);
            float hf = 0.22f + 0.6f * ((i * 0.618f) % 1f);
            pool.hands.add(new Hand(anchor, theta, k, hf, r.nextLong(), pool.age + (i % 3), pool.releaseAt(), e, Vec3d.ZERO, pulses));
        }
    }

    // ---------------------------------------------------------------------
    // Ticking
    // ---------------------------------------------------------------------

    private static void tick(MinecraftClient client) {
        if (client.isPaused() || POOLS.isEmpty()) return;
        Iterator<Pool> it = POOLS.values().iterator();
        while (it.hasNext()) {
            Pool pool = it.next();
            if (pool.world != client.world || ++pool.age > pool.releaseAt() + ShadowHands.AFTER_TICKS) {
                it.remove();
                continue;
            }
            // Stray hands claw up out of the pool as its edge passes over them
            double front = pool.radius * Grasp.frontAt((float) pool.age / ShadowHands.WAVE_TICKS);
            Iterator<double[]> pending = pool.pendingAmbient.iterator();
            while (pending.hasNext()) {
                double[] a = pending.next();
                if (a[0] > front - 0.5) continue;
                if (a[2]-- > 0) continue;
                pending.remove();
                double x = pool.center.x + Math.cos(a[1]) * a[0], z = pool.center.z + Math.sin(a[1]) * a[0];
                Vec3d anchor = new Vec3d(x, groundAt(pool, x, z, pool.center.y), z);
                double theta = pool.world.random.nextDouble() * Math.PI * 2;
                Vec3d fist = anchor.add(-Math.cos(theta) * 0.7, 1.3 + pool.world.random.nextDouble() * 0.9, -Math.sin(theta) * 0.7);
                pool.hands.add(new Hand(anchor, theta, 0.7f + pool.world.random.nextFloat() * 0.3f, 0f, pool.world.random.nextLong(),
                        pool.age, pool.releaseAt() - pool.world.random.nextInt(30), null, fist, new int[0]));
            }
        }
    }

    // ---------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------

    private static void render(WorldRenderContext context) {
        if (POOLS.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        float tickDelta = context.tickCounter().getTickDelta(false);
        Vec3d cam = context.camera().getPos();
        MatrixStack ms = context.matrixStack() != null ? context.matrixStack() : new MatrixStack();
        Matrix4f pose = ms.peek().getPositionMatrix();
        VertexConsumerProvider.Immediate buffers = client.getBufferBuilders().getEntityVertexConsumers();

        VertexConsumer vc = buffers.getBuffer(Shapes.POOL);
        for (Pool pool : POOLS.values()) pool(vc, pose, cam, pool, pool.age + tickDelta, false);
        buffers.draw(Shapes.POOL);

        vc = buffers.getBuffer(Shapes.FLESH);
        for (Pool pool : POOLS.values()) {
            for (Hand h : pool.hands) h.draw(vc, pose, pool.world, cam, pool.age + tickDelta, tickDelta, false);
        }
        buffers.draw(Shapes.FLESH);

        vc = buffers.getBuffer(Shapes.AURA);
        for (Pool pool : POOLS.values()) {
            pool(vc, pose, cam, pool, pool.age + tickDelta, true);
            for (Hand h : pool.hands) h.draw(vc, pose, pool.world, cam, pool.age + tickDelta, tickDelta, true);
        }
        buffers.draw(Shapes.AURA);
    }

    /**
     * The pool, {@code t} ticks in: black where the shadow has reached ({@code glow} false), or the light in it - the
     * burning edge and the rings rolling through - ({@code glow} true).
     */
    private static void pool(VertexConsumer vc, Matrix4f pose, Vec3d cam, Pool pool, float t, boolean glow) {
        float front = pool.radius * Grasp.frontAt(t / ShadowHands.WAVE_TICKS);
        float drain = MathHelper.clamp((t - pool.releaseAt()) / ShadowHands.AFTER_TICKS, 0f, 1f);
        float fade = 1f - drain * drain;
        front *= 1f - 0.35f * drain;
        if (fade <= 0.01f) return;
        // Brighter while it's squeezing, a throb at every crush
        float throb = 0f;
        if (t > ShadowHands.WAVE_TICKS && t < pool.releaseAt() + 4) {
            float since = (t - ShadowHands.WAVE_TICKS) % ShadowHands.PULSE_EVERY;
            throb = (float) Math.exp(-since * since / 8.0);
        }
        float wavePhase = (float) (pool.seed % 628) / 100f;
        for (Cell c : pool.cells) {
            double a = c.angle;
            // A wobbling edge, with tendrils reaching out ahead of it
            double tendril = Math.pow(Math.max(0, Math.sin(13 * a + 2 * Math.sin(t * 0.05 + wavePhase))), 6) * 1.1;
            double edge = front * (1 + 0.07 * Math.sin(5 * a + t * 0.13 + wavePhase) + 0.05 * Math.sin(9 * a - t * 0.21))
                    + tendril * Math.min(1, front / 3);
            double in = edge - c.dist;
            if (in < 0) continue;
            double y = c.y + 0.02 - cam.y;
            double x = c.x - cam.x, z = c.z - cam.z;
            if (!glow) {
                float alpha = (float) (0.88 * MathHelper.clamp(in / 1.2, 0.35, 1.0)) * fade;
                Shapes.tile(vc, pose, x, y, z, CELL, 0.015f, 0f, 0.03f, alpha);
            } else {
                // The burning edge
                if (in < 0.9) {
                    float e = (float) (1 - in / 0.9);
                    Shapes.tile(vc, pose, x, y + 0.005, z, CELL, 0.42f, 0.0f, 0.75f, (0.4f * e + 0.08f) * fade);
                }
                // Rings of faint light rolling inward through it, and a throb at every squeeze
                double ring = Math.sin(c.dist * 1.6 + t * 0.22 + 2.5 * Math.sin(a * 3 + t * 0.03));
                float va = ring > 0.85 ? (float) ((ring - 0.85) / 0.15) * 0.16f : 0f;
                va += throb * 0.22f * (float) (1 - c.dist / pool.radius);
                if (va > 0.01f) Shapes.tile(vc, pose, x, y + 0.004, z, CELL, 0.32f, 0.0f, 0.6f, va * fade);
            }
        }
    }
}
