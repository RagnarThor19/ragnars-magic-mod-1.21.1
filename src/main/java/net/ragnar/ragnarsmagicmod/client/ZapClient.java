package net.ragnar.ragnarsmagicmod.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.LivingEntity;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.ragnar.ragnarsmagicmod.network.ZapPayload;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Draws the Tome of Zap: a thin, solid, zig-zagging bolt of yellow-white lightning that races out of the staff to
 * whatever was hit, with a spark riding its tip. It holds there for a moment, flickering and throwing off little
 * forks, then pulls in towards the target and dies away. Where it lands it bursts into a star of sparks, with a
 * flash and bits of the block knocked loose. Flat glowing ribbons on top of the world, like vanilla lightning.
 */
public final class ZapClient {
    private ZapClient() {}

    private static final RenderLayer LAYER = RenderLayer.of("ragnarsmagicmod_zap",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 16384, false, true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.LIGHTNING_TRANSPARENCY)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    /** Blocks per tick on its way out: quick, but you can still see it race to the target. */
    private static final double SPEED = 15.0;
    private static final float MIN_TRAVEL = 0.6f, MAX_TRAVEL = 2.2f;
    private static final int HOLD = 2, FADE = 5, BURST = 4;
    /** How far along from the staff the bolt bends to stay on the staff tip of a caster who moves. */
    private static final double ANCHOR = 2.0;
    private static final int MAX_ZAPS = 32;

    // A white-hot core in a yellow sheath, in a soft amber glow
    private static final float CORE_W = 0.035f, MID_W = 0.075f, GLOW_W = 0.14f;

    private static final class Zap {
        final int casterId, hit;
        final Vec3d start, end, normal;
        final long seed;
        final Vec3d[] nodes;
        final double[] dist;
        final float travel;
        int age, struckAt;
        boolean struck;

        Zap(int casterId, int hit, Vec3d start, Vec3d end, Vec3d normal, long seed) {
            this.casterId = casterId;
            this.hit = hit;
            this.start = start;
            this.end = end;
            this.normal = normal;
            this.seed = seed;
            this.nodes = path(start, end, Random.create(seed));
            this.dist = new double[nodes.length];
            for (int i = 1; i < nodes.length; i++) dist[i] = dist[i - 1] + nodes[i - 1].distanceTo(nodes[i]);
            this.travel = MathHelper.clamp((float) (length() / SPEED), MIN_TRAVEL, MAX_TRAVEL);
        }

        double length() {
            return dist[dist.length - 1];
        }

        boolean done() {
            return age > travel + HOLD + FADE;
        }
    }

    private static final List<Zap> ZAPS = new ArrayList<>();

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(ZapPayload.ID, (payload, context) -> {
            MinecraftClient client = context.client();
            if (client.world == null) return;
            Vec3d start = new Vec3d(payload.from());
            if (client.world.getEntityById(payload.casterId()) instanceof LivingEntity caster) start = staffTip(caster, 1f);
            if (ZAPS.size() >= MAX_ZAPS) ZAPS.remove(0);
            ZAPS.add(new Zap(payload.casterId(), payload.hit(), start, new Vec3d(payload.to()), new Vec3d(payload.normal()),
                    client.world.random.nextLong()));
            if (client.player != null && payload.casterId() == client.player.getId()) ScreenShake.kick(0.18f, 4);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ZAPS.clear());
        ClientTickEvents.END_CLIENT_TICK.register(ZapClient::tick);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(ZapClient::renderWorld);
    }

    // ---------------------------------------------------------------------
    // Shape
    // ---------------------------------------------------------------------

