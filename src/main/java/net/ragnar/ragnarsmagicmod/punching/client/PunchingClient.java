package net.ragnar.ragnarsmagicmod.punching.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.Arm;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.punching.Punching;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client half of the Tome of Punching (see Punching):
 * <ul>
 *   <li>the caster's body: through the dash the punching arm is cocked back with the other up on guard and the body
 *       turned away; on impact the arm snaps straight out and the body twists into the blow, held a moment, then eases
 *       back;</li>
 *   <li>the impact: a white flash at the fist, rings of shocked air racing out along the punch like a sonic cone, and a
 *       ring of force tearing across the ground.</li>
 * </ul>
 */
public final class PunchingClient {
    private PunchingClient() {}

    /** Ticks the arm stays thrown out after the hit, then the ticks it takes to ease back. */
    private static final float HOLD = 6f, EASE = 7f;
    private static final int IMPACT_TICKS = 16;

    private static final RenderLayer GLOW = RenderLayer.of("ragnarsmagicmod_punch",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 16384, false, true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.LIGHTNING_TRANSPARENCY)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    /** A caster's punch, timed in world ticks: when the dash began, and when the fist landed (-1 until it does). */
    private static final class Punch {
        long dashAt, hitAt = -1;
    }

    private record Impact(Vec3d at, Vec3d dir, double groundY, boolean direct, long time) {}

