package net.ragnar.ragnarsmagicmod.lightningpath.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.ragnar.ragnarsmagicmod.lightningpath.LightningPath;
import net.ragnar.ragnarsmagicmod.lightningpath.LightningPathRun;
import net.ragnar.ragnarsmagicmod.lightningpath.PathBuilder;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Client half of the Tome of the Lightning Path (see LightningPath).
 * <ul>
 *   <li>Painting: your view tips down to the ground; the ground lights up behind your crosshair in flickering
 *       half-block tiles of electric blue with a fine crackle over them, and a pulsing pixel ring of tiles marks where
 *       it ends. A bar under the crosshair shows the time left,
 *       and how far the path reaches.</li>
 *   <li>Running (your own): this client moves you along the route - hugging the ground, lifting over bumps, stopping
 *       short of anything solid - turning you to face the way it runs, widening the view and lighting the edges of
 *       the screen.</li>
 *   <li>Everyone sees the runner as a streak of lightning, the whole route flaring as they go, and a bolt striking
 *       down where they arrive.</li>
 * </ul>
 */
public final class LightningPathClient {
    private LightningPathClient() {}

    private static final Identifier VIGNETTE = Identifier.ofVanilla("textures/misc/vignette.png");
    private static final RenderLayer GLOW = RenderLayer.of("ragnarsmagicmod_lightning_path",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 65536, false, true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.LIGHTNING_TRANSPARENCY)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    private static final int TRAIL = 10, FLARE_TICKS = 24, BOLT_TICKS = 12;

    /** One caster's cast, as this client sees it. */
    private static final class Cast {
        final int casterId;
        List<Vec3d> painted = List.of();
        int ticksLeft;
        PathBuilder.Route route;
        int runAge = -1;
        int flare = -1;
        final Deque<Vec3d> trail = new ArrayDeque<>();
        int age;

        Cast(int casterId) {
            this.casterId = casterId;
        }

        boolean running() {
            return runAge >= 0 && flare < 0;
        }
    }

    private record Bolt(Vec3d at, Vec3d top, long seed, int[] age) {}

    private static final Map<Integer, Cast> CASTS = new HashMap<>();
    private static final List<Bolt> BOLTS = new ArrayList<>();