    /**
     * Short straight runs that kick from side to side, the side they kick to slowly turning around the bolt so it
     * zig-zags in 3D. Tucked in close at the target, and straighter for the first couple of blocks out of the staff
     * so it reads as a clean line leaving your hand in first person.
     */
    private static Vec3d[] path(Vec3d a, Vec3d b, Random r) {
        Vec3d span = b.subtract(a);
        double len = span.length();
        int n = Math.max(3, (int) Math.round(len / 0.65));
        Vec3d dir = len > 1e-6 ? span.multiply(1.0 / len) : new Vec3d(0, 1, 0);
        Vec3d u = perpendicular(dir), v = dir.crossProduct(u);
        double scale = Math.min(1.0, 0.45 + len / 14.0);
        double angle = r.nextDouble() * Math.PI * 2;

        Vec3d[] pts = new Vec3d[n + 1];
        pts[0] = a;
        pts[n] = b;
        for (int i = 1; i < n; i++) {
            double t = (i + (r.nextDouble() - 0.5) * 0.5) / n;
            double d = t * len;
            double ramp = Math.min(1.0, Math.min(d / Math.min(2.5, len * 0.3), (len - d) / 0.9));
            angle += r.nextGaussian() * 0.7;
            double amp = (0.1 + r.nextDouble() * 0.2) * scale * ramp * (i % 2 == 0 ? 1 : -1);
            if (r.nextFloat() < 0.15f) amp *= 1.8; // the odd sharper kink
            pts[i] = a.add(span.multiply(t)).add(u.multiply(Math.cos(angle) * amp)).add(v.multiply(Math.sin(angle) * amp));
        }
        return pts;
    }

    /** A little zig-zag from {@code a} to {@code b}, for forks and sparks. */
    private static Vec3d[] twig(Vec3d a, Vec3d b, Random r, int n, double amp) {
        Vec3d span = b.subtract(a);
        Vec3d dir = span.lengthSquared() > 1e-10 ? span.normalize() : new Vec3d(0, 1, 0);
        Vec3d u = perpendicular(dir), v = dir.crossProduct(u);
        Vec3d[] pts = new Vec3d[n + 1];
        pts[0] = a;
        pts[n] = b;
        for (int i = 1; i < n; i++) {
            double ang = r.nextDouble() * Math.PI * 2;
            double k = amp * (i % 2 == 0 ? 1 : -1) * (0.6 + r.nextDouble() * 0.4);
            pts[i] = a.add(span.multiply(i / (double) n)).add(u.multiply(Math.cos(ang) * k)).add(v.multiply(Math.sin(ang) * k));
        }
        return pts;
    }

    private static Vec3d perpendicular(Vec3d d) {
        Vec3d p = Math.abs(d.y) < 0.9 ? d.crossProduct(new Vec3d(0, 1, 0)) : d.crossProduct(new Vec3d(1, 0, 0));
        return p.normalize();
    }

    /** The point {@code d} blocks along the bolt. */
    private static Vec3d pointAt(Vec3d[] pts, double[] dist, double d) {
        for (int i = 0; i < pts.length - 1; i++) {
            if (d <= dist[i + 1]) {
                double seg = dist[i + 1] - dist[i];
                return pts[i].lerp(pts[i + 1], seg < 1e-9 ? 0 : MathHelper.clamp((d - dist[i]) / seg, 0, 1));
            }
        }
        return pts[pts.length - 1];
    }

    /** Which way the bolt runs {@code d} blocks along. */
    private static Vec3d dirAt(Vec3d[] pts, double[] dist, double d) {
        int i = 0;
        while (i < pts.length - 2 && d > dist[i + 1]) i++;
        Vec3d s = pts[i + 1].subtract(pts[i]);
        return s.lengthSquared() > 1e-10 ? s.normalize() : new Vec3d(0, 1, 0);
    }

    // ---------------------------------------------------------------------
    // Where the staff is
    // ---------------------------------------------------------------------