    private static final Map<Integer, Punch> PUNCHES = new HashMap<>();
    private static final List<Impact> IMPACTS = new ArrayList<>();

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(Punching.DashPayload.ID, (payload, context) -> {
            World w = context.client().world;
            if (w == null) return;
            Punch p = new Punch();
            p.dashAt = w.getTime();
            PUNCHES.put(payload.casterId(), p);
        });
        ClientPlayNetworking.registerGlobalReceiver(Punching.ImpactPayload.ID, (payload, context) -> {
            World w = context.client().world;
            if (w == null) return;
            Punch p = PUNCHES.computeIfAbsent(payload.casterId(), id -> {
                Punch n = new Punch();
                n.dashAt = w.getTime();
                return n;
            });
            p.hitAt = w.getTime();
            Vec3d at = new Vec3d(payload.x(), payload.y(), payload.z());
            IMPACTS.add(new Impact(at, new Vec3d(payload.dirX(), 0, payload.dirZ()), groundY(w, at), payload.direct(), w.getTime()));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            PUNCHES.clear();
            IMPACTS.clear();
        });
        ClientTickEvents.END_CLIENT_TICK.register(PunchingClient::tick);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(PunchingClient::render);
    }

    private static double groundY(World w, Vec3d at) {
        BlockPos.Mutable p = BlockPos.ofFloored(at).mutableCopy();
        for (int i = 0; i < 5; i++, p.move(0, -1, 0)) {
            if (!w.getBlockState(p).getCollisionShape(w, p).isEmpty()) return p.getY() + 1.0;
        }
        return at.y - 1.2;
    }

    private static void tick(MinecraftClient client) {
        if (client.world == null) return;
        long now = client.world.getTime();
        PUNCHES.values().removeIf(p -> p.hitAt >= 0 ? now - p.hitAt > HOLD + EASE + 2 : now - p.dashAt > 40);
        IMPACTS.removeIf(i -> now - i.time > IMPACT_TICKS);
        // Streaks of air trailing a dashing caster
        for (Map.Entry<Integer, Punch> e : PUNCHES.entrySet()) {
            if (e.getValue().hitAt >= 0) continue;
            Entity caster = client.world.getEntityById(e.getKey());
            if (caster == null) continue;
            Vec3d v = caster.getVelocity();
            for (int i = 0; i < 3; i++) {
                client.world.addParticle(ParticleTypes.WHITE_SMOKE, caster.getX() + (client.world.random.nextDouble() - 0.5) * 0.8,
                        caster.getY() + 0.2 + client.world.random.nextDouble() * 1.5, caster.getZ() + (client.world.random.nextDouble() - 0.5) * 0.8,
                        -v.x * 0.15, 0, -v.z * 0.15);
            }
        }
    }

    // ---------------------------------------------------------------------
    // The caster's body
    // ---------------------------------------------------------------------

    /** Poses a caster mid-punch. Called after the model has worked out its usual pose (see MightyPushPoseMixin). */
    public static void pose(LivingEntity entity, BipedEntityModel<?> model, float tickDelta) {
        Punch p = PUNCHES.get(entity.getId());
        if (p == null) return;
        float now = entity.getWorld().getTime() + tickDelta;
        boolean right = entity.getMainArm() == Arm.RIGHT;
        ModelPart fist = right ? model.rightArm : model.leftArm;
        ModelPart guard = right ? model.leftArm : model.rightArm;
        float side = right ? 1f : -1f;

        float twist, fistPitch, fistYaw, guardPitch, guardYaw, blend;
        if (p.hitAt < 0) {
            // Winding up: fist drawn back by the hip, the other hand up on guard, shoulders turned away
            float t = MathHelper.clamp((now - p.dashAt) / 3f, 0f, 1f);
            blend = t * t * (3f - 2f * t);
            twist = 0.55f * side;
            fistPitch = 0.75f;
            fistYaw = 0.2f * side;
            guardPitch = -1.35f;
            guardYaw = -0.45f * side;
        } else {
            // The blow: arm dead straight out, shoulders driven round into it, the guard hand pulled back
            float since = now - p.hitAt;
            float snap = MathHelper.clamp(since / 1.5f, 0f, 1f);
            blend = since < HOLD ? 1f : MathHelper.clamp(1f - (since - HOLD) / EASE, 0f, 1f);
            blend = blend * blend * (3f - 2f * blend);
            twist = MathHelper.lerp(snap, 0.55f, -0.45f) * side;
            fistPitch = MathHelper.lerp(snap, 0.75f, -MathHelper.HALF_PI - 0.1f);
            fistYaw = MathHelper.lerp(snap, 0.2f, -0.12f) * side;
            guardPitch = MathHelper.lerp(snap, -1.35f, 0.5f);
            guardYaw = 0f;
        }
        if (blend <= 0f) return;
        model.body.yaw = MathHelper.lerp(blend, model.body.yaw, twist);
        // The arms hang off the turned shoulders, as in vanilla's own swing
        float by = model.body.yaw;
        model.rightArm.pivotZ = MathHelper.sin(by) * 5f;
        model.rightArm.pivotX = -MathHelper.cos(by) * 5f;
        model.leftArm.pivotZ = -MathHelper.sin(by) * 5f;
        model.leftArm.pivotX = MathHelper.cos(by) * 5f;
        fist.pitch = MathHelper.lerp(blend, fist.pitch, fistPitch);
        fist.yaw = MathHelper.lerp(blend, fist.yaw, fistYaw + by);
        fist.roll = MathHelper.lerp(blend, fist.roll, 0f);
        guard.pitch = MathHelper.lerp(blend, guard.pitch, guardPitch);
        guard.yaw = MathHelper.lerp(blend, guard.yaw, guardYaw + by);
    }

    // ---------------------------------------------------------------------
    // The shockwave
    // ---------------------------------------------------------------------

    private static Matrix4f pose = new Matrix4f();
    private static Vec3d cam = Vec3d.ZERO;

    private static void render(WorldRenderContext context) {
        if (IMPACTS.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;
        float now = client.world.getTime() + context.tickCounter().getTickDelta(false);
        cam = context.camera().getPos();
        MatrixStack ms = context.matrixStack() != null ? context.matrixStack() : new MatrixStack();
        pose = ms.peek().getPositionMatrix();
        VertexConsumerProvider.Immediate buffers = client.getBufferBuilders().getEntityVertexConsumers();
        VertexConsumer vc = buffers.getBuffer(GLOW);
        Vec3d camFwd = Vec3d.fromPolar(context.camera().getPitch(), context.camera().getYaw());

        for (Impact i : IMPACTS) {
            float t = now - i.time;
            if (t < 0) continue;
            float strength = i.direct ? 1f : 0.75f;
            // Rings of shocked air racing out along the punch, each further and wider than the last: a cone of force
            for (int k = 0; k < 3; k++) {
                float lt = t - k * 1.2f;
                if (lt <= 0) continue;
                float p = MathHelper.clamp(lt / 9f, 0f, 1f);
                float fade = (1f - p) * (1f - p) * strength;
                Vec3d c = i.at.add(i.dir.multiply(0.4 + k * 0.9 + p * (1.5 + k * 1.2)));
                float radius = 0.35f + p * (1.6f + k * 0.9f);
                ring(vc, c, i.dir, radius, 0.28f + 0.2f * p, 1f, 0.97f, 0.9f, 0.75f * fade);
            }
            // Force tearing out across the ground
            float gp = MathHelper.clamp(t / 12f, 0f, 1f);
            float gfade = (1f - gp) * strength;
            ring(vc, new Vec3d(i.at.x, i.groundY + 0.05, i.at.z), new Vec3d(0, 1, 0), 0.5f + gp * (float) Punching.AREA_RADIUS, 0.7f, 1f, 0.95f, 0.85f, 0.55f * gfade);
            ring(vc, new Vec3d(i.at.x, i.groundY + 0.06, i.at.z), new Vec3d(0, 1, 0), 0.3f + gp * (float) Punching.AREA_RADIUS * 0.6f, 0.4f, 1f, 0.8f, 0.5f, 0.35f * gfade);
            // The flash where the fist landed, facing the camera
            float f = MathHelper.clamp(1f - t / 4f, 0f, 1f);
            if (f > 0) {
                ring(vc, i.at, camFwd, 0.01f + 0.9f * (1f - f), 1.1f, 1f, 1f, 1f, f);
                ring(vc, i.at, camFwd, 0.6f + 1.8f * (1f - f), 0.9f, 1f, 0.85f, 0.6f, 0.6f * f);
            }
        }
        buffers.draw(GLOW);
    }

    /** A glowing band round {@code c} in the plane facing {@code normal}: bright at {@code radius}, soft on both sides. */
    private static void ring(VertexConsumer vc, Vec3d c, Vec3d normal, float radius, float width, float r, float g, float b, float alpha) {
        if (alpha <= 0.003f) return;
        Vec3d n = normal.normalize();
        Vec3d u = Math.abs(n.y) > 0.9 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
        u = u.subtract(n.multiply(u.dotProduct(n))).normalize();
        Vec3d w = n.crossProduct(u).normalize();
        int segs = 40;
        float in = Math.max(0f, radius - width), out = radius + width * 0.5f;
        for (int s = 0; s < segs; s++) {
            double a0 = s * Math.PI * 2 / segs, a1 = (s + 1) * Math.PI * 2 / segs;
            Vec3d d0 = u.multiply(Math.cos(a0)).add(w.multiply(Math.sin(a0)));
            Vec3d d1 = u.multiply(Math.cos(a1)).add(w.multiply(Math.sin(a1)));
            v(vc, c.add(d0.multiply(in)), r, g, b, in <= 0.001f ? alpha : 0f);
            v(vc, c.add(d1.multiply(in)), r, g, b, in <= 0.001f ? alpha : 0f);
            v(vc, c.add(d1.multiply(radius)), r, g, b, alpha);
            v(vc, c.add(d0.multiply(radius)), r, g, b, alpha);
            v(vc, c.add(d0.multiply(radius)), r, g, b, alpha);
            v(vc, c.add(d1.multiply(radius)), r, g, b, alpha);
            v(vc, c.add(d1.multiply(out)), r, g, b, 0f);
            v(vc, c.add(d0.multiply(out)), r, g, b, 0f);
        }
    }

    private static void v(VertexConsumer vc, Vec3d p, float r, float g, float b, float a) {
        vc.vertex(pose, (float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z)).color(r, g, b, MathHelper.clamp(a, 0f, 1f));
    }
}