    // Your own run
    private static PathBuilder.Route myRoute;
    private static double myDistance;
    private static int edgeGlow, kick;

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(LightningPath.PaintPayload.ID, (payload, context) -> {
            MinecraftClient client = context.client();
            Cast c = CASTS.get(payload.casterId());
            boolean fresh = c == null;
            if (fresh) CASTS.put(payload.casterId(), c = new Cast(payload.casterId()));
            c.painted = LightningPath.unpack(payload.points());
            c.ticksLeft = payload.ticksLeft();
            // Your view tips down to the ground in front of you, ready to paint
            if (fresh && client.player != null && client.player.getId() == payload.casterId()) client.player.setPitch(62f);
        });
        ClientPlayNetworking.registerGlobalReceiver(LightningPath.RunPayload.ID, (payload, context) -> {
            MinecraftClient client = context.client();
            Cast c = CASTS.computeIfAbsent(payload.casterId(), Cast::new);
            if (payload.points().size() < 2) {
                CASTS.remove(payload.casterId());
                return;
            }
            c.painted = LightningPath.unpack(payload.points());
            c.route = PathBuilder.route(c.painted);
            c.runAge = 0;
            if (client.player != null && client.player.getId() == payload.casterId()) {
                myRoute = c.route;
                myDistance = 0;
                edgeGlow = 12;
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(LightningPath.ArrivePayload.ID, (payload, context) -> {
            Cast c = CASTS.get(payload.casterId());
            if (c != null) c.flare = 0;
            Vec3d at = new Vec3d(payload.at());
            Random r = context.client().world != null ? context.client().world.random : Random.create();
            BOLTS.add(new Bolt(at, at.add((r.nextDouble() - 0.5) * 8, 30, (r.nextDouble() - 0.5) * 8), r.nextLong(), new int[1]));
            if (BOLTS.size() > 6) BOLTS.remove(0);
            MinecraftClient client = context.client();
            if (client.player != null && client.player.getId() == payload.casterId()) {
                myRoute = null;
                edgeGlow = 10;
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            CASTS.clear();
            BOLTS.clear();
            myRoute = null;
        });
        ClientTickEvents.END_CLIENT_TICK.register(LightningPathClient::tick);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(LightningPathClient::render);
        HudRenderCallback.EVENT.register(LightningPathClient::renderHud);
    }

    private static Cast mine() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client.player == null ? null : CASTS.get(client.player.getId());
    }

    // ---------------------------------------------------------------------
    // Your own run
    // ---------------------------------------------------------------------

    /**
     * Moves you along your route for this tick, instead of walking (PlayerTravelMixin). True if it did. Hugs the
     * ground, lifts over bumps, and stops short of anything it can't get through - never inside a block.
     */
    public static boolean travel(ClientPlayerEntity p) {
        if (myRoute == null) return false;
        PathBuilder.Route route = myRoute;
        myDistance = Math.min(route.length(), myDistance + LightningPath.SPEED);
        Vec3d want = route.pointAt(myDistance);
        Vec3d at = clearSpot(p, want);
        boolean end = myDistance >= route.length();
        if (at == null) {
            at = p.getPos();
            end = true;
        }
        Vec3d moved = at.subtract(p.getPos());
        p.setPosition(at);
        p.setVelocity(moved);
        p.fallDistance = 0;
        p.setOnGround(true);
        p.updateLimbs(false);

        // Facing the way the route runs
        Vec3d dir = route.direction(myDistance);
        float yaw = (float) (MathHelper.atan2(-dir.x, dir.z) * MathHelper.DEGREES_PER_RADIAN);
        p.setYaw(p.getYaw() + MathHelper.wrapDegrees(yaw - p.getYaw()) * 0.45f);
        p.setPitch(MathHelper.lerp(0.3f, p.getPitch(), 12f));

        if (end) {
            myRoute = null;
            // A little skid of momentum out the end
            p.setVelocity(dir.multiply(1, 0, 1).multiply(0.45));
            kick = 8;
            ClientPlayNetworking.send(LightningPath.DonePayload.INSTANCE);
        }
        return true;
    }

    /** Somewhere to stand at (or just above) {@code want}, or null if there's nowhere. */
    private static Vec3d clearSpot(ClientPlayerEntity p, Vec3d want) {
        Box box = p.getDimensions(p.getPose()).getBoxAt(want);
        for (double up = 0; up <= 3.0; up += 0.5) {
            if (p.getWorld().isSpaceEmpty(p, box.offset(0, up, 0))) return want.add(0, up, 0);
        }
        return null;
    }

    /** While you paint or run, your keys don't move you. */
    public static void captureInput(Input input) {
        Cast c = mine();
        if (c == null || (c.runAge < 0 && c.ticksLeft <= 0 && myRoute == null) || c.flare >= 0) return;
        input.movementForward = 0;
        input.movementSideways = 0;
        input.pressingForward = false;
        input.pressingBack = false;
        input.pressingLeft = false;
        input.pressingRight = false;
        input.jumping = false;
        input.sneaking = false;
    }

    /** The view widens as you go, and snaps back as you land. */
    public static double fovMultiplier(float tickDelta) {
        if (myRoute != null) return 1.3;
        return kick > 0 ? 1.0 + 0.3 * (kick - tickDelta) / 8.0 : 1.0;
    }

    /** Pointing the way while painting; arms swept back while running. */
    public static void pose(LivingEntity entity, BipedEntityModel<?> model, float tickDelta) {
        Cast c = CASTS.get(entity.getId());
        if (c == null || c.flare >= 0) return;
        if (c.runAge >= 0) {
            model.rightArm.pitch = 1.35f;
            model.leftArm.pitch = 1.35f;
            model.rightArm.yaw = 0f;
            model.leftArm.yaw = 0f;
            model.rightArm.roll = 0.2f;
            model.leftArm.roll = -0.2f;
        } else if (c.ticksLeft > 0) {
            model.rightArm.pitch = -MathHelper.HALF_PI + model.head.pitch;
            model.rightArm.yaw = model.head.yaw;
            model.rightArm.roll = 0f;
        }
    }

    // ---------------------------------------------------------------------
    // Ticking
    // ---------------------------------------------------------------------

    private static void tick(MinecraftClient client) {
        if (edgeGlow > 0 && !client.isPaused()) edgeGlow--;
        if (kick > 0 && !client.isPaused()) kick--;
        if (client.world == null || client.isPaused()) return;
        ClientWorld world = client.world;
        Random r = world.random;
        BOLTS.removeIf(b -> ++b.age()[0] > BOLT_TICKS);
        Iterator<Cast> it = CASTS.values().iterator();
        while (it.hasNext()) {
            Cast c = it.next();
            c.age++;
            if (c.ticksLeft > 0) c.ticksLeft--;
            if (c.runAge >= 0) c.runAge++;
            if (c.flare >= 0 && ++c.flare > FLARE_TICKS) {
                it.remove();
                continue;
            }
            if (c.age > 30 * 20) {
                it.remove();
                continue;
            }
            // Sparks spitting off the painted line
            if (!c.painted.isEmpty() && c.runAge < 0 && c.painted.size() > 1) {
                for (int i = 0; i < 2; i++) {
                    Vec3d p = c.painted.get(r.nextInt(c.painted.size()));
                    world.addParticle(ParticleTypes.ELECTRIC_SPARK, p.x, p.y + 0.15, p.z, r.nextGaussian() * 0.05, 0.08, r.nextGaussian() * 0.05);
                }
            }
            // The runner: where they've been, and sparks off them
            Entity e = world.getEntityById(c.casterId);
            if (e != null && c.running()) {
                Vec3d at = e.getPos().add(0, 0.9, 0);
                c.trail.addFirst(at);
                while (c.trail.size() > TRAIL) c.trail.removeLast();
                for (int i = 0; i < 4; i++) {
                    world.addParticle(ParticleTypes.ELECTRIC_SPARK, at.x + r.nextGaussian() * 0.3, at.y + r.nextGaussian() * 0.5, at.z + r.nextGaussian() * 0.3,
                            r.nextGaussian() * 0.1, r.nextGaussian() * 0.1, r.nextGaussian() * 0.1);
                }
            } else if (!c.trail.isEmpty() && c.flare >= 0) {
                c.trail.removeLast();
            }
        }
    }

    // ---------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------

    private static Matrix4f pose = new Matrix4f();
    private static Vec3d cam = Vec3d.ZERO;

    private static void render(WorldRenderContext context) {
        if (CASTS.isEmpty() && BOLTS.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;
        float tickDelta = context.tickCounter().getTickDelta(false);
        cam = context.camera().getPos();
        MatrixStack ms = context.matrixStack() != null ? context.matrixStack() : new MatrixStack();
        pose = ms.peek().getPositionMatrix();
        VertexConsumerProvider.Immediate buffers = client.getBufferBuilders().getEntityVertexConsumers();
        VertexConsumer vc = buffers.getBuffer(GLOW);

        for (Cast c : CASTS.values()) {
            float t = c.age + tickDelta;
            if (c.runAge < 0) {
                // Painting: the ground lit up in blocky tiles along the line, a fine crackle over the top
                if (c.painted.size() > 1) {
                    long seed = c.casterId * 31L + c.age / 2;
                    float flicker = 0.7f + 0.3f * MathHelper.sin(t * 2.1f);
                    pathTiles(vc, c.painted, c.age, 0.85f * flicker);
                    bolt(vc, lift(c.painted, 0.14), Random.create(seed), 0.14, 0.06f, 0.9f, 0.97f, 1f, 0.55f * flicker);
                    Vec3d end = c.painted.get(c.painted.size() - 1);
                    float pulse = 0.75f + 0.25f * MathHelper.sin(t * 0.8f);
                    pixelRing(vc, client.world, end, 1.1 + 0.25 * pulse, 0.5, 0.55f, 0.8f, 1f, 0.85f);
                    pixelRing(vc, client.world, end, 2.4 + 0.25 * pulse, 0.5, 0.35f, 0.55f, 1f, 0.35f);
                    // The spot itself: a bright little cross of tiles, and a shaft of light over it
                    double ex = snap(end.x), ez = snap(end.z);
                    tile(vc, ex, end.y + 0.03, ez, CELL, 0.8f, 0.95f, 1f, 0.9f * pulse);
                    for (int k = 0; k < 4; k++) {
                        double dx = k == 0 ? CELL : k == 1 ? -CELL : 0, dz = k == 2 ? CELL : k == 3 ? -CELL : 0;
                        tile(vc, ex + dx, end.y + 0.03, ez + dz, CELL, 0.5f, 0.75f, 1f, 0.6f * pulse);
                    }
                    beam(vc, end, end.add(0, 4.5, 0), 0.35f, 0.55f, 0.8f, 1f, 0.3f * pulse);
                }
                continue;
            }
            // The route, flaring as they run it, then fading after they land
            float route = c.flare < 0 ? 0.25f : 0.9f * Math.max(0f, 1f - (c.flare + tickDelta) / FLARE_TICKS);
            if (c.route != null && route > 0.01f) {
                long seed = c.casterId * 17L + c.age / 2;
                pathTiles(vc, c.painted, c.age, route);
                bolt(vc, lift(sparse(c.route.points), 0.14), Random.create(seed), 0.2, 0.1f, 0.6f, 0.85f, 1f, route * 0.7f);
            }
            // The runner, a streak of lightning
            if (c.trail.size() > 1) {
                List<Vec3d> streak = new ArrayList<>(c.trail);
                Entity e = client.world.getEntityById(c.casterId);
                if (e != null && c.running()) streak.set(0, e.getLerpedPos(tickDelta).add(0, 0.9, 0));
                long seed = c.casterId * 7L + c.age;
                bolt(vc, streak, Random.create(seed), 0.45, 0.5f, 0.5f, 0.8f, 1f, 0.7f);
                bolt(vc, streak, Random.create(seed), 0.45, 0.14f, 1f, 1f, 1f, 1f);
            }
        }

        for (Bolt b : BOLTS) {
            float a = 1f - (b.age()[0] + tickDelta) / BOLT_TICKS;
            if (a <= 0f) continue;
            a *= b.age()[0] % 2 == 0 ? 1f : 0.6f;
            Random r = Random.create(b.seed() + b.age()[0] / 2);
            List<Vec3d> main = jaggedLine(b.top(), b.at(), r, 14, 1.4);
            bolt(vc, main, r, 0.0, 0.7f, 0.55f, 0.8f, 1f, 0.6f * a);
            bolt(vc, main, r, 0.0, 0.22f, 1f, 1f, 1f, a);
            // Forks
            for (int i = 0; i < 3; i++) {
                Vec3d from = main.get(2 + r.nextInt(main.size() - 4));
                Vec3d to = from.add((r.nextDouble() - 0.5) * 8, -4 - r.nextDouble() * 6, (r.nextDouble() - 0.5) * 8);
                bolt(vc, jaggedLine(from, to, r, 6, 0.8), r, 0.0, 0.12f, 0.85f, 0.95f, 1f, 0.7f * a);
            }
            glow(vc, b.at().add(0, 1, 0), context, 4f, 0.6f, 0.8f, 1f, 0.7f * a);
            float k = (b.age()[0] + tickDelta) / BOLT_TICKS;
            pixelRing(vc, client.world, b.at(), 0.8 + 5.0 * k, 0.75, 0.6f, 0.85f, 1f, (1f - k) * 0.9f);
            pixelRing(vc, client.world, b.at(), 0.4 + 2.5 * k, 0.5, 0.85f, 0.95f, 1f, (1f - k) * 0.6f);
        }
        buffers.draw(GLOW);
    }

    /** Floor tiles are half a block across, like the Tome of Unseen Hands' shadow. */
    private static final double CELL = 0.5;

    private static double snap(double v) {
        return (Math.floor(v / CELL) + 0.5) * CELL;
    }

    /**
     * The ground along a path, lit up a half-block tile at a time - each tile flickering on its own, a brighter pulse
     * of current running out along it.
     */
    private static void pathTiles(VertexConsumer vc, List<Vec3d> pts, int age, float alpha) {
        if (pts.size() < 2 || alpha <= 0.01f) return;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        double along = 0, total = PathBuilder.measure(pts);
        double current = (age * 3.0) % Math.max(1, total + 6) - 3;
        for (int i = 1; i < pts.size(); i++) {
            Vec3d a = pts.get(i - 1), b = pts.get(i);
            double len = a.distanceTo(b);
            int steps = Math.max(1, (int) Math.ceil(len / (CELL * 0.5)));
            for (int k = 0; k <= steps; k++) {
                Vec3d p = a.lerp(b, k / (double) steps);
                double x = snap(p.x), z = snap(p.z);
                long key = ((long) MathHelper.floor(x / CELL) << 32) ^ (MathHelper.floor(z / CELL) & 0xFFFFFFFFL);
                if (!seen.add(key)) continue;
                double s = along + len * k / steps;
                // Each tile flickers on its own, never quite out
                long h = key * 0x9E3779B97F4A7C15L + age / 2;
                float flick = 0.55f + 0.45f * (((h >>> 40) & 0xFF) / 255f);
                float pulse = (float) Math.max(0, 1 - Math.abs(s - current) / 3);
                float a2 = alpha * (0.45f * flick + 0.55f * pulse);
                tile(vc, x, p.y + 0.02, z, CELL, 0.35f + 0.6f * pulse, 0.6f + 0.35f * pulse, 1f, a2);
            }
            along += len;
        }
    }

    /**
     * A ring of half-block tiles lying on the real ground round {@code c}: every tile whose middle is between
     * {@code radius - width} and {@code radius} out - a circle, the way Minecraft would draw one.
     */
    private static void pixelRing(VertexConsumer vc, ClientWorld world, Vec3d c, double radius, double width,
                                  float r, float g, float b, float alpha) {
        if (alpha <= 0.01f || radius <= 0.1) return;
        int n = (int) Math.ceil(radius / CELL) + 1;
        double cx = snap(c.x), cz = snap(c.z);
        BlockPos.Mutable m = new BlockPos.Mutable();
        int cy = MathHelper.floor(c.y + 0.01);
        for (int i = -n; i <= n; i++) {
            for (int j = -n; j <= n; j++) {
                double x = cx + i * CELL, z = cz + j * CELL;
                double d = Math.sqrt((x - c.x) * (x - c.x) + (z - c.z) * (z - c.z));
                if (d > radius || d < radius - width) continue;
                Double y = floorAt(world, m, x, cy, z);
                if (y == null) continue;
                tile(vc, x, y + 0.025, z, CELL, r, g, b, alpha);
            }
        }
    }

    /** The top of the ground a few blocks above or below {@code cy} at x, z, with open space over it. */
    private static Double floorAt(ClientWorld world, BlockPos.Mutable m, double x, int cy, double z) {
        int bx = MathHelper.floor(x), bz = MathHelper.floor(z);
        for (int y = cy + 2; y >= cy - 3; y--) {
            m.set(bx, y, bz);
            var shape = world.getBlockState(m).getCollisionShape(world, m);
            if (shape.isEmpty()) continue;
            m.set(bx, y + 1, bz);
            if (!world.getBlockState(m).getCollisionShape(world, m).isEmpty() && y < cy + 2) continue;
            return y + shape.getMax(net.minecraft.util.math.Direction.Axis.Y);
        }
        return null;
    }

    /** A flat square of light, {@code s} across, lying at {@code y}. */
    private static void tile(VertexConsumer vc, double x, double y, double z, double s, float r, float g, float b, float a) {
        if (a <= 0.005f) return;
        double h = s / 2 - 0.02; // a hairline gap between tiles, so they read as tiles
        v(vc, new Vec3d(x - h, y, z - h), r, g, b, a);
        v(vc, new Vec3d(x + h, y, z - h), r, g, b, a);
        v(vc, new Vec3d(x + h, y, z + h), r, g, b, a);
        v(vc, new Vec3d(x - h, y, z + h), r, g, b, a);
    }

    private static List<Vec3d> lift(List<Vec3d> pts, double up) {
        List<Vec3d> out = new ArrayList<>(pts.size());
        for (Vec3d p : pts) out.add(p.add(0, up, 0));
        return out;
    }

    /** Every few points of a fine route (it's drawn jagged anyway). */
    private static List<Vec3d> sparse(List<Vec3d> pts) {
        List<Vec3d> out = new ArrayList<>();
        for (int i = 0; i < pts.size(); i += 3) out.add(pts.get(i));
        out.add(pts.get(pts.size() - 1));
        return out;
    }

    private static List<Vec3d> jaggedLine(Vec3d a, Vec3d b, Random r, int n, double amp) {
        List<Vec3d> out = new ArrayList<>();
        for (int i = 0; i <= n; i++) {
            double k = i / (double) n;
            Vec3d p = a.lerp(b, k);
            if (i > 0 && i < n) p = p.add(r.nextGaussian() * amp, r.nextGaussian() * amp * 0.3, r.nextGaussian() * amp);
            out.add(p);
        }
        return out;
    }

    /** A ribbon of light along {@code pts}, each point nudged by up to {@code jitter}, turned to face the camera. */
    private static void bolt(VertexConsumer vc, List<Vec3d> pts, Random r, double jitter, float width,
                             float red, float green, float blue, float alpha) {
        if (pts.size() < 2 || alpha <= 0.003f) return;
        Vec3d prev = null;
        for (Vec3d p0 : pts) {
            Vec3d p = jitter > 0 ? p0.add(r.nextGaussian() * jitter, Math.abs(r.nextGaussian()) * jitter * 0.6, r.nextGaussian() * jitter) : p0;
            if (prev != null) segment(vc, prev, p, width, red, green, blue, alpha);
            prev = p;
        }
    }

    private static void segment(VertexConsumer vc, Vec3d a, Vec3d b, float width, float r, float g, float bl, float alpha) {
        Vec3d mid = a.add(b).multiply(0.5);
        Vec3d side = b.subtract(a).crossProduct(cam.subtract(mid));
        if (side.lengthSquared() < 1e-8) return;
        side = side.normalize().multiply(width * 0.5);
        v(vc, a.add(side), r, g, bl, 0f);
        v(vc, b.add(side), r, g, bl, 0f);
        v(vc, b, r, g, bl, alpha);
        v(vc, a, r, g, bl, alpha);
        v(vc, a, r, g, bl, alpha);
        v(vc, b, r, g, bl, alpha);
        v(vc, b.subtract(side), r, g, bl, 0f);
        v(vc, a.subtract(side), r, g, bl, 0f);
    }

    private static void beam(VertexConsumer vc, Vec3d from, Vec3d to, float width, float r, float g, float b, float alpha) {
        Vec3d axis = to.subtract(from);
        Vec3d side = axis.crossProduct(cam.subtract(from.add(axis.multiply(0.5))));
        if (side.lengthSquared() < 1e-8) return;
        side = side.normalize().multiply(width);
        v(vc, from, r, g, b, alpha);
        v(vc, to, r, g, b, 0f);
        v(vc, to.add(side), r, g, b, 0f);
        v(vc, from.add(side), r, g, b, 0f);
        v(vc, from, r, g, b, alpha);
        v(vc, to, r, g, b, 0f);
        v(vc, to.subtract(side), r, g, b, 0f);
        v(vc, from.subtract(side), r, g, b, 0f);
    }

    private static void glow(VertexConsumer vc, Vec3d c, WorldRenderContext context, float size, float r, float g, float b, float alpha) {
        Vec3d fwd = Vec3d.fromPolar(context.camera().getPitch(), context.camera().getYaw());
        Vec3d right = fwd.crossProduct(new Vec3d(0, 1, 0));
        right = right.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : right.normalize();
        Vec3d up = right.crossProduct(fwd).normalize();
        int n = 24;
        for (int i = 0; i < n; i++) {
            double t0 = i * Math.PI * 2 / n, t1 = (i + 1) * Math.PI * 2 / n;
            v(vc, c, r, g, b, alpha);
            v(vc, c, r, g, b, alpha);
            v(vc, c.add(right.multiply(Math.cos(t1) * size)).add(up.multiply(Math.sin(t1) * size)), r, g, b, 0f);
            v(vc, c.add(right.multiply(Math.cos(t0) * size)).add(up.multiply(Math.sin(t0) * size)), r, g, b, 0f);
        }
    }

    private static void v(VertexConsumer vc, Vec3d p, float r, float g, float b, float a) {
        vc.vertex(pose, (float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z)).color(r, g, b, MathHelper.clamp(a, 0f, 1f));
    }

    // ---------------------------------------------------------------------
    // On screen
    // ---------------------------------------------------------------------

    private static void renderHud(DrawContext context, RenderTickCounter counter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        float tickDelta = counter.getTickDelta(false);
        int w = context.getScaledWindowWidth(), h = context.getScaledWindowHeight();

        // Electric blue at the edges of the screen as you go
        float edge = myRoute != null ? 0.75f : Math.max(0f, (edgeGlow - tickDelta) / 12f);
        if (edge > 0.01f) {
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.enableBlend();
            RenderSystem.blendFunc(com.mojang.blaze3d.platform.GlStateManager.SrcFactor.ONE, com.mojang.blaze3d.platform.GlStateManager.DstFactor.ONE);
            context.setShaderColor(0.25f * edge, 0.5f * edge, 0.9f * edge, 1f);
            context.drawTexture(VIGNETTE, 0, 0, -90, 0f, 0f, w, h, w, h);
            context.setShaderColor(1f, 1f, 1f, 1f);
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
        }

        Cast c = mine();
        if (c == null || c.runAge >= 0 || client.options.hudHidden) return;
        // Painting: time left as a draining bar, how far the path reaches, and how to go early
        int cx = w / 2, y = h / 2 + 10, half = 24;
        float left = Math.max(0f, c.ticksLeft - tickDelta) / LightningPathRun.PAINT_TICKS;
        context.fill(cx - half - 1, y - 1, cx + half + 1, y + 2, 0x88000000);
        context.fill(cx - half, y, cx - half + Math.round(2 * half * left), y + 1, 0xFF8CCBFF);
        double length = PathBuilder.measure(c.painted);
        String reach = (int) length + " / " + (int) PathBuilder.MAX_LENGTH;
        int color = length >= PathBuilder.MAX_LENGTH - 1 ? 0xFFFFD060 : 0xFFD8ECFF;
        context.getMatrices().push();
        context.getMatrices().translate(cx, y + 4, 0);
        context.getMatrices().scale(0.75f, 0.75f, 1f);
        context.drawTextWithShadow(client.textRenderer, reach, -client.textRenderer.getWidth(reach) / 2, 0, color);
        context.getMatrices().pop();
        Text hint = Text.literal("Look along your path  •  Right-click to go now").formatted(Formatting.GRAY);
        context.drawTextWithShadow(client.textRenderer, hint, cx - client.textRenderer.getWidth(hint) / 2, h - 72, 0xFFFFFF);
    }
}
