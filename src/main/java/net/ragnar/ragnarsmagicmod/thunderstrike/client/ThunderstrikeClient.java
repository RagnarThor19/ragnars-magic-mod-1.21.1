package net.ragnar.ragnarsmagicmod.thunderstrike.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
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
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.LivingEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.ragnar.ragnarsmagicmod.thunderstrike.Thunderstrike;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Everything you see and hear of a Tome of Thunderstrike, drawn on each client from the server's three payloads.
 * <ul>
 *   <li><b>The storm cloud</b>: blocky, like the game's own clouds, but low, dark and churning; it boils up over the
 *       spot, turns slowly, and flickers with lightning inside, its blocks lighting up around each flash.</li>
 *   <li><b>The ground</b>: static crackling round the edge of the strike and across it, smoke as it's about to go.</li>
 *   <li><b>Leaders and streamers</b>: faint jagged channels stepping down out of the cloud along the very path the bolt
 *       will take, and little sparks reaching up from the ground and from anything standing there to meet them.</li>
 *   <li><b>The bolt</b>: a giant branching channel, white-hot at the core in a blue glow, flickering through several
 *       return strokes; the sky flashes, the screen flashes for anyone close, a ring of light runs out over the ground,
 *       and the thunder arrives later the further off you are.</li>
 *   <li><b>Chain lightning</b>: crackling arcs from creature to creature, re-forking every tick.</li>
 * </ul>
 */
public final class ThunderstrikeClient {
    private ThunderstrikeClient() {}

    static final RenderLayer GLOW = RenderLayer.of("ragnarsmagicmod_thunder_glow",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 65536, false, true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.LIGHTNING_TRANSPARENCY)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    static final RenderLayer CLOUD = RenderLayer.of("ragnarsmagicmod_thunder_cloud",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 65536, false, true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.TRANSLUCENT_TRANSPARENCY)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    private static final int LEADER_TICKS = 26, STREAMER_TICKS = 9, BOLT_TICKS = 18, ARC_TICKS = 7;
    private static final int CLOUD_GROW = 30, CLOUD_FADE = 45;
    /** How much thicker the trunk is than a branch's full width: it's a colossal bolt. */
    private static final float TRUNK = 1.6f;
    private static final double CLOUD_RADIUS = 15, CELL = 3;

    // ---------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------

    /** A puff of cloud: its place (relative to the storm's centre), size and how far out it is. */
    private record Cell(double x, double z, double y, double h, double w, double dist) {}

    /** A flicker of lightning inside the cloud, from {@code a} to {@code b}, at {@code time}. */
    private record Flash(Vec3d a, Vec3d b, long time, long seed) {}

    private static final class Storm {
        final Vec3d ground;
        final long seed, start;
        final int warn;
        final double cloudY;
        final BoltShape bolt;
        final List<Cell> cells = new ArrayList<>();
        final List<Flash> flashes = new ArrayList<>();
        final Random random;
        long struck = -1;

        Storm(Vec3d ground, long seed, long start, int warn) {
            this.ground = ground;
            this.seed = seed;
            this.start = start;
            this.warn = warn;
            this.cloudY = ground.y + Thunderstrike.CLOUD_HEIGHT;
            this.bolt = BoltShape.make(ground, cloudY, seed);
            this.random = new Random(seed ^ 0x5DEECE66DL);
            Random r = new Random(seed * 31 + 7);
            int n = (int) Math.ceil(CLOUD_RADIUS / CELL);
            for (int gx = -n; gx <= n; gx++) {
                for (int gz = -n; gz <= n; gz++) {
                    double x = gx * CELL + (r.nextDouble() - 0.5) * 1.2, z = gz * CELL + (r.nextDouble() - 0.5) * 1.2;
                    double d = Math.hypot(x, z);
                    if (d > CLOUD_RADIUS * (0.8 + r.nextDouble() * 0.25)) continue;
                    if (d > 7 && r.nextDouble() < 0.28) continue; // ragged at the edges
                    double h = 1.5 + r.nextInt(3) + (d < 6 ? 1.5 : 0);
                    cells.add(new Cell(x, z, r.nextDouble() * 1.4, h, CELL + 0.4 + r.nextDouble() * 0.8, d));
                }
            }
        }