    private static Vec3d staffTip(LivingEntity caster, float tickDelta) {
        MinecraftClient client = MinecraftClient.getInstance();
        Vec3d eye = caster.getCameraPosVec(tickDelta);
        Vec3d look = caster.getRotationVec(tickDelta);
        Hand hand = caster instanceof ClientPlayerEntity p ? SpellSwitcher.findStaffHand(p) : null;
        Arm arm = hand == Hand.OFF_HAND ? caster.getMainArm().getOpposite() : caster.getMainArm();
        int side = arm == Arm.RIGHT ? 1 : -1;

        if (caster == client.player && client.options.getPerspective().isFirstPerson() && client.getCameraEntity() == caster) {
            // Just off the staff tip in your hand, low and to the side of the crosshair
            Vec3d right = look.crossProduct(new Vec3d(0, 1, 0));
            right = right.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : right.normalize();
            Vec3d up = right.crossProduct(look).normalize();
            return eye.add(look.multiply(0.8)).add(right.multiply(0.3 * side)).add(up.multiply(-0.17));
        }
        float bodyYaw = MathHelper.lerpAngleDegrees(tickDelta, caster.prevBodyYaw, caster.bodyYaw);
        Vec3d fwd = Vec3d.fromPolar(0, bodyYaw);
        Vec3d right = new Vec3d(-fwd.z, 0, fwd.x);
        return caster.getLerpedPos(tickDelta).add(0, caster.isInSneakingPose() ? 1.1 : 1.35, 0)
                .add(fwd.multiply(0.5)).add(right.multiply(0.36 * side)).add(look.multiply(0.35));
    }

    /** The joints shivering a little each tick, so the bolt crackles while keeping its shape. Ends stay put. */
    private static Vec3d[] crackled(Vec3d[] pts, Random r) {
        Vec3d[] out = pts.clone();
        for (int i = 1; i < out.length - 1; i++) {
            out[i] = out[i].add(r.nextGaussian() * 0.035, r.nextGaussian() * 0.035, r.nextGaussian() * 0.035);
        }
        return out;
    }

    /** The bolt's joints this frame, the end near the staff bent to follow the caster if they've moved. */
    private static Vec3d[] anchored(Zap z, ClientWorld world, float tickDelta) {
        if (!(world.getEntityById(z.casterId) instanceof LivingEntity caster)) return z.nodes;
        Vec3d shift = staffTip(caster, tickDelta).subtract(z.start);
        if (shift.lengthSquared() < 1e-8 || shift.lengthSquared() > 9) return z.nodes;
        Vec3d[] pts = new Vec3d[z.nodes.length];
        for (int i = 0; i < pts.length; i++) pts[i] = z.nodes[i].add(shift.multiply(Math.max(0, 1 - z.dist[i] / ANCHOR)));
        return pts;
    }

    // ---------------------------------------------------------------------
    // Ticking: sparks off the tip, the strike
    // ---------------------------------------------------------------------

    private static void tick(MinecraftClient client) {
        if (client.world == null) {
            ZAPS.clear();
            return;
        }
        if (client.isPaused()) return;
        Iterator<Zap> it = ZAPS.iterator();
        while (it.hasNext()) {
            Zap z = it.next();
            z.age++;
            if (z.age < z.travel) {
                Vec3d p = pointAt(z.nodes, z.dist, z.age / z.travel * z.length());
                Random r = client.world.random;
                for (int i = 0; i < 2; i++) {
                    client.world.addParticle(ParticleTypes.ELECTRIC_SPARK, p.x, p.y, p.z,
                            r.nextGaussian() * 0.08, r.nextGaussian() * 0.08, r.nextGaussian() * 0.08);
                }
            } else if (!z.struck) {
                z.struck = true;
                z.struckAt = z.age;
                strike(client, z);
            }
            // The beam crackling: a sharp snap as it lands, and a softer one as it hangs there
            if (z.struck && z.age == z.struckAt) crackle(client, z, 0.4f);
            if (z.struck && z.age == z.struckAt + 2) crackle(client, z, 0.25f);
            if (z.age >= z.travel && z.age < z.travel + HOLD + FADE / 2) {
                // Sparks spitting off along the bolt while it hangs there
                Random r = client.world.random;
                for (int i = 0; i < 3; i++) {
                    Vec3d p = pointAt(z.nodes, z.dist, r.nextDouble() * z.length());
                    client.world.addParticle(ParticleTypes.ELECTRIC_SPARK, p.x, p.y, p.z,
                            r.nextGaussian() * 0.05, r.nextGaussian() * 0.05, r.nextGaussian() * 0.05);
                }
            }
            if (z.done()) it.remove();
        }
    }

