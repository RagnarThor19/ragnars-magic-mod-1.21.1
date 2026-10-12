package net.ragnar.ragnarsmagicmod.crows.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.ragnar.ragnarsmagicmod.client.CameraGlide;
import net.ragnar.ragnarsmagicmod.crows.Crows;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Client half of the Tome of Crows (see Crows). Everything here is worked out from the start packet alone, so every
 * player near either end sees the same flock.
 * <ul>
 *   <li>Crows come in out of the sky from all around and wheel high over the caster, then spiral down and in, faster
 *       and tighter, until they're a churning column of wings with the caster lost inside it. Black ink smokes out
 *       between them, and the caster is no longer drawn at all.</li>
 *   <li>The column bursts: every crow peels off its circle and flies away up and out, shedding feathers, and a stream
 *       of them arcs over to the far end.</li>
 *   <li>There a second flock, circling since the charge-up, has come down into its own column - and when it bursts
 *       apart the caster is standing in the middle of it.</li>
 *   <li>The caster watches it all from behind (third person) with their keys held, the camera flies across with the
 *       crows, and the edges of the screen go dark while they're among them.</li>
 * </ul>
 */
public final class CrowsClient {
    private CrowsClient() {}

    private static final int FLOCK = 56;
    private static final int TRAVELLERS = 12;
    /** How long a crow flying off is still drawn. */
    private static final int FLY_OFF_TICKS = 40;
    private static final int GLIDE_TICKS = 18;
    private static final Identifier VIGNETTE = Identifier.ofVanilla("textures/misc/vignette.png");

    /** One of the two places the crows gather: where the caster leaves from, and where they arrive. */
    private record Site(Vec3d center, int start, int arriveEnd, int peak, int burst) {}

    private static final class Crow {
        final boolean far; // in the flock at the far end
        final boolean traveller; // one of the stream flying from one end to the other
        int arrive, approach, burstJitter;
        Vec3d spawn; // where it comes from, relative to the site's center
        double th0, spin, rWide, rTight, hHigh, hLow, bob, out, rise;
        float flapPhase, flapSpeed, scale;
        Vec3d travelFrom, travelTo;
        double arc, sway;

        Crow(boolean far, boolean traveller) {
            this.far = far;
            this.traveller = traveller;
        }
    }

    private static final class Feather {
        Vec3d pos, prev, vel;
        final float length, phase;
        float spin, prevSpin;
        final float spinSpeed;
        int age;
        final int life;
        boolean resting;

        Feather(Vec3d pos, Vec3d vel, float length, float phase, float spinSpeed, int life) {
            this.pos = this.prev = pos;
            this.vel = vel;
            this.length = length;
            this.phase = phase;
            this.spinSpeed = spinSpeed;
            this.life = life;
        }
    }

    private static final class Flight {
        final int casterId;
        final ClientWorld world;
        final Site near, away;
        final List<Crow> crows = new ArrayList<>();
        final Random random;
        int age;
        boolean cancelled;

        Flight(int casterId, ClientWorld world, Vec3d from, Vec3d to, long seed) {
            this.casterId = casterId;
            this.world = world;
            this.near = new Site(from.add(0, 1, 0), 0, 34, Crows.VANISH, Crows.LEAVE);
            this.away = new Site(to.add(0, 1, 0), 18, 64, Crows.APPEAR - 2, Crows.SCATTER);
            this.random = Random.create(seed);
        }

        Site site(Crow c) {
            return c.far ? away : near;
        }
    }