        float sinceStart(ClientWorld w, float td) {
            return w.getTime() - start + td;
        }

        float sinceStrike(ClientWorld w, float td) {
            return struck < 0 ? -1 : w.getTime() - struck + td;
        }
    }

    private record ArcFx(Vec3d from, Vec3d to, float power, long start, long seed) {}

    private static final List<Storm> STORMS = new ArrayList<>();
    private static final List<ArcFx> ARCS = new ArrayList<>();

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(Thunderstrike.Warn.ID, (p, ctx) -> {
            ClientWorld w = ctx.client().world;
            if (w != null) STORMS.add(new Storm(p.ground(), p.seed(), w.getTime(), p.ticks()));
        });
        ClientPlayNetworking.registerGlobalReceiver(Thunderstrike.Strike.ID, (p, ctx) -> strike(ctx.client(), p.ground(), p.seed()));
        ClientPlayNetworking.registerGlobalReceiver(Thunderstrike.Arc.ID, (p, ctx) -> {
            ClientWorld w = ctx.client().world;
            if (w != null) ARCS.add(new ArcFx(p.from(), p.to(), p.power(), w.getTime(), w.getRandom().nextLong()));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> {
            STORMS.clear();
            ARCS.clear();
        });
        ClientTickEvents.END_CLIENT_TICK.register(ThunderstrikeClient::tick);
        WorldRenderEvents.AFTER_ENTITIES.register(ThunderstrikeClient::render);
        HudRenderCallback.EVENT.register((context, counter) -> {
            float a = screenFlash(counter.getTickDelta(false));
            if (a > 0.01f) {
                int alpha = (int) (MathHelper.clamp(a, 0f, 1f) * 255);
                context.fill(0, 0, context.getScaledWindowWidth(), context.getScaledWindowHeight(), (alpha << 24) | 0xEEF2FF);
            }
        });
    }

    private static void strike(MinecraftClient client, Vec3d ground, long seed) {
        ClientWorld w = client.world;
        if (w == null) return;
        Storm s = null;
        for (Storm st : STORMS) if (st.seed == seed) s = st;
        if (s == null) { // joined (or came in range) mid-storm: draw the strike anyway
            s = new Storm(ground, seed, w.getTime() - Thunderstrike.WARN_TICKS, Thunderstrike.WARN_TICKS);
            STORMS.add(s);
        }
        s.struck = w.getTime();
        // The game's own thunder: everywhere, and later the further away (that's the 'true')
        w.playSound(ground.x, ground.y, ground.z, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.WEATHER, 10000f, 0.62f, true);
        w.playSound(ground.x, ground.y, ground.z, SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.WEATHER, 2.5f, 0.5f, false);
        w.playSound(ground.x, ground.y, ground.z, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.WEATHER, 3.0f, 0.6f, true);
        w.setLightningTicksLeft(2);
    }

    // ---------------------------------------------------------------------
    // Ticking: particles, cloud flickers, the sky flashing with each stroke
    // ---------------------------------------------------------------------