    /** A crackle of electricity off the bolt, from the point on it nearest the listener. */
    private static void crackle(MinecraftClient client, Zap z, float volume) {
        Vec3d ear = client.gameRenderer.getCamera().getPos();
        Vec3d near = z.nodes[0];
        for (Vec3d n : z.nodes) if (n.squaredDistanceTo(ear) < near.squaredDistanceTo(ear)) near = n;
        float pitch = 1.5f + client.world.random.nextFloat() * 0.35f;
        client.world.playSound(near.x, near.y, near.z, SoundEvents.ENTITY_FIREWORK_ROCKET_TWINKLE, SoundCategory.PLAYERS, volume, pitch, false);
    }

    private static void strike(MinecraftClient client, Zap z) {
        ClientWorld w = client.world;
        Random r = w.random;
        Vec3d p = z.end, n = z.normal;
        for (int i = 0; i < 14; i++) {
            Vec3d v = n.multiply(0.12 + r.nextDouble() * 0.2).add(r.nextGaussian() * 0.12, r.nextGaussian() * 0.12 + 0.05, r.nextGaussian() * 0.12);
            w.addParticle(ParticleTypes.ELECTRIC_SPARK, p.x, p.y, p.z, v.x, v.y, v.z);
        }
        if (z.hit == ZapPayload.BLOCK) {
            // Knocks a few bits off the block and leaves it smoking
            BlockState state = w.getBlockState(BlockPos.ofFloored(p.subtract(n.multiply(0.05))));
            if (!state.isAir()) {
                BlockStateParticleEffect bits = new BlockStateParticleEffect(ParticleTypes.BLOCK, state);
                for (int i = 0; i < 8; i++) {
                    Vec3d v = n.multiply(0.1 + r.nextDouble() * 0.1).add(r.nextGaussian() * 0.06, r.nextDouble() * 0.08, r.nextGaussian() * 0.06);
                    w.addParticle(bits, p.x, p.y, p.z, v.x, v.y, v.z);
                }
            }
            for (int i = 0; i < 4; i++) {
                w.addParticle(ParticleTypes.SMOKE, p.x + r.nextGaussian() * 0.08, p.y + r.nextGaussian() * 0.08, p.z + r.nextGaussian() * 0.08,
                        n.x * 0.02, 0.03, n.z * 0.02);
            }
        }
        if (client.player != null && z.casterId == client.player.getId() && z.hit == ZapPayload.ENTITY) ScreenShake.kick(0.1f, 3);
    }

    // ---------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------

