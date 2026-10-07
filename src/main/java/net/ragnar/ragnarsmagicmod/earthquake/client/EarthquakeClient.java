package net.ragnar.ragnarsmagicmod.earthquake.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.OverlayVertexConsumer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.model.ModelLoader;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.ragnar.ragnarsmagicmod.earthquake.Earthquake;
import net.ragnar.ragnarsmagicmod.earthquake.EarthquakeCast;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Client half of the Tome of Earthquake (see Earthquake). Nothing here touches a real block: everything is drawn
 * over the world and forgotten.
 * <ul>
 *   <li><b>The charge.</b> Fissures run out from the caster's feet across the ground, drawn with Minecraft's own
 *       block-breaking cracks, deepening as they go. Grit spits up along them, pebbles hop, and under a roof dust
 *       starts trickling down.</li>
 *   <li><b>The shock.</b> A wave rolls out through the ground: copies of the real blocks heave up and tip outward as
 *       it passes and settle again behind it. The fissures burst open into tilted slabs that jut up out of the
 *       ground and sink back over a couple of seconds. Chunks of earth are flung out, dust billows, and under a roof
 *       rubble rains down.</li>
 *   <li><b>The aftershocks.</b> Two smaller, quicker ripples.</li>
 * </ul>
 * The ground is surveyed once per cast, when the charge begins; after that each frame only draws the blocks the wave
 * is passing over right now, plus the fissures.
 */
public final class EarthquakeClient {
    private EarthquakeClient() {}

    /** How far behind the wave's front the ground is still heaving, per pulse. */
    private static final double[] BAND = {3.2, 2.2, 1.8};
    /** How high the ground heaves, per pulse (at the middle; less toward the edge). */
    private static final float[] HEAVE = {1.25f, 0.5f, 0.32f};
    /** How long each pulse's wave takes to reach the edge. */
    private static final int[] PULSE_TICKS = {EarthquakeCast.WAVE_TICKS, 14, 12};
    /** How long the fissure slabs stand before they've sunk back. */
    private static final int SLAB_TICKS = 55;
    private static final int MAX_DEBRIS = 140;
    /** The ground this close to the caster stays still, so nothing gets drawn over them. */
    private static final double STILL = 2.2;
    /** How far below the caster's feet it looks for ground, and above it for a roof. */
    private static final int DOWN = 12, UP = 4, ROOF = 10;

    /** A patch of ground: its top block, and the block overhead if there's a roof within reach. */
    private static final class Column {
        final BlockPos pos;
        final BlockState state;
        final double dist;
        final Vec3d out;
        @Nullable final BlockPos roof;
        @Nullable final BlockState roofState;
        @Nullable Crack crack;

        Column(BlockPos pos, BlockState state, double dist, Vec3d out, @Nullable BlockPos roof, @Nullable BlockState roofState) {
            this.pos = pos;
            this.state = state;
            this.dist = dist;
            this.out = out;
            this.roof = roof;
            this.roofState = roofState;
        }
    }

    /** One block of a fissure: how far along the fissure it is, and how its slab tips when it bursts open. */
    private static final class Crack {
        final Column col;
        final double along;
        final Vector3f axis;
        final float tilt, lift;
        boolean opened;

        Crack(Column col, double along, Vector3f axis, float tilt, float lift) {
            this.col = col;
            this.along = along;
            this.axis = axis;
            this.tilt = tilt;
            this.lift = lift;
        }
    }

    /** A chunk of earth (or roof) flying or falling. */
    private static final class Debris {
        final BlockState state;
        Vec3d pos, prev, vel;
        final Vector3f axis;
        final float size, spinSpeed;
        float spin, prevSpin;
        final double floorY;
        int age;

        Debris(BlockState state, Vec3d pos, Vec3d vel, Vector3f axis, float size, float spinSpeed, double floorY) {
            this.state = state;
            this.pos = this.prev = pos;
            this.vel = vel;
            this.axis = axis;
            this.size = size;
            this.spinSpeed = spinSpeed;
            this.floorY = floorY;
        }
    }