    private static void tick(MinecraftClient client) {
        ClientWorld w = client.world;
        if (w == null) {
            STORMS.clear();
            ARCS.clear();
            return;
        }
        long now = w.getTime();
        ARCS.removeIf(a -> now - a.start() > ARC_TICKS);
        for (Iterator<Storm> it = STORMS.iterator(); it.hasNext(); ) {
            Storm s = it.next();
            long age = now - s.start;
            if (s.struck >= 0 && now - s.struck > CLOUD_FADE + 40 || age > s.warn + 200) {
                it.remove();
                continue;
            }
            Random r = s.random;
            float warnFrac = MathHelper.clamp(age / (float) s.warn, 0f, 1f);
            if (s.struck < 0) {
                // Static round the edge of where it'll hit, and across it, thicker as it builds
                int sparks = 3 + (int) (warnFrac * 14);
                for (int i = 0; i < sparks; i++) {
                    double a = r.nextDouble() * Math.PI * 2;
                    double rad = i % 3 == 0 ? r.nextDouble() * Thunderstrike.STRIKE_RADIUS : Thunderstrike.STRIKE_RADIUS + (r.nextDouble() - 0.5) * 0.6;
                    double x = s.ground.x + Math.cos(a) * rad, z = s.ground.z + Math.sin(a) * rad;
                    double y = w.getTopY(Heightmap.Type.MOTION_BLOCKING, MathHelper.floor(x), MathHelper.floor(z)) + 0.05;
                    w.addParticle(ParticleTypes.ELECTRIC_SPARK, x, y, z, (r.nextDouble() - 0.5) * 0.1, 0.05 + r.nextDouble() * 0.15, (r.nextDouble() - 0.5) * 0.1);
                }
                if (warnFrac > 0.7f && age % 2 == 0) {
                    double a = r.nextDouble() * Math.PI * 2;
                    double x = s.ground.x + Math.cos(a) * Thunderstrike.STRIKE_RADIUS, z = s.ground.z + Math.sin(a) * Thunderstrike.STRIKE_RADIUS;
                    double y = w.getTopY(Heightmap.Type.MOTION_BLOCKING, MathHelper.floor(x), MathHelper.floor(z));
                    w.addParticle(ParticleTypes.SMOKE, x, y + 0.1, z, 0, 0.04, 0);
                }
                // Lightning flickering inside the cloud, more and more often
                if (age > 8 && r.nextFloat() < 0.08f + warnFrac * 0.45f) {
                    double a = r.nextDouble() * Math.PI * 2, d = r.nextDouble() * CLOUD_RADIUS * 0.7;
                    Vec3d p = new Vec3d(s.ground.x + Math.cos(a) * d, s.cloudY + 0.4, s.ground.z + Math.sin(a) * d);
                    double b = r.nextDouble() * Math.PI * 2, len = 3 + r.nextDouble() * 6;
                    s.flashes.add(new Flash(p, p.add(Math.cos(b) * len, -0.6 - r.nextDouble(), Math.sin(b) * len), now, r.nextLong()));
                }
            } else {
                long since = now - s.struck;
                // Each return stroke lights the whole sky again
                if (since == 3 || since == 6 || since == 10) w.setLightningTicksLeft(2);
                if (since < 30 && since % 2 == 0) {
                    w.addParticle(ParticleTypes.LARGE_SMOKE, s.ground.x + (r.nextDouble() - 0.5) * 2, s.ground.y + 0.4, s.ground.z + (r.nextDouble() - 0.5) * 2, 0, 0.06, 0);
                }
                if (since < CLOUD_FADE && r.nextFloat() < 0.2f) {
                    double a = r.nextDouble() * Math.PI * 2, d = r.nextDouble() * CLOUD_RADIUS * 0.6;
                    Vec3d p = new Vec3d(s.ground.x + Math.cos(a) * d, s.cloudY + 0.4, s.ground.z + Math.sin(a) * d);
                    s.flashes.add(new Flash(p, p.add((r.nextDouble() - 0.5) * 8, -0.5, (r.nextDouble() - 0.5) * 8), now, r.nextLong()));
                }
            }
            s.flashes.removeIf(f -> now - f.time() > 3);
        }
    }

    /** How bright the bolt is {@code t} ticks after the strike: a first stroke, then flickering return strokes. */
    static float strokes(float t) {
        if (t < 0 || t > BOLT_TICKS) return 0f;
        float[][] keys = {{0, 1f}, {1.5f, 0.3f}, {3, 1f}, {4.5f, 0.25f}, {6, 0.9f}, {8, 0.2f}, {10, 0.65f}, {12.5f, 0.12f}, {BOLT_TICKS, 0f}};
        for (int i = 1; i < keys.length; i++) {
            if (t <= keys[i][0]) {
                float f = (t - keys[i - 1][0]) / (keys[i][0] - keys[i - 1][0]);
                return MathHelper.lerp(f, keys[i - 1][1], keys[i][1]);
            }
        }
        return 0f;
    }