    private static void renderWorld(WorldRenderContext context) {
        if (ZAPS.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;
        float tickDelta = context.tickCounter().getTickDelta(false);
        Vec3d cam = context.camera().getPos();
        Vec3d fwd = Vec3d.fromPolar(context.camera().getPitch(), context.camera().getYaw());
        Vec3d camRight = fwd.crossProduct(new Vec3d(0, 1, 0));
        camRight = camRight.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : camRight.normalize();
        Vec3d camUp = camRight.crossProduct(fwd).normalize();

        MatrixStack ms = context.matrixStack() != null ? context.matrixStack() : new MatrixStack();
        Matrix4f pose = ms.peek().getPositionMatrix();
        VertexConsumerProvider.Immediate buffers = client.getBufferBuilders().getEntityVertexConsumers();
        VertexConsumer vc = buffers.getBuffer(LAYER);
        for (Zap z : ZAPS) draw(z, client.world, tickDelta, cam, camRight, camUp, pose, vc);
        buffers.draw(LAYER);
    }

    private static void draw(Zap z, ClientWorld world, float tickDelta, Vec3d cam, Vec3d camRight, Vec3d camUp,
                             Matrix4f pose, VertexConsumer vc) {
        float time = z.age + tickDelta;
        float head = Math.min(1f, time / z.travel);
        float fade = MathHelper.clamp((time - z.travel - HOLD) / FADE, 0f, 1f);
        float alpha = 1f - fade;
        if (alpha <= 0.01f) return;
        // Flickers like real lightning once it's landed
        if (time >= z.travel) alpha *= z.age % 2 == 0 ? 1f : 0.8f;
        float width = 1f - 0.45f * fade;
        double len = z.length();
        double headD = head * len;
        double tailD = fade * fade * len; // as it dies it pulls in towards the target

        Vec3d[] pts = crackled(anchored(z, world, tickDelta), Random.create(z.seed * 31L + z.age));
        // Thin, faint strands that weave round the bolt, a new tangle every tick
        Random strands = Random.create(z.seed * 17L + z.age * 104729L);
        for (int i = 0; i < pts.length - 1; i++) {
            double d0 = z.dist[i], d1 = z.dist[i + 1];
            if (d1 <= tailD || d0 >= headD || d1 - d0 < 1e-9) continue;
            Vec3d a = pts[i].lerp(pts[i + 1], MathHelper.clamp((tailD - d0) / (d1 - d0), 0, 1));
            Vec3d b = pts[i].lerp(pts[i + 1], MathHelper.clamp((headD - d0) / (d1 - d0), 0, 1));
            bolt(vc, pose, a.subtract(cam), b.subtract(cam), width, alpha);
            if (strands.nextFloat() < 0.55f) {
                Vec3d[] strand = twig(a, b, strands, 3, 0.06 + strands.nextDouble() * 0.06);
                for (int k = 0; k < strand.length - 1; k++) {
                    ribbon(vc, pose, strand[k].subtract(cam), strand[k + 1].subtract(cam), CORE_W * 0.6f * width, 1f, 0.95f, 0.6f, alpha * 0.45f);
                }
            }
        }

        // Little forks crackling off it, new ones every tick
        if (time > z.travel * 0.3f && fade < 0.6f) {
            Random r = Random.create(z.seed + z.age * 7919L);
            int forks = 1 + r.nextInt(2);
            for (int k = 0; k < forks && headD - tailD > 0.5; k++) {
                double d = tailD + r.nextDouble() * (headD - tailD);
                Vec3d base = pointAt(pts, z.dist, d);
                Vec3d dir = dirAt(pts, z.dist, d);
                Vec3d out = dir.add(perpendicular(dir).multiply(r.nextGaussian())).add(dir.crossProduct(perpendicular(dir)).multiply(r.nextGaussian())).normalize();
                Vec3d[] twig = twig(base, base.add(out.multiply(0.35 + r.nextDouble() * 0.55)), r, 3, 0.07);
                for (int i = 0; i < twig.length - 1; i++) bolt(vc, pose, twig[i].subtract(cam), twig[i + 1].subtract(cam), width * 0.55f, alpha * 0.75f);
            }
        }

        // A spark riding the tip on the way out
        if (head < 1f) {
            Vec3d tip = pointAt(pts, z.dist, headD);
            glow(vc, pose, tip.subtract(cam), camRight, camUp, 0.1f, 1f, 0.95f, 0.6f, 0.45f);
            sparks(vc, pose, tip, cam, Random.create(z.seed ^ (long) (time * 3)), 3, 0.12, 0.28, 0.8f, 0.8f);
        }

        // A star of sparks where it lands
        float since = time - z.travel;
        if (since >= 0 && since < BURST) {
            float k = 1f - since / BURST;
            glow(vc, pose, z.end.subtract(cam), camRight, camUp, 0.16f * k + 0.05f, 1f, 0.85f, 0.35f, 0.35f * k);
            sparks(vc, pose, z.end, cam, Random.create(z.seed + 31L * (int) since), 5, 0.25 * k, 0.6 * k, 0.9f, k);
        }
    }

    /** Short zig-zags flying out of {@code at} every which way. */
    private static void sparks(VertexConsumer vc, Matrix4f pose, Vec3d at, Vec3d cam, Random r, int count,
                               double minLen, double maxLen, float width, float alpha) {
        for (int k = 0; k < count; k++) {
            Vec3d dir = new Vec3d(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).normalize();
            Vec3d[] twig = twig(at, at.add(dir.multiply(minLen + r.nextDouble() * (maxLen - minLen))), r, 2, 0.05);
            for (int i = 0; i < twig.length - 1; i++) bolt(vc, pose, twig[i].subtract(cam), twig[i + 1].subtract(cam), width * 0.6f, alpha);
        }
    }

    /** One piece of bolt: core, sheath and glow, each a flat ribbon turned to face the camera. */
    private static void bolt(VertexConsumer vc, Matrix4f pose, Vec3d a, Vec3d b, float width, float alpha) {
        ribbon(vc, pose, a, b, GLOW_W * width, 1f, 0.75f, 0.2f, alpha * 0.18f);
        ribbon(vc, pose, a, b, MID_W * width, 1f, 0.9f, 0.4f, alpha * 0.55f);
        ribbon(vc, pose, a, b, CORE_W * width, 1f, 1f, 0.92f, alpha);
    }

    /** A strip from {@code a} to {@code b} (relative to the camera), a touch longer so the joints close up. */
    private static void ribbon(VertexConsumer vc, Matrix4f pose, Vec3d a, Vec3d b, float width,
                               float r, float g, float bl, float alpha) {
        Vec3d d = b.subtract(a);
        double l = d.length();
        if (l < 1e-5 || alpha <= 0.004f) return;
        Vec3d n = d.multiply(1.0 / l);
        Vec3d mid = a.add(b).multiply(0.5);
        // Thinner right up close, so it doesn't fill your view coming out of your own staff
        float w = width * (float) MathHelper.clamp(mid.length() / 2.5, 0.4, 1.0);
        Vec3d ext = n.multiply(w * 0.5);
        a = a.subtract(ext);
        b = b.add(ext);
        Vec3d side = n.crossProduct(mid);
        side = side.lengthSquared() < 1e-10 ? perpendicular(n) : side.normalize();
        side = side.multiply(w * 0.5);
        v(vc, pose, a.subtract(side), r, g, bl, alpha);
        v(vc, pose, b.subtract(side), r, g, bl, alpha);
        v(vc, pose, b.add(side), r, g, bl, alpha);
        v(vc, pose, a.add(side), r, g, bl, alpha);
    }

    /** A soft square of light facing the camera. */
    private static void glow(VertexConsumer vc, Matrix4f pose, Vec3d c, Vec3d right, Vec3d up, float size,
                             float r, float g, float b, float alpha) {
        Vec3d x = right.multiply(size), y = up.multiply(size);
        v(vc, pose, c.subtract(x).subtract(y), r, g, b, alpha);
        v(vc, pose, c.add(x).subtract(y), r, g, b, alpha);
        v(vc, pose, c.add(x).add(y), r, g, b, alpha);
        v(vc, pose, c.subtract(x).add(y), r, g, b, alpha);
        x = x.multiply(0.4);
        y = y.multiply(0.4);
        v(vc, pose, c.subtract(x).subtract(y), 1f, 1f, 0.95f, alpha);
        v(vc, pose, c.add(x).subtract(y), 1f, 1f, 0.95f, alpha);
        v(vc, pose, c.add(x).add(y), 1f, 1f, 0.95f, alpha);
        v(vc, pose, c.subtract(x).add(y), 1f, 1f, 0.95f, alpha);
    }

    private static void v(VertexConsumer vc, Matrix4f pose, Vec3d p, float r, float g, float b, float a) {
        vc.vertex(pose, (float) p.x, (float) p.y, (float) p.z).color(r, g, b, MathHelper.clamp(a, 0f, 1f));
    }
}