    private static final Map<Integer, Flight> FLIGHTS = new HashMap<>();
    private static final List<Feather> FEATHERS = new ArrayList<>();
    private static Perspective perspectiveBefore;

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(Crows.StartPayload.ID, (payload, context) -> {
            MinecraftClient client = context.client();
            if (client.world == null) return;
            Flight f = new Flight(payload.casterId(), client.world, new Vec3d(payload.from()), new Vec3d(payload.to()), payload.seed());
            populate(f);
            FLIGHTS.put(payload.casterId(), f);
            if (client.player != null && client.player.getId() == payload.casterId()) {
                if (perspectiveBefore == null) perspectiveBefore = client.options.getPerspective();
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(Crows.MovedPayload.ID, (payload, context) -> CameraGlide.start(GLIDE_TICKS));
        ClientPlayNetworking.registerGlobalReceiver(Crows.CancelPayload.ID, (payload, context) -> {
            Flight f = FLIGHTS.get(payload.casterId());
            if (f != null) f.cancelled = true;
            Crows.CLIENT.remove(payload.casterId());
            MinecraftClient client = context.client();
            if (client.player != null && client.player.getId() == payload.casterId()) restoreView(client);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            FLIGHTS.clear();
            FEATHERS.clear();
            Crows.CLIENT.clear();
            restoreView(client);
        });
        ClientTickEvents.END_CLIENT_TICK.register(CrowsClient::tick);
        WorldRenderEvents.AFTER_ENTITIES.register(CrowsClient::render);
        HudRenderCallback.EVENT.register(CrowsClient::renderHud);
    }

    private static void restoreView(MinecraftClient client) {
        if (perspectiveBefore != null) client.options.setPerspective(perspectiveBefore);
        perspectiveBefore = null;
    }

    /** True while {@code player}'s keys are held: from the cast until they've arrived. Read by CrowsInputMixin. */
    public static boolean rooted(PlayerEntity player) {
        Flight f = FLIGHTS.get(player.getId());
        return f != null && !f.cancelled && f.age < Crows.APPEAR + 2;
    }

    // ---------------------------------------------------------------------
    // The flock
    // ---------------------------------------------------------------------

    private static void populate(Flight f) {
        Random r = f.random;
        for (int k = 0; k < 2; k++) {
            boolean far = k == 1;
            Site s = far ? f.away : f.near;
            for (int i = 0; i < FLOCK; i++) {
                Crow c = new Crow(far, false);
                // Most come early; the last few hurry in at the end
                double u = Math.pow(r.nextDouble(), 0.8);
                c.arrive = s.start() + (int) (u * (s.arriveEnd() - s.start()));
                c.approach = 16 + r.nextInt(8);
                double yaw = r.nextDouble() * Math.PI * 2, dist = 16 + r.nextDouble() * 10;
                c.spawn = new Vec3d(Math.cos(yaw) * dist, 6 + r.nextDouble() * 10, Math.sin(yaw) * dist);
                c.th0 = r.nextDouble() * Math.PI * 2;
                c.spin = r.nextInt(7) == 0 ? -1 : 1; // a few cut across the rest
                c.rWide = 2.4 + r.nextDouble() * 1.6;
                c.rTight = 0.32 + r.nextDouble() * 0.4;
                c.hHigh = 2.5 + r.nextDouble() * 3.0;
                c.hLow = -1.0 + r.nextDouble() * 2.1;
                c.bob = r.nextDouble() * Math.PI * 2;
                c.out = 0.3 + r.nextDouble() * 0.35;
                c.rise = 0.1 + r.nextDouble() * 0.4;
                c.burstJitter = r.nextInt(4);
                c.flapPhase = r.nextFloat() * MathHelper.TAU;
                c.flapSpeed = 1.3f + r.nextFloat() * 0.5f;
                c.scale = 0.95f + r.nextFloat() * 0.35f;
                f.crows.add(c);
            }
        }
        // The stream that carries the caster over
        for (int i = 0; i < TRAVELLERS; i++) {
            Crow c = new Crow(false, true);
            c.arrive = Crows.LEAVE + r.nextInt(8);
            c.approach = 15 + r.nextInt(6);
            c.travelFrom = f.near.center().add(r.nextGaussian() * 0.4, r.nextGaussian() * 0.5, r.nextGaussian() * 0.4);
            c.travelTo = f.away.center().add(r.nextGaussian() * 0.4, r.nextGaussian() * 0.5, r.nextGaussian() * 0.4);
            c.arc = 2.0 + f.near.center().distanceTo(f.away.center()) * 0.12 + r.nextDouble() * 2;
            c.sway = r.nextGaussian() * 1.5;
            c.flapPhase = r.nextFloat() * MathHelper.TAU;
            c.flapSpeed = 1.6f + r.nextFloat() * 0.4f;
            c.scale = 0.95f + r.nextFloat() * 0.3f;
            f.crows.add(c);
        }
    }

    private static double smooth(double x) {
        x = MathHelper.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }

    /** Where crow {@code c} is on its circle at time {@code t}: wide and high at first, then down and in, ever faster. */
    private static Vec3d orbit(Crow c, Site s, double t) {
        double span = s.peak() - s.start();
        double tt = t - s.start();
        double k = MathHelper.clamp(tt / span, 0, 1);
        double e = smooth(k);
        double radius = MathHelper.lerp(e, c.rWide, c.rTight);
        double height = MathHelper.lerp(e, c.hHigh, c.hLow) + 0.18 * Math.sin(t * 0.23 + c.bob);
        // Angular speed climbs from 0.1 to 0.85 rad/tick by the peak, and holds
        double turned = 0.1 * tt + 0.75 * (tt <= span ? span * k * k * k / 3 : span / 3 + (tt - span));
        double th = c.th0 + c.spin * turned;
        return s.center().add(Math.cos(th) * radius, height, Math.sin(th) * radius);
    }

    /** Where crow {@code c} is at time {@code t}, or null if it isn't there (not yet come, or long gone). */
    private static Vec3d pos(Flight f, Crow c, double t) {
        if (t < c.arrive) return null;
        if (c.traveller) {
            double u = (t - c.arrive) / c.approach;
            if (u >= 1) return null;
            double e = smooth(u);
            Vec3d d = c.travelTo.subtract(c.travelFrom);
            Vec3d side = d.crossProduct(new Vec3d(0, 1, 0));
            side = side.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : side.normalize();
            double hump = Math.sin(Math.PI * e);
            return c.travelFrom.add(d.multiply(e)).add(0, c.arc * hump, 0).add(side.multiply(c.sway * hump));
        }
        Site s = f.site(c);
        int burst = s.burst() + c.burstJitter;
        if (t < c.arrive + c.approach && t < burst) {
            double u = (t - c.arrive) / c.approach;
            return s.center().add(c.spawn).lerp(orbit(c, s, t), smooth(u));
        }
        if (t < burst) return orbit(c, s, t);
        // Flying off: away along its circle, out and up, faster and faster
        double dt = t - burst;
        if (dt > FLY_OFF_TICKS) return null;
        Vec3d p0 = orbit(c, s, burst);
        Vec3d tangent = orbit(c, s, burst + 0.05).subtract(p0);
        tangent = tangent.lengthSquared() < 1e-9 ? Vec3d.ZERO : tangent.normalize();
        Vec3d outward = new Vec3d(p0.x - s.center().x, 0, p0.z - s.center().z);
        outward = outward.lengthSquared() < 1e-6 ? tangent : outward.normalize();
        Vec3d v0 = tangent.multiply(0.4).add(outward.multiply(c.out)).add(0, c.rise, 0);
        Vec3d acc = outward.multiply(0.03).add(0, 0.02, 0);
        return p0.add(v0.multiply(dt)).add(acc.multiply(0.5 * dt * dt));
    }

    // ---------------------------------------------------------------------
    // Ticking
    // ---------------------------------------------------------------------

    private static void tick(MinecraftClient client) {
        if (client.isPaused()) return;
        if (!FEATHERS.isEmpty()) tickFeathers();
        if (FLIGHTS.isEmpty()) return;
        Iterator<Flight> it = FLIGHTS.values().iterator();
        while (it.hasNext()) {
            Flight f = it.next();
            if (f.world != client.world) {
                it.remove();
                Crows.CLIENT.remove(f.casterId);
                continue;
            }
            int t = ++f.age;
            boolean mine = client.player != null && client.player.getId() == f.casterId;
            if (!f.cancelled) {
                if (t >= Crows.VANISH && t < Crows.APPEAR) Crows.CLIENT.add(f.casterId);
                else Crows.CLIENT.remove(f.casterId);
                effects(f, t);
            }
            if (mine && (t == Crows.DURATION || f.cancelled)) restoreView(client);
            if (t > Crows.SCATTER + FLY_OFF_TICKS + 4 || (f.cancelled && t > Crows.DURATION)) {
                it.remove();
                Crows.CLIENT.remove(f.casterId);
            }
        }
    }

    /** Ink, smoke and feathers, all along the way. */
    private static void effects(Flight f, int t) {
        Random r = f.random;
        ClientWorld w = f.world;
        Vec3d a = f.near.center(), b = f.away.center();

        // The charge-up: shadow smoking up off the ground and swirling round the caster
        if (t < Crows.VANISH) {
            int n = 1 + t / 14;
            for (int i = 0; i < n; i++) {
                double th = r.nextDouble() * Math.PI * 2, rad = 0.6 + r.nextDouble() * 1.0;
                double x = a.x + Math.cos(th) * rad, z = a.z + Math.sin(th) * rad;
                w.addParticle(ParticleTypes.SMOKE, x, a.y - 0.95, z, -Math.sin(th) * 0.06, 0.03 + r.nextDouble() * 0.04, Math.cos(th) * 0.06);
            }
        }
        // Ink thickening inside each column as it closes
        if (t >= Crows.VANISH - 10 && t < Crows.LEAVE) ink(w, r, a, 3);
        if (t >= Crows.APPEAR - 14 && t < Crows.SCATTER) ink(w, r, b, 3);
        if (t == Crows.LEAVE) burstFx(w, r, a);
        if (t == Crows.SCATTER) burstFx(w, r, b);

        // Now and then a crow loses a feather
        for (Crow c : f.crows) {
            if (r.nextFloat() > (c.traveller ? 0.02f : 0.006f)) continue;
            Vec3d p = pos(f, c, t);
            if (p != null) feather(r, p, new Vec3d(r.nextGaussian() * 0.04, 0.02, r.nextGaussian() * 0.04));
        }
    }

    private static void ink(ClientWorld w, Random r, Vec3d c, int n) {
        for (int i = 0; i < n; i++) {
            w.addParticle(ParticleTypes.SQUID_INK, c.x + r.nextGaussian() * 0.3, c.y - 0.8 + r.nextDouble() * 1.9, c.z + r.nextGaussian() * 0.3,
                    r.nextGaussian() * 0.03, r.nextDouble() * 0.04, r.nextGaussian() * 0.03);
        }
    }

    private static void burstFx(ClientWorld w, Random r, Vec3d c) {
        for (int i = 0; i < 40; i++) {
            Vec3d v = new Vec3d(r.nextGaussian(), r.nextGaussian() * 0.6 + 0.3, r.nextGaussian()).normalize().multiply(0.15 + r.nextDouble() * 0.25);
            w.addParticle(ParticleTypes.SQUID_INK, c.x + r.nextGaussian() * 0.3, c.y + r.nextGaussian() * 0.6, c.z + r.nextGaussian() * 0.3, v.x, v.y, v.z);
        }
        for (int i = 0; i < 14; i++) {
            Vec3d v = new Vec3d(r.nextGaussian(), 0.4, r.nextGaussian()).normalize().multiply(0.12);
            w.addParticle(ParticleTypes.LARGE_SMOKE, c.x, c.y - 0.5 + r.nextDouble() * 1.5, c.z, v.x, v.y, v.z);
        }
        for (int i = 0; i < 34; i++) {
            Vec3d v = new Vec3d(r.nextGaussian(), r.nextDouble() * 1.2, r.nextGaussian()).normalize().multiply(0.12 + r.nextDouble() * 0.25);
            feather(r, c.add(r.nextGaussian() * 0.4, r.nextGaussian() * 0.6, r.nextGaussian() * 0.4), v);
        }
    }

    private static void feather(Random r, Vec3d p, Vec3d v) {
        if (FEATHERS.size() > 400) return;
        FEATHERS.add(new Feather(p, v, 0.14f + r.nextFloat() * 0.1f, r.nextFloat() * MathHelper.TAU,
                (r.nextFloat() - 0.5f) * 0.5f, 70 + r.nextInt(50)));
    }

    private static void tickFeathers() {
        MinecraftClient client = MinecraftClient.getInstance();
        Iterator<Feather> it = FEATHERS.iterator();
        while (it.hasNext()) {
            Feather f = it.next();
            if (++f.age > f.life || client.world == null) {
                it.remove();
                continue;
            }
            f.prev = f.pos;
            f.prevSpin = f.spin;
            if (f.resting) continue;
            // Drifting down, rocking side to side as feathers do
            double sway = Math.sin(f.age * 0.22 + f.phase) * 0.025;
            f.vel = f.vel.multiply(0.88).add(sway * Math.cos(f.phase), -0.006, sway * Math.sin(f.phase));
            if (f.vel.y < -0.05) f.vel = new Vec3d(f.vel.x, -0.05, f.vel.z);
            Vec3d next = f.pos.add(f.vel);
            BlockPos below = BlockPos.ofFloored(next);
            var shape = client.world.getBlockState(below).getCollisionShape(client.world, below);
            if (!shape.isEmpty()) {
                // Landed: lie flat on top of it (or stay put against the side of it)
                f.resting = true;
                double top = below.getY() + shape.getMax(net.minecraft.util.math.Direction.Axis.Y);
                if (f.pos.y >= top - 0.2) f.pos = new Vec3d(f.pos.x, top + 0.01, f.pos.z);
                f.prev = f.pos;
                continue;
            }
            f.pos = next;
            f.spin += f.spinSpeed;
        }
    }

    // ---------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------

    private static void render(WorldRenderContext context) {
        if (FLIGHTS.isEmpty() && FEATHERS.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        float tickDelta = context.tickCounter().getTickDelta(false);
        Vec3d cam = context.camera().getPos();
        MatrixStack ms = context.matrixStack() != null ? context.matrixStack() : new MatrixStack();
        Matrix4f pose = ms.peek().getPositionMatrix();
        VertexConsumerProvider.Immediate buffers = client.getBufferBuilders().getEntityVertexConsumers();
        VertexConsumer vc = buffers.getBuffer(CrowDraw.SOLID);

        for (Flight f : FLIGHTS.values()) {
            if (f.cancelled) continue;
            double t = f.age + tickDelta;
            for (Crow c : f.crows) drawCrow(vc, pose, cam, f, c, t);
        }
        for (Feather f : FEATHERS) {
            Vec3d p = f.prev.lerp(f.pos, tickDelta).subtract(cam);
            float spin = MathHelper.lerp(tickDelta, f.prevSpin, f.spin);
            float shrink = MathHelper.clamp((f.life - f.age - tickDelta) / 12f, 0f, 1f);
            if (shrink <= 0f) continue;
            Vec3d axis, across;
            if (f.resting) {
                axis = new Vec3d(Math.cos(f.phase), 0, Math.sin(f.phase));
                across = new Vec3d(-Math.sin(f.phase), 0, Math.cos(f.phase));
            } else {
                // Tumbling: the shaft turns about the vertical and tips back and forth
                double tilt = 0.5 * Math.sin((f.age + tickDelta) * 0.25 + f.phase);
                axis = new Vec3d(Math.cos(spin) * Math.cos(tilt), Math.sin(tilt), Math.sin(spin) * Math.cos(tilt));
                across = axis.crossProduct(new Vec3d(0, 1, 0));
                across = across.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : across.normalize();
                across = across.multiply(Math.cos(f.phase + f.age * 0.1)).add(axis.crossProduct(across).multiply(Math.sin(f.phase + f.age * 0.1)));
            }
            CrowDraw.feather(vc, pose, p, axis, across, f.length * shrink, f.length * 0.32f * shrink);
        }
        buffers.draw(CrowDraw.SOLID);
    }

    private static void drawCrow(VertexConsumer vc, Matrix4f pose, Vec3d cam, Flight f, Crow c, double t) {
        Vec3d p = pos(f, c, t);
        if (p == null) return;
        Vec3d before = pos(f, c, t - 0.15), after = pos(f, c, t + 0.15);
        if (before == null) before = p;
        if (after == null) after = p;
        Vec3d vel = after.subtract(before);
        if (vel.lengthSquared() < 1e-8) vel = new Vec3d(1, 0, 0);
        Vec3d fwd = vel.normalize();
        // Bank into the turn: lean towards where it's being pulled
        Vec3d accel = after.add(before).subtract(p.multiply(2));
        Vec3d right = fwd.crossProduct(new Vec3d(0, 1, 0));
        right = right.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : right.normalize();
        Vec3d up = right.crossProduct(fwd).normalize();
        double lean = MathHelper.clamp(accel.dotProduct(right) * 60, -1.1, 1.1);
        Vec3d bankedUp = up.multiply(Math.cos(lean)).add(right.multiply(Math.sin(lean)));
        Vec3d bankedRight = fwd.crossProduct(bankedUp).normalize();

        // Wings: steady beats while circling, frantic ones as it bursts away, and the odd glide coming in
        double speed = vel.length() / 0.3;
        float beat = (float) (t * c.flapSpeed * (speed > 0.9 ? 1.35 : 1.0)) + c.flapPhase;
        float amp = 0.75f;
        if (!c.traveller && t < c.arrive + c.approach * 0.6) amp = 0.25f + 0.2f * MathHelper.sin(beat * 0.3f); // gliding in
        float flap = amp * MathHelper.sin(beat) + 0.12f;
        CrowDraw.crow(vc, pose, p.subtract(cam), fwd, bankedRight, bankedUp, c.scale, flap);
    }

    /** While you're among the crows the edges of your screen go dark. */
    private static void renderHud(DrawContext context, RenderTickCounter counter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        Flight f = FLIGHTS.get(client.player.getId());
        if (f == null || f.cancelled) return;
        float t = f.age + counter.getTickDelta(false);
        float in = MathHelper.clamp((t - (Crows.VANISH - 8)) / 8f, 0f, 1f);
        float out = MathHelper.clamp((Crows.SCATTER + 6 - t) / 10f, 0f, 1f);
        float a = Math.min(in, out) * 0.95f;
        if (a <= 0f) return;
        int w = context.getScaledWindowWidth(), h = context.getScaledWindowHeight();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SrcFactor.ZERO, GlStateManager.DstFactor.ONE_MINUS_SRC_COLOR,
                GlStateManager.SrcFactor.ONE, GlStateManager.DstFactor.ZERO);
        for (int i = 0; i < 2; i++) {
            context.setShaderColor(a, a, a, 1f);
            context.drawTexture(VIGNETTE, 0, 0, -90, 0f, 0f, w, h, w, h);
        }
        context.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }
}