    private static final class Quake {
        final int casterId;
        final Vec3d center;
        final long seed;
        int age;
        /** When each pulse went off (in this quake's ticks), or -1 until it does. */
        final int[] pulseAt = {-1, -1, -1};
        /** How far through the columns each pulse has got, for the one-off effects as it reaches them. */
        final int[] reached = new int[3];
        boolean cancelled;
        int cancelledAt;
        final List<Column> columns = new ArrayList<>();
        final List<Crack> cracks = new ArrayList<>();
        final List<Column> roofs = new ArrayList<>();
        final List<Debris> debris = new ArrayList<>();
        /** How far the cracks had run last tick, for the effects as they reach new blocks. */
        double crackFront;

        Quake(int casterId, Vec3d center, long seed) {
            this.casterId = casterId;
            this.center = center;
            this.seed = seed;
        }

        boolean shocked() {
            return pulseAt[0] >= 0;
        }

        /** Ticks since the main shock, or a negative number before it. */
        float sinceShock(float tickDelta) {
            return shocked() ? age - pulseAt[0] + tickDelta : -1;
        }
    }

    private static final Map<Integer, Quake> QUAKES = new HashMap<>();

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(Earthquake.ChargePayload.ID, (payload, context) -> {
            ClientWorld world = context.client().world;
            if (world == null) return;
            Quake q = new Quake(payload.casterId(), new Vec3d(payload.center()), payload.seed());
            survey(world, q);
            fissures(q);
            QUAKES.put(q.casterId, q);
        });
        ClientPlayNetworking.registerGlobalReceiver(Earthquake.ShockPayload.ID, (payload, context) -> {
            Quake q = QUAKES.get(payload.casterId());
            if (q == null) return;
            if (payload.pulse() < 0) {
                q.cancelled = true;
                q.cancelledAt = q.age;
            } else if (payload.pulse() < q.pulseAt.length) {
                q.pulseAt[payload.pulse()] = q.age;
                ClientWorld world = context.client().world;
                if (world != null && payload.pulse() == 0) burst(world, q);
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> QUAKES.clear());
        ClientTickEvents.END_CLIENT_TICK.register(EarthquakeClient::tick);
        WorldRenderEvents.AFTER_ENTITIES.register(EarthquakeClient::render);
    }

    // ---------------------------------------------------------------------
    // Surveying the ground, once
    // ---------------------------------------------------------------------

    /** Finds the ground (a solid top with room above it) under every spot in reach, and any roof over it. */
    private static void survey(ClientWorld world, Quake q) {
        Vec3d o = q.center;
        int r = (int) Math.ceil(EarthquakeCast.RADIUS);
        int baseY = (int) Math.floor(o.y);
        BlockPos.Mutable p = new BlockPos.Mutable();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int x = (int) Math.floor(o.x) + dx, z = (int) Math.floor(o.z) + dz;
                double fx = x + 0.5 - o.x, fz = z + 0.5 - o.z;
                double dist = Math.sqrt(fx * fx + fz * fz);
                if (dist > EarthquakeCast.RADIUS) continue;
                boolean roomAbove = false;
                for (int y = baseY + UP; y >= baseY - DOWN; y--) {
                    p.set(x, y, z);
                    BlockState s = world.getBlockState(p);
                    boolean solid = !s.isAir() && !s.getCollisionShape(world, p).isEmpty();
                    if (!solid) {
                        roomAbove = true;
                        continue;
                    }
                    if (!roomAbove) continue; // still inside rock: keep looking down for a floor
                    if (s.getRenderType() != BlockRenderType.MODEL) break;
                    BlockPos ground = p.toImmutable();
                    // A roof over it, close enough to shake loose
                    BlockPos roof = null;
                    BlockState roofState = null;
                    for (int up = 2; up <= ROOF; up++) {
                        BlockPos above = ground.up(up);
                        BlockState a = world.getBlockState(above);
                        if (!a.isAir() && !a.getCollisionShape(world, above).isEmpty()) {
                            if (a.getRenderType() == BlockRenderType.MODEL) {
                                roof = above;
                                roofState = a;
                            }
                            break;
                        }
                    }
                    Vec3d out = dist < 1e-3 ? new Vec3d(1, 0, 0) : new Vec3d(fx / dist, 0, fz / dist);
                    Column col = new Column(ground, s, dist, out, roof, roofState);
                    q.columns.add(col);
                    if (roof != null) q.roofs.add(col);
                    break;
                }
            }
        }
        q.columns.sort((a, b) -> Double.compare(a.dist, b.dist));
    }

    /** Lays out the fissures: a dozen or so cracks wandering out from the middle, the odd one forking. */
    private static void fissures(Quake q) {
        Map<Long, Column> byXz = new HashMap<>();
        for (Column c : q.columns) byXz.put(key(c.pos.getX(), c.pos.getZ()), c);
        Map<Long, Crack> taken = new HashMap<>();
        Random r = Random.create(q.seed);
        int n = 10 + r.nextInt(4);
        for (int i = 0; i < n; i++) {
            double angle = i * Math.PI * 2 / n + (r.nextDouble() - 0.5) * 0.45;
            double length = EarthquakeCast.RADIUS * (0.5 + r.nextDouble() * 0.4);
            grow(q, byXz, taken, r, q.center.x, q.center.z, angle, 0.8, length, true);
        }
    }

    private static void grow(Quake q, Map<Long, Column> byXz, Map<Long, Crack> taken, Random r,
                             double x, double z, double angle, double along, double length, boolean canFork) {
        while (along < length) {
            angle += r.nextGaussian() * 0.22;
            x += Math.cos(angle) * 0.6;
            z += Math.sin(angle) * 0.6;
            along += 0.6;
            int bx = MathHelper.floor(x), bz = MathHelper.floor(z);
            long k = key(bx, bz);
            Column col = byXz.get(k);
            if (col != null && col.dist > STILL && !taken.containsKey(k)) {
                Vector3f axis = new Vector3f((float) r.nextGaussian(), 0, (float) r.nextGaussian());
                if (axis.lengthSquared() < 1e-4f) axis.set(1, 0, 0);
                axis.normalize();
                float tilt = (12f + r.nextFloat() * 22f) * (r.nextBoolean() ? 1 : -1);
                // Taller slabs near the middle
                float lift = (float) (0.45 + 0.75 * (1 - along / EarthquakeCast.RADIUS)) * (0.7f + r.nextFloat() * 0.5f);
                Crack crack = new Crack(col, along, axis, tilt, lift);
                taken.put(k, crack);
                col.crack = crack;
                q.cracks.add(crack);
            }
            if (canFork && along > 4 && r.nextFloat() < 0.045f) {
                double side = (r.nextBoolean() ? 1 : -1) * (0.5 + r.nextDouble() * 0.5);
                grow(q, byXz, taken, r, x, z, angle + side, along, along + (length - along) * (0.4 + r.nextDouble() * 0.4), false);
            }
        }
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    // ---------------------------------------------------------------------
    // Ticking
    // ---------------------------------------------------------------------

    private static void tick(MinecraftClient client) {
        if (client.world == null || client.isPaused() || QUAKES.isEmpty()) return;
        ClientWorld world = client.world;
        Iterator<Quake> it = QUAKES.values().iterator();
        while (it.hasNext()) {
            Quake q = it.next();
            q.age++;
            if (q.cancelled) {
                tickDebris(world, q);
                if (q.age - q.cancelledAt > 20 && q.debris.isEmpty()) it.remove();
                continue;
            }
            if (!q.shocked()) charging(world, q);
            for (int p = 0; p < q.pulseAt.length; p++) if (q.pulseAt[p] >= 0) rolling(world, q, p);
            if (q.shocked()) slabsSettling(world, q);
            tickDebris(world, q);
            boolean over = q.shocked() && q.age - q.pulseAt[0] > Math.max(SLAB_TICKS + EarthquakeCast.WAVE_TICKS, 70);
            if ((over && q.debris.isEmpty()) || q.age > 600) it.remove();
        }
    }

    /** How far the cracks have run, {@code age} ticks into the charge: to their tips just as it goes off. */
    private static double crackFront(float age) {
        float k = MathHelper.clamp(age / EarthquakeCast.CHARGE_TICKS, 0f, 1f);
        return EarthquakeCast.RADIUS * 0.95 * Math.pow(k, 0.85);
    }

    /** Grit spitting up as the cracks reach new ground, pebbles hopping, dust trickling from the roof. */
    private static void charging(ClientWorld world, Quake q) {
        Random r = world.random;
        float k = MathHelper.clamp(q.age / (float) EarthquakeCast.CHARGE_TICKS, 0f, 1f);
        double front = crackFront(q.age);
        int sounds = 0;
        for (Crack c : q.cracks) {
            if (c.along <= q.crackFront || c.along > front) continue;
            Vec3d top = Vec3d.ofBottomCenter(c.col.pos.up());
            world.addParticle(new BlockStateParticleEffect(ParticleTypes.BLOCK, c.col.state), top.x, top.y + 0.05, top.z,
                    (r.nextDouble() - 0.5) * 0.2, 0.15 + r.nextDouble() * 0.15, (r.nextDouble() - 0.5) * 0.2);
            if (sounds < 1 && r.nextFloat() < 0.12f) {
                sounds++;
                world.playSound(top.x, top.y, top.z, c.col.state.getSoundGroup().getBreakSound(), SoundCategory.BLOCKS, 0.7f, 0.6f + r.nextFloat() * 0.2f, false);
            }
        }
        q.crackFront = front;

        // Grit and pebbles bouncing along the open cracks, more and more as it builds
        int specks = (int) (q.cracks.size() * 0.04 * (0.3 + k));
        for (int i = 0; i < specks && !q.cracks.isEmpty(); i++) {
            Crack c = q.cracks.get(r.nextInt(q.cracks.size()));
            if (c.along > front) continue;
            Vec3d top = Vec3d.ofBottomCenter(c.col.pos.up());
            world.addParticle(new BlockStateParticleEffect(ParticleTypes.BLOCK, c.col.state), top.x + r.nextDouble() - 0.5, top.y + 0.05,
                    top.z + r.nextDouble() - 0.5, 0, 0.1 + 0.25 * k, 0);
            if (k > 0.4f && r.nextFloat() < 0.08f * k && q.debris.size() < MAX_DEBRIS) {
                q.debris.add(new Debris(c.col.state, top.add(0, 0.1, 0), new Vec3d((r.nextDouble() - 0.5) * 0.1, 0.2 + r.nextDouble() * 0.25 * k,
                        (r.nextDouble() - 0.5) * 0.1), randomAxis(r), 0.15f + r.nextFloat() * 0.12f, 10f + r.nextFloat() * 20f, top.y));
            }
        }
        // Underground, dust shaken loose from the roof
        int dust = (int) (q.roofs.size() * 0.01 * k * k) + (k > 0.3f && r.nextFloat() < k ? 1 : 0);
        for (int i = 0; i < dust && !q.roofs.isEmpty(); i++) {
            Column c = q.roofs.get(r.nextInt(q.roofs.size()));
            world.addParticle(new BlockStateParticleEffect(ParticleTypes.FALLING_DUST, c.roofState), c.roof.getX() + r.nextDouble(),
                    c.roof.getY() - 0.05, c.roof.getZ() + r.nextDouble(), 0, 0, 0);
        }
        // The ground at the caster's feet already breaking up
        Vec3d o = q.center;
        if (r.nextFloat() < 0.4f + 0.6f * k) {
            BlockPos below = BlockPos.ofFloored(o.x, o.y - 0.5, o.z);
            BlockState ground = world.getBlockState(below);
            if (!ground.isAir()) {
                double a = r.nextDouble() * Math.PI * 2, rad = 0.6 + r.nextDouble() * 2.2;
                world.addParticle(new BlockStateParticleEffect(ParticleTypes.BLOCK, ground), o.x + Math.cos(a) * rad, o.y + 0.1,
                        o.z + Math.sin(a) * rad, 0, 0.2 + 0.3 * k, 0);
            }
        }
    }

    /** The one-off effects as pulse {@code p}'s wave reaches each patch of ground: flung earth, dust, rubble. */
    private static void rolling(ClientWorld world, Quake q, int p) {
        int t = q.age - q.pulseAt[p];
        if (t > PULSE_TICKS[p] + 1) return;
        double radius = radiusAt(p, t);
        Random r = world.random;
        float big = p == 0 ? 1f : 0.3f;
        while (q.reached[p] < q.columns.size() && q.columns.get(q.reached[p]).dist <= radius) {
            Column col = q.columns.get(q.reached[p]++);
            Vec3d top = Vec3d.ofBottomCenter(col.pos.up());
            double k = 1 - col.dist / EarthquakeCast.RADIUS;
            boolean fissure = col.crack != null;
            if (q.debris.size() < MAX_DEBRIS && r.nextFloat() < (fissure ? 0.3f : 0.035f) * big) {
                Vec3d vel = col.out.multiply(0.15 + r.nextDouble() * 0.3).add(0, (0.35 + r.nextDouble() * 0.45) * (0.6 + 0.6 * k) * (p == 0 ? 1 : 0.6), 0);
                q.debris.add(new Debris(col.state, top.add(0, 0.2, 0), vel, randomAxis(r), (0.3f + r.nextFloat() * 0.35f) * (p == 0 ? 1 : 0.6f),
                        8f + r.nextFloat() * 22f, top.y));
            }
            if (r.nextFloat() < 0.14f * big + (fissure ? 0.3f : 0)) {
                world.addParticle(new BlockStateParticleEffect(ParticleTypes.BLOCK, col.state), top.x, top.y + 0.1, top.z,
                        col.out.x * 0.25, 0.3, col.out.z * 0.25);
            }
            if (p == 0 && col.dist > 5 && r.nextFloat() < (fissure ? 0.05f : 0.012f)) {
                world.addParticle(ParticleTypes.POOF, top.x, top.y + 0.2, top.z, col.out.x * 0.08, 0.03, col.out.z * 0.08);
            }
            // Rubble and dust raining from the roof overhead
            if (col.roof != null) {
                if (q.debris.size() < MAX_DEBRIS && r.nextFloat() < 0.1f * big) {
                    boolean leafy = col.roofState.isIn(BlockTags.LEAVES);
                    Vec3d at = Vec3d.ofCenter(col.roof).add(r.nextDouble() - 0.5, -0.6, r.nextDouble() - 0.5);
                    if (!leafy) {
                        q.debris.add(new Debris(col.roofState, at, new Vec3d(0, -0.05, 0), randomAxis(r), 0.25f + r.nextFloat() * 0.35f,
                                4f + r.nextFloat() * 10f, top.y));
                    }
                }
                if (r.nextFloat() < 0.35f * big) {
                    world.addParticle(new BlockStateParticleEffect(ParticleTypes.FALLING_DUST, col.roofState), col.roof.getX() + r.nextDouble(),
                            col.roof.getY() - 0.05, col.roof.getZ() + r.nextDouble(), 0, 0, 0);
                }
            }
        }
    }

    /** Dust puffing off the fissure slabs as they sink back down. */
    private static void slabsSettling(ClientWorld world, Quake q) {
        Random r = world.random;
        float since = q.sinceShock(0);
        if (since < 10 || since > SLAB_TICKS || r.nextFloat() > 0.6f || q.cracks.isEmpty()) return;
        Crack c = q.cracks.get(r.nextInt(q.cracks.size()));
        Vec3d top = Vec3d.ofBottomCenter(c.col.pos.up());
        world.addParticle(new BlockStateParticleEffect(ParticleTypes.BLOCK, c.col.state), top.x, top.y, top.z, 0, 0.05, 0);
    }

    /** The main shock going off at the caster's feet. */
    private static void burst(ClientWorld world, Quake q) {
        Random r = world.random;
        Vec3d o = q.center;
        BlockState ground = world.getBlockState(BlockPos.ofFloored(o.x, o.y - 0.5, o.z));
        if (ground.isAir()) return;
        for (int i = 0; i < 60; i++) {
            double a = r.nextDouble() * Math.PI * 2, s = 0.3 + r.nextDouble() * 0.6;
            world.addParticle(new BlockStateParticleEffect(ParticleTypes.BLOCK, ground), o.x, o.y + 0.2, o.z,
                    Math.cos(a) * s, 0.3 + r.nextDouble() * 0.6, Math.sin(a) * s);
        }
        for (int i = 0; i < 24; i++) {
            double a = i * Math.PI * 2 / 24;
            world.addParticle(ParticleTypes.POOF, o.x + Math.cos(a) * 1.2, o.y + 0.15, o.z + Math.sin(a) * 1.2, Math.cos(a) * 0.3, 0.01, Math.sin(a) * 0.3);
        }
    }

    private static void tickDebris(ClientWorld world, Quake q) {
        Iterator<Debris> it = q.debris.iterator();
        while (it.hasNext()) {
            Debris d = it.next();
            d.age++;
            d.prev = d.pos;
            d.prevSpin = d.spin;
            d.vel = d.vel.multiply(0.98).add(0, -0.05, 0);
            d.pos = d.pos.add(d.vel);
            d.spin += d.spinSpeed;
            boolean landed = d.age > 3 && d.vel.y < 0 && (d.pos.y < d.floorY || !world.getBlockState(BlockPos.ofFloored(d.pos)).isAir());
            if (landed || d.age > 90) {
                int n = d.size > 0.3f ? 6 : 2;
                for (int i = 0; i < n; i++) {
                    world.addParticle(new BlockStateParticleEffect(ParticleTypes.BLOCK, d.state), d.pos.x, d.pos.y + 0.2, d.pos.z,
                            (world.random.nextDouble() - 0.5) * 0.3, 0.2, (world.random.nextDouble() - 0.5) * 0.3);
                }
                it.remove();
            }
        }
    }

    /** How far pulse {@code p}'s wave has rolled {@code t} ticks after it went off. */
    private static double radiusAt(int p, double t) {
        double k = MathHelper.clamp(t / PULSE_TICKS[p], 0, 1);
        return EarthquakeCast.RADIUS * (1 - (1 - k) * (1 - k));
    }

    private static Vector3f randomAxis(Random r) {
        Vector3f v = new Vector3f((float) r.nextGaussian(), (float) r.nextGaussian(), (float) r.nextGaussian());
        return v.lengthSquared() < 1e-4f ? new Vector3f(0, 1, 0) : v.normalize();
    }

    // ---------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------

    private static void render(WorldRenderContext context) {
        if (QUAKES.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        if (world == null) return;
        float tickDelta = context.tickCounter().getTickDelta(false);
        Vec3d cam = context.camera().getPos();
        MatrixStack ms = context.matrixStack() != null ? context.matrixStack() : new MatrixStack();
        VertexConsumerProvider.Immediate buffers = client.getBufferBuilders().getEntityVertexConsumers();
        VertexConsumerProvider.Immediate effects = client.getBufferBuilders().getEffectVertexConsumers();
        BlockRenderManager blocks = client.getBlockRenderManager();
        boolean drew = false, cracked = false;

        for (Quake q : QUAKES.values()) {
            float since = q.sinceShock(tickDelta);
            float fade = q.cancelled ? 1f - MathHelper.clamp((q.age - q.cancelledAt + tickDelta) / 20f, 0f, 1f) : 1f;

            // The fissures: cracks spreading before the shock, slabs jutting up after it
            double front = crackFront(q.age + tickDelta);
            for (Crack c : q.cracks) {
                int stage;
                float lift = 0, tilt = 0;
                if (!q.shocked()) {
                    if (c.along > front) continue;
                    stage = (int) MathHelper.clamp((front - c.along) * 1.4, 0, 9);
                    if (q.cancelled) stage = (int) (stage * fade);
                } else {
                    // The wave reaches it, it bursts up, then sinks back over the rest of the slab's time
                    float reach = (float) timeToReach(0, c.col.dist);
                    float s = since - reach;
                    if (s < 0) {
                        stage = 9;
                    } else {
                        float up = MathHelper.clamp(s / 3f, 0f, 1f);
                        float down = MathHelper.clamp((s - 8f) / (SLAB_TICKS - 8f), 0f, 1f);
                        float h = up * (1f - down * down * (3f - 2f * down));
                        lift = c.lift * h;
                        tilt = c.tilt * h;
                        stage = (int) MathHelper.clamp(9 * (1f - down), 0, 9);
                    }
                }
                ms.push();
                BlockPos pos = c.col.pos;
                ms.translate(pos.getX() + 0.5 - cam.x, pos.getY() + 0.5 + lift - cam.y, pos.getZ() + 0.5 - cam.z);
                if (tilt != 0) ms.multiply(RotationAxis.of(c.axis).rotationDegrees(tilt));
                ms.translate(-0.5, -0.5, -0.5);
                if (lift > 0.02f) {
                    blocks.renderBlockAsEntity(c.col.state, ms, buffers, WorldRenderer.getLightmapCoordinates(world, pos.up()), OverlayTexture.DEFAULT_UV);
                    drew = true;
                }
                if (stage >= 0 && (lift > 0.02f || !q.shocked() || since < timeToReach(0, c.col.dist) + 1)) {
                    VertexConsumer crack = new OverlayVertexConsumer(effects.getBuffer(ModelLoader.BLOCK_DESTRUCTION_RENDER_LAYERS.get(stage)), ms.peek(), 1f);
                    blocks.renderDamage(c.col.state, pos, world, ms, crack);
                    cracked = true;
                }
                ms.pop();
            }

            // The ground heaving as each wave rolls through
            for (int p = 0; p < q.pulseAt.length; p++) {
                if (q.pulseAt[p] < 0) continue;
                float t = q.age - q.pulseAt[p] + tickDelta;
                if (t > PULSE_TICKS[p] + 6) continue;
                double radius = radiusAt(p, t);
                double band = BAND[p];
                for (int i = lowerBound(q.columns, radius - band); i < q.columns.size(); i++) {
                    Column col = q.columns.get(i);
                    if (col.dist > radius) break;
                    if (col.dist < STILL || (col.crack != null && p == 0)) continue; // still, or drawn as a slab
                    double x = (radius - col.dist) / band;
                    float wave = (float) Math.sin(Math.PI * x);
                    // Settles back as it nears the edge, and after the wave's done
                    float strength = (float) (1.0 - 0.55 * col.dist / EarthquakeCast.RADIUS) * MathHelper.clamp((PULSE_TICKS[p] + 6 - t) / 6f, 0f, 1f);
                    float lift = HEAVE[p] * wave * strength;
                    if (lift < 0.03f) continue;
                    ms.push();
                    ms.translate(col.pos.getX() + 0.5 - cam.x, col.pos.getY() + 0.5 + lift - cam.y, col.pos.getZ() + 0.5 - cam.z);
                    // Tipped outward as it rides the wave
                    ms.multiply(RotationAxis.of(new Vector3f((float) -col.out.z, 0, (float) col.out.x)).rotation(0.35f * wave * strength));
                    ms.translate(-0.5, -0.5, -0.5);
                    blocks.renderBlockAsEntity(col.state, ms, buffers, WorldRenderer.getLightmapCoordinates(world, col.pos.up()), OverlayTexture.DEFAULT_UV);
                    ms.pop();
                    drew = true;
                }
            }

            // Chunks of earth and rubble in the air
            for (Debris d : q.debris) {
                Vec3d p = d.prev.lerp(d.pos, tickDelta);
                ms.push();
                ms.translate(p.x - cam.x, p.y - cam.y, p.z - cam.z);
                ms.multiply(RotationAxis.of(d.axis).rotationDegrees(MathHelper.lerp(tickDelta, d.prevSpin, d.spin)));
                ms.scale(d.size, d.size, d.size);
                ms.translate(-0.5, -0.5, -0.5);
                blocks.renderBlockAsEntity(d.state, ms, buffers, WorldRenderer.getLightmapCoordinates(world, BlockPos.ofFloored(p)), OverlayTexture.DEFAULT_UV);
                ms.pop();
                drew = true;
            }
        }
        if (drew) buffers.draw();
        if (cracked) effects.draw();
    }

    /** Ticks after pulse {@code p} goes off that its wave reaches {@code dist} blocks out. */
    private static double timeToReach(int p, double dist) {
        double k = MathHelper.clamp(dist / EarthquakeCast.RADIUS, 0, 1);
        return PULSE_TICKS[p] * (1 - Math.sqrt(1 - k));
    }

    /** The first column at least {@code dist} out. */
    private static int lowerBound(List<Column> columns, double dist) {
        int lo = 0, hi = columns.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (columns.get(mid).dist < dist) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }
}