    private static float screenFlash(float td) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null || STORMS.isEmpty()) return 0f;
        float best = 0f;
        for (Storm s : STORMS) {
            float t = s.sinceStrike(client.world, td);
            if (t < 0 || t > 12) continue;
            double near = MathHelper.clamp(1 - client.player.getPos().distanceTo(s.ground) / 90, 0, 1);
            best = Math.max(best, (float) (0.55 * strokes(t) * near));
        }
        return best;
    }

    // ---------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------

    private static void render(WorldRenderContext ctx) {
        if (STORMS.isEmpty() && ARCS.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld w = ctx.world();
        if (w == null) return;
        float td = ctx.tickCounter().getTickDelta(false);
        Vec3d cam = ctx.camera().getPos();
        MatrixStack matrices = ctx.matrixStack() != null ? ctx.matrixStack() : new MatrixStack();
        Matrix4f m = matrices.peek().getPositionMatrix();
        VertexConsumerProvider.Immediate buffers = client.getBufferBuilders().getEntityVertexConsumers();

        VertexConsumer cloud = buffers.getBuffer(CLOUD);
        for (Storm s : STORMS) drawCloud(cloud, m, w, s, cam, td);
        buffers.draw(CLOUD);

        VertexConsumer glow = buffers.getBuffer(GLOW);
        for (Storm s : STORMS) {
            drawFlashes(glow, m, w, s, cam, td);
            if (s.struck < 0) drawWarning(glow, m, w, s, cam, td);
            else drawBolt(glow, m, w, s, cam, td);
        }
        long now = w.getTime();
        for (ArcFx a : ARCS) drawArc(glow, m, a, now, cam, td);
        buffers.draw(GLOW);
    }

    private static void drawCloud(VertexConsumer vc, Matrix4f m, ClientWorld w, Storm s, Vec3d cam, float td) {
        float age = s.sinceStart(w, td);
        float grow = (float) (easeOut(MathHelper.clamp(age / CLOUD_GROW, 0f, 1f)) * (CLOUD_RADIUS + 4));
        float after = s.sinceStrike(w, td);
        float fade = after < 0 ? 1f : 1f - MathHelper.clamp((after - 20) / CLOUD_FADE, 0f, 1f);
        if (fade <= 0f) return;
        float lit = after >= 0 ? strokes(after) : 0f;
        double turn = age * 0.012;
        double cos = Math.cos(turn), sin = Math.sin(turn);
        long now = w.getTime();
        for (Cell c : s.cells) {
            float scale = MathHelper.clamp((float) ((grow - c.dist()) / 4), 0f, 1f);
            if (scale <= 0f) continue;
            scale *= 0.85f + 0.15f * fade;
            double x = s.ground.x + c.x() * cos - c.z() * sin, z = s.ground.z + c.x() * sin + c.z() * cos;
            double y = s.cloudY + c.y() + Math.sin(age * 0.07 + c.dist()) * 0.15;
            // Lit from inside by nearby flickers, and all of it by the strike
            float bright = lit * 0.7f;
            for (Flash f : s.flashes) {
                float k = 1f - (now - f.time() + td) / 3f;
                if (k <= 0) continue;
                double d = Math.hypot(f.a().x - x, f.a().z - z);
                bright += k * (float) Math.max(0, 1 - d / 9) * 0.8f;
            }
            float base = 0.15f + bright * 0.55f;
            float alpha = 0.9f * scale * fade;
            double hw = c.w() * 0.5 * scale, hh = c.h() * scale;
            box(vc, m, x - hw - cam.x, y - cam.y, z - hw - cam.z, x + hw - cam.x, y + hh - cam.y, z + hw - cam.z,
                    base, base * 1.03f + bright * 0.05f, base * 1.2f + bright * 0.25f, alpha);
        }
    }

    private static void drawFlashes(VertexConsumer vc, Matrix4f m, ClientWorld w, Storm s, Vec3d cam, float td) {
        long now = w.getTime();
        for (Flash f : s.flashes) {
            float k = 1f - (now - f.time() + td) / 3f;
            if (k <= 0) continue;
            Random r = new Random(f.seed());
            jagged(vc, m, f.a().subtract(cam), f.b().subtract(cam), r, 0.22f, k * 0.9f, 0.5f);
        }
    }

    private static void drawWarning(VertexConsumer vc, Matrix4f m, ClientWorld w, Storm s, Vec3d cam, float td) {
        float age = s.sinceStart(w, td);
        Random flicker = new Random(s.seed + (long) age * 977);
        // Stepped leaders: creeping down the bolt's own path, in jumps
        float leaderStart = s.warn - LEADER_TICKS;
        if (age > leaderStart) {
            float p = MathHelper.clamp((age - leaderStart) / LEADER_TICKS, 0f, 1f);
            p = (float) Math.floor(p * 13) / 13f; // stepwise, the way a leader really comes down
            double reach = s.bolt.length * p * 0.94;
            float alpha = 0.3f + 0.35f * flicker.nextFloat();
            polyline(vc, m, s.bolt.trunk, s.bolt.along, reach, cam, 0.16f, alpha);
            for (BoltShape.Line b : s.bolt.branches) {
                if (b.forkAt() * s.bolt.length > reach) continue;
                double grownLen = Math.min(1, (reach - b.forkAt() * s.bolt.length) / 6) * lineLength(b.pts());
                polyline(vc, m, b.pts(), cumulative(b.pts()), grownLen, cam, 0.09f, alpha * 0.7f);
            }
        }
        // Streamers: little sparks reaching up from the ground, and from whatever's standing there
        float streamStart = s.warn - STREAMER_TICKS;
        if (age > streamStart) {
            float k = MathHelper.clamp((age - streamStart) / STREAMER_TICKS, 0f, 1f);
            Random r = new Random(s.seed * 13);
            for (int i = 0; i < 7; i++) {
                double a = r.nextDouble() * Math.PI * 2, d = 1 + r.nextDouble() * (Thunderstrike.STRIKE_RADIUS - 1);
                double x = s.ground.x + Math.cos(a) * d, z = s.ground.z + Math.sin(a) * d;
                double y = w.getTopY(Heightmap.Type.MOTION_BLOCKING, MathHelper.floor(x), MathHelper.floor(z));
                Vec3d base = new Vec3d(x, y, z);
                jagged(vc, m, base.subtract(cam), base.add(0, 0.6 + 2.4 * k, 0).subtract(cam), flicker, 0.1f, 0.5f + 0.4f * flicker.nextFloat(), 0.25f);
            }
            Box zone = new Box(s.ground, s.ground).expand(Thunderstrike.STRIKE_RADIUS, 4, Thunderstrike.STRIKE_RADIUS);
            for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, zone, LivingEntity::isAlive)) {
                Vec3d head = e.getLerpedPos(td).add(0, e.getHeight(), 0);
                jagged(vc, m, head.subtract(cam), head.add(0, 0.4 + 1.8 * k, 0).subtract(cam), flicker, 0.09f, 0.6f, 0.2f);
            }
        }
    }

    private static void drawBolt(VertexConsumer vc, Matrix4f m, ClientWorld w, Storm s, Vec3d cam, float td) {
        float t = s.sinceStrike(w, td);
        float i = strokes(t);
        if (i <= 0.01f) return;
        float thick = 0.85f + 0.15f * i;
        Vec3d[] trunk = s.bolt.trunk;
        for (int k = 0; k < trunk.length - 1; k++) {
            // A touch thicker low down, where it's carrying everything to the ground
            float low = 0.85f + 0.3f * k / (trunk.length - 1);
            boltSegment(vc, m, trunk[k].subtract(cam), trunk[k + 1].subtract(cam), TRUNK * thick * low, i);
        }
        // Branches show bright on the first stroke and fade, as the return strokes run down the trunk alone
        float branchAlpha = i * (t < 4 ? 1f : MathHelper.clamp(1 - (t - 4) / 5, 0f, 1f));
        if (branchAlpha > 0.01f) {
            for (BoltShape.Line b : s.bolt.branches) {
                for (int k = 0; k < b.pts().length - 1; k++) {
                    boltSegment(vc, m, b.pts()[k].subtract(cam), b.pts()[k + 1].subtract(cam), b.width() * thick, branchAlpha);
                }
            }
        }
        // Where it hits: a blaze of light, and a ring of it running out over the ground
        Vec3d g = s.ground.subtract(cam);
        Vec3d toCam = g.lengthSquared() > 1e-6 ? g.normalize().multiply(-1) : new Vec3d(0, 0, 1);
        Vec3d right = toCam.crossProduct(new Vec3d(0, 1, 0));
        right = right.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : right.normalize();
        Vec3d up = right.crossProduct(toCam).normalize();
        disc(vc, m, g.add(0, 0.8, 0), right, up, 4f + 4f * i, 0.75f, 0.8f, 1f, 0.6f * i);
        disc(vc, m, g.add(0, 0.8, 0), right, up, 1.5f + 1.5f * i, 1f, 1f, 1f, 0.7f * i);
        if (t < 10) {
            float rr = 1 + t * 1.3f, a = (1 - t / 10f) * 0.7f;
            ring(vc, m, g.add(0, 0.15, 0), rr, 0.7f, 0.7f, 0.8f, 1f, a);
        }
    }

    private static void drawArc(VertexConsumer vc, Matrix4f m, ArcFx a, long now, Vec3d cam, float td) {
        float age = now - a.start() + td;
        if (age > ARC_TICKS) return;
        float alpha = (1 - age / ARC_TICKS) * (0.7f + 0.3f * (float) Math.random());
        Random r = new Random(a.seed() + (long) age * 31); // a fresh crackle every tick
        float w = 0.55f + 0.5f * a.power();
        jagged(vc, m, a.from().subtract(cam), a.to().subtract(cam), r, w, alpha, 0.7f);
        jagged(vc, m, a.from().subtract(cam), a.to().subtract(cam), r, w * 0.4f, alpha * 0.6f, 0.9f);
    }

    // ---------------------------------------------------------------------
    // Drawing helpers (all positions relative to the camera)
    // ---------------------------------------------------------------------

    /** One piece of the giant bolt: a wide blue glow, a bright sheath and a white-hot core. */
    private static void boltSegment(VertexConsumer vc, Matrix4f m, Vec3d a, Vec3d b, float width, float i) {
        ribbon(vc, m, a, b, 3.4f * width, 0.42f, 0.5f, 1f, 0.16f * i);
        ribbon(vc, m, a, b, 1.35f * width, 0.72f, 0.78f, 1f, 0.5f * i);
        ribbon(vc, m, a, b, 0.55f * width, 1f, 1f, 1f, 0.95f * i);
    }

    /** A crackling jagged bolt from {@code a} to {@code b}, with forks, made fresh from {@code r}. */
    private static void jagged(VertexConsumer vc, Matrix4f m, Vec3d a, Vec3d b, Random r, float width, float alpha, float wild) {
        double len = a.distanceTo(b);
        if (len < 1e-3) return;
        int n = MathHelper.clamp((int) (len / 0.6), 3, 40);
        Vec3d span = b.subtract(a), dir = span.normalize(), u = perpendicular(dir), v = dir.crossProduct(u);
        Vec3d prev = a;
        for (int k = 1; k <= n; k++) {
            double t = k / (double) n;
            Vec3d p = a.add(span.multiply(t));
            if (k < n) {
                double amp = wild * Math.min(1.2, len * 0.12) * Math.sin(Math.PI * t);
                p = p.add(u.multiply(r.nextGaussian() * amp)).add(v.multiply(r.nextGaussian() * amp));
            }
            ribbon(vc, m, prev, p, width * 2.6f, 0.45f, 0.55f, 1f, alpha * 0.22f);
            ribbon(vc, m, prev, p, width, 0.88f, 0.92f, 1f, alpha);
            if (k < n && r.nextFloat() < 0.12f) {
                Vec3d out = p.add(new Vec3d(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).normalize().multiply(0.4 + r.nextDouble() * Math.min(2, len * 0.2)));
                ribbon(vc, m, p, out, width * 0.5f, 0.8f, 0.86f, 1f, alpha * 0.6f);
            }
            prev = p;
        }
    }

    /** A line drawn only {@code upTo} along its length (the leader growing down). */
    private static void polyline(VertexConsumer vc, Matrix4f m, Vec3d[] pts, double[] along, double upTo, Vec3d cam, float width, float alpha) {
        for (int k = 0; k < pts.length - 1; k++) {
            if (along[k] >= upTo) break;
            Vec3d a = pts[k], b = pts[k + 1];
            if (along[k + 1] > upTo) b = a.add(b.subtract(a).multiply((upTo - along[k]) / (along[k + 1] - along[k])));
            ribbon(vc, m, a.subtract(cam), b.subtract(cam), width * 3f, 0.45f, 0.5f, 1f, alpha * 0.3f);
            ribbon(vc, m, a.subtract(cam), b.subtract(cam), width, 0.85f, 0.88f, 1f, alpha);
        }
    }

    private static double[] cumulative(Vec3d[] pts) {
        double[] c = new double[pts.length];
        for (int i = 1; i < pts.length; i++) c[i] = c[i - 1] + pts[i].distanceTo(pts[i - 1]);
        return c;
    }

    private static double lineLength(Vec3d[] pts) {
        double[] c = cumulative(pts);
        return c[c.length - 1];
    }

    /** A strip from {@code a} to {@code b}, a hair longer so joints close up, turned to face the camera. */
    private static void ribbon(VertexConsumer vc, Matrix4f m, Vec3d a, Vec3d b, float width, float r, float g, float bl, float alpha) {
        Vec3d d = b.subtract(a);
        double l = d.length();
        if (l < 1e-5 || alpha <= 0.004f) return;
        Vec3d n = d.multiply(1.0 / l);
        Vec3d mid = a.add(b).multiply(0.5);
        Vec3d ext = n.multiply(width * 0.35);
        a = a.subtract(ext);
        b = b.add(ext);
        Vec3d side = n.crossProduct(mid);
        side = side.lengthSquared() < 1e-10 ? perpendicular(n) : side.normalize();
        side = side.multiply(width * 0.5);
        v(vc, m, a.subtract(side), r, g, bl, alpha);
        v(vc, m, b.subtract(side), r, g, bl, alpha);
        v(vc, m, b.add(side), r, g, bl, alpha);
        v(vc, m, a.add(side), r, g, bl, alpha);
    }

    /** A round, soft blaze of light facing the camera: bright in the middle, fading to nothing at the rim. */
    private static void disc(VertexConsumer vc, Matrix4f m, Vec3d c, Vec3d right, Vec3d up, float radius, float r, float g, float b, float alpha) {
        int n = 28;
        for (int k = 0; k < n; k++) {
            double a0 = k * Math.PI * 2 / n, a1 = (k + 1) * Math.PI * 2 / n;
            Vec3d p0 = c.add(right.multiply(Math.cos(a0) * radius)).add(up.multiply(Math.sin(a0) * radius));
            Vec3d p1 = c.add(right.multiply(Math.cos(a1) * radius)).add(up.multiply(Math.sin(a1) * radius));
            v(vc, m, c, r, g, b, alpha);
            v(vc, m, c, r, g, b, alpha);
            v(vc, m, p0, r, g, b, 0f);
            v(vc, m, p1, r, g, b, 0f);
        }
    }

    /** A flat ring lying on the ground. */
    private static void ring(VertexConsumer vc, Matrix4f m, Vec3d c, float radius, float width, float r, float g, float b, float a) {
        int n = 40;
        for (int k = 0; k < n; k++) {
            double a0 = k * Math.PI * 2 / n, a1 = (k + 1) * Math.PI * 2 / n;
            float ri = radius - width / 2, ro = radius + width / 2;
            v(vc, m, c.add(Math.cos(a0) * ri, 0, Math.sin(a0) * ri), r, g, b, a);
            v(vc, m, c.add(Math.cos(a0) * ro, 0, Math.sin(a0) * ro), r, g, b, a * 0.2f);
            v(vc, m, c.add(Math.cos(a1) * ro, 0, Math.sin(a1) * ro), r, g, b, a * 0.2f);
            v(vc, m, c.add(Math.cos(a1) * ri, 0, Math.sin(a1) * ri), r, g, b, a);
        }
    }

    /** A box of cloud: lighter on top, darkest underneath. */
    private static void box(VertexConsumer vc, Matrix4f m, double x0, double y0, double z0, double x1, double y1, double z1,
                            float r, float g, float b, float a) {
        float X0 = (float) x0, Y0 = (float) y0, Z0 = (float) z0, X1 = (float) x1, Y1 = (float) y1, Z1 = (float) z1;
        quad(vc, m, X0, Y1, Z0, X0, Y1, Z1, X1, Y1, Z1, X1, Y1, Z0, r * 1.35f, g * 1.35f, b * 1.3f, a);   // top
        quad(vc, m, X0, Y0, Z0, X1, Y0, Z0, X1, Y0, Z1, X0, Y0, Z1, r * 0.7f, g * 0.7f, b * 0.75f, a);    // underside
        quad(vc, m, X0, Y0, Z0, X0, Y1, Z0, X1, Y1, Z0, X1, Y0, Z0, r, g, b, a);
        quad(vc, m, X0, Y0, Z1, X1, Y0, Z1, X1, Y1, Z1, X0, Y1, Z1, r, g, b, a);
        quad(vc, m, X0, Y0, Z0, X0, Y0, Z1, X0, Y1, Z1, X0, Y1, Z0, r * 0.9f, g * 0.9f, b * 0.9f, a);
        quad(vc, m, X1, Y0, Z0, X1, Y1, Z0, X1, Y1, Z1, X1, Y0, Z1, r * 0.9f, g * 0.9f, b * 0.9f, a);
    }

    private static void quad(VertexConsumer vc, Matrix4f m, float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz, float r, float g, float b, float a) {
        float cr = MathHelper.clamp(r, 0, 1), cg = MathHelper.clamp(g, 0, 1), cb = MathHelper.clamp(b, 0, 1);
        vc.vertex(m, ax, ay, az).color(cr, cg, cb, a);
        vc.vertex(m, bx, by, bz).color(cr, cg, cb, a);
        vc.vertex(m, cx, cy, cz).color(cr, cg, cb, a);
        vc.vertex(m, dx, dy, dz).color(cr, cg, cb, a);
    }

    private static Vec3d perpendicular(Vec3d d) {
        Vec3d p = Math.abs(d.y) < 0.9 ? d.crossProduct(new Vec3d(0, 1, 0)) : d.crossProduct(new Vec3d(1, 0, 0));
        return p.normalize();
    }

    private static void v(VertexConsumer vc, Matrix4f m, Vec3d p, float r, float g, float b, float a) {
        vc.vertex(m, (float) p.x, (float) p.y, (float) p.z).color(r, g, b, MathHelper.clamp(a, 0f, 1f));
    }

    private static float easeOut(float t) {
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }
}
