package net.ragnar.ragnarsmagicmod.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.ragnar.ragnarsmagicmod.network.CloudPayload;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Draws the Tome of Clouds cloud under every player flying on one. It is rendered at the player's
 * interpolated position each frame, so it moves with them perfectly, and it is shaded and coloured
 * like the sky's own clouds (darker at night and in storms). The puffs breathe and drift, the cloud
 * banks into the direction of travel, and it leaves a wispy trail. The rider stands still on it.
 */
public final class CloudClient {
    private CloudClient() {}

    private static final class Cloud {
        int ticksLeft;
        boolean serverShown;
        float scale, prevScale;
        int age;
        // Bank (radians) toward the direction of travel, smoothed
        float pitch, prevPitch, roll, prevRoll;
    }

    private static final Map<Integer, Cloud> CLOUDS = new HashMap<>();

    private static final int GROW_TICKS = 5;
    // If the server stops reminding us (player left range, packet lost), let the cloud go
    private static final int STALE_TICKS = 30;
    // For the first moments of a cast you're launched off the cloud before flight kicks in
    private static final int LAUNCH_GRACE = 12;
    private static final float MAX_BANK = 0.12f;

    /**
     * The cloud's puffs, as boxes {minX, minY, minZ, sizeX, sizeY, sizeZ} relative to the player's
     * feet; the top of the main body sits just under the feet. Flat and lumpy, like a sky cloud.
     */
    private static final float[][] PUFFS = {
            {-1.10f, -0.45f, -1.10f, 2.20f, 0.42f, 2.20f}, // main body
            {-0.80f, -0.68f, -0.80f, 1.60f, 0.30f, 1.60f}, // rounded underside
            {-1.50f, -0.40f, -0.60f, 0.55f, 0.30f, 1.20f}, // side bulges
            { 0.95f, -0.38f, -0.45f, 0.55f, 0.28f, 1.00f},
            {-0.60f, -0.39f, -1.50f, 1.10f, 0.29f, 0.55f},
            {-0.35f, -0.41f,  0.95f, 1.00f, 0.31f, 0.55f},
            {-1.05f, -0.10f, -0.95f, 0.70f, 0.22f, 0.70f}, // lumps on top, around the feet
            { 0.40f, -0.10f,  0.30f, 0.60f, 0.17f, 0.70f},
            { 0.45f, -0.10f, -0.90f, 0.50f, 0.14f, 0.45f},
    };

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(CloudPayload.ID, (payload, context) -> {
            if (payload.ticksLeft() <= 0) {
                Cloud c = CLOUDS.get(payload.playerId());
                if (c != null) { c.ticksLeft = 0; c.serverShown = false; }
            } else {
                Cloud c = CLOUDS.computeIfAbsent(payload.playerId(), id -> new Cloud());
                if (payload.ticksLeft() > c.ticksLeft + 5) c.age = 0; // a fresh cast
                c.ticksLeft = payload.ticksLeft();
                c.serverShown = payload.shown();
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> CLOUDS.clear());
        ClientTickEvents.END_CLIENT_TICK.register(CloudClient::tick);
        WorldRenderEvents.AFTER_ENTITIES.register(CloudClient::render);
    }

    private static void tick(MinecraftClient client) {
        if (client.world == null) { CLOUDS.clear(); return; }
        if (client.isPaused()) return;

        Iterator<Map.Entry<Integer, Cloud>> it = CLOUDS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Cloud> e = it.next();
            Cloud c = e.getValue();
            Entity player = client.world.getEntityById(e.getKey());

            c.age++;
            c.ticksLeft--;
            boolean alive = player != null && c.ticksLeft > -STALE_TICKS;
            boolean active = alive && c.ticksLeft > 0;

            boolean show;
            if (!active || player.isOnGround()) {
                show = false;
            } else if (player == client.player) {
                // Our own flight state is known right away - no waiting on the server
                show = client.player.getAbilities().flying || (c.serverShown && c.age <= LAUNCH_GRACE);
            } else {
                show = c.serverShown;
            }

            c.prevScale = c.scale;
            c.scale = MathHelper.clamp(c.scale + (show ? 1f : -1f) / GROW_TICKS, 0f, 1f);

            if (player != null) {
                Vec3d vel = new Vec3d(player.getX() - player.prevX, player.getY() - player.prevY, player.getZ() - player.prevZ);

                // Dip the leading edge, leaning into the movement
                c.prevPitch = c.pitch;
                c.prevRoll = c.roll;
                float targetPitch = MathHelper.clamp((float) vel.z * 0.25f, -MAX_BANK, MAX_BANK);
                float targetRoll = MathHelper.clamp((float) -vel.x * 0.25f, -MAX_BANK, MAX_BANK);
                c.pitch += (targetPitch - c.pitch) * 0.15f;
                c.roll += (targetRoll - c.roll) * 0.15f;

                if (c.scale > 0.5f) {
                    spawnTrail(client.world, player, vel, c);
                    // Stand still on the cloud instead of running on the spot
                    if (player instanceof LivingEntity living) living.limbAnimator.setSpeed(0f);
                }
            }

            if (!active && c.scale <= 0f) it.remove();
        }
    }

    /** Wisps shed off the back of the cloud while moving, and the odd one drifting off when idle. */
    private static void spawnTrail(ClientWorld world, Entity player, Vec3d vel, Cloud c) {
        Random rand = world.getRandom();
        double speed = vel.horizontalLength();
        if (speed > 0.06) {
            Vec3d back = new Vec3d(-vel.x, 0, -vel.z).normalize();
            Vec3d side = new Vec3d(-back.z, 0, back.x);
            int count = speed > 0.5 ? 3 : speed > 0.2 ? 2 : 1;
            for (int i = 0; i < count; i++) {
                double spread = (rand.nextDouble() - 0.5) * 1.8;
                double x = player.getX() + back.x * (0.9 + rand.nextDouble() * 0.4) + side.x * spread;
                double y = player.getY() - 0.35 + (rand.nextDouble() - 0.5) * 0.35;
                double z = player.getZ() + back.z * (0.9 + rand.nextDouble() * 0.4) + side.z * spread;
                world.addParticle(ParticleTypes.CLOUD, x, y, z,
                        back.x * 0.03 + (rand.nextDouble() - 0.5) * 0.02,
                        (rand.nextDouble() - 0.5) * 0.01,
                        back.z * 0.03 + (rand.nextDouble() - 0.5) * 0.02);
            }
        } else if (rand.nextInt(8) == 0) {
            double a = rand.nextDouble() * Math.PI * 2;
            world.addParticle(ParticleTypes.CLOUD,
                    player.getX() + Math.cos(a) * 1.3, player.getY() - 0.4, player.getZ() + Math.sin(a) * 1.3,
                    Math.cos(a) * 0.01, -0.005, Math.sin(a) * 0.01);
        }
    }

    private static void render(WorldRenderContext context) {
        if (CLOUDS.isEmpty() || context.world() == null) return;

        float tickDelta = context.tickCounter().getTickDelta(false);
        Vec3d cam = context.camera().getPos();
        MatrixStack matrices = context.matrixStack() != null ? context.matrixStack() : new MatrixStack();
        VertexConsumerProvider.Immediate buffers = MinecraftClient.getInstance().getBufferBuilders().getEntityVertexConsumers();
        VertexConsumer vc = buffers.getBuffer(RenderLayer.getDebugQuads());

        // Same colour the sky's clouds are using right now
        Vec3d sky = context.world().getCloudsColor(tickDelta);

        boolean drew = false;
        for (Map.Entry<Integer, Cloud> e : CLOUDS.entrySet()) {
            Cloud c = e.getValue();
            float scale = MathHelper.lerp(tickDelta, c.prevScale, c.scale);
            if (scale <= 0.001f) continue;
            Entity player = context.world().getEntityById(e.getKey());
            if (player == null) continue;

            double x = MathHelper.lerp(tickDelta, player.prevX, player.getX());
            double y = MathHelper.lerp(tickDelta, player.prevY, player.getY());
            double z = MathHelper.lerp(tickDelta, player.prevZ, player.getZ());
            float time = c.age + tickDelta;

            // Ease in with a little overshoot so it "puffs" into existence, then bob gently
            float eased = scale >= 1f ? 1f : backOut(scale);
            float bob = MathHelper.sin(time * 0.12f) * 0.03f;

            matrices.push();
            matrices.translate(x - cam.x, y - cam.y + bob, z - cam.z);
            matrices.multiply(new org.joml.Quaternionf()
                    .rotateX(MathHelper.lerp(tickDelta, c.prevPitch, c.pitch))
                    .rotateZ(MathHelper.lerp(tickDelta, c.prevRoll, c.roll)));
            matrices.scale(eased, eased, eased);
            Matrix4f m = matrices.peek().getPositionMatrix();
            for (int i = 0; i < PUFFS.length; i++) {
                float[] p = PUFFS[i];
                // Each puff breathes and drifts on its own rhythm; the main body stays steadier
                float amount = i == 0 ? 0.4f : 1f;
                float breathe = 1f + MathHelper.sin(time * 0.09f + i * 1.7f) * 0.06f * amount;
                float dx = MathHelper.sin(time * 0.05f + i * 2.3f) * 0.04f * amount;
                float dy = MathHelper.sin(time * 0.11f + i * 1.1f) * 0.025f * amount;
                float dz = MathHelper.cos(time * 0.06f + i * 0.9f) * 0.04f * amount;
                float hx = p[3] * breathe / 2, hy = p[4] * breathe / 2, hz = p[5] * breathe / 2;
                float cx = p[0] + p[3] / 2 + dx, cy = p[1] + p[4] / 2 + dy, cz = p[2] + p[5] / 2 + dz;
                box(vc, m, cx - hx, cy - hy, cz - hz, cx + hx, cy + hy, cz + hz, sky);
            }
            matrices.pop();
            drew = true;
        }
        if (drew) buffers.draw(RenderLayer.getDebugQuads());
    }

    private static float backOut(float t) {
        float s = 1.7f;
        float u = t - 1f;
        return 1f + u * u * ((s + 1f) * u + s);
    }

    /** A cube shaded the way vanilla shades its sky clouds: bright top, darker sides, darkest bottom. */
    private static void box(VertexConsumer vc, Matrix4f m, float x0, float y0, float z0, float x1, float y1, float z1, Vec3d sky) {
        float r = (float) sky.x, g = (float) sky.y, b = (float) sky.z;
        // top
        quad(vc, m, r, g, b, 1.0f, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
        // bottom
        quad(vc, m, r, g, b, 0.7f, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        // east / west
        quad(vc, m, r, g, b, 0.9f, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
        quad(vc, m, r, g, b, 0.9f, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
        // north / south
        quad(vc, m, r, g, b, 0.8f, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
        quad(vc, m, r, g, b, 0.8f, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
    }

    private static void quad(VertexConsumer vc, Matrix4f m, float r, float g, float b, float shade,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz) {
        float sr = r * shade, sg = g * shade, sb = b * shade;
        vc.vertex(m, ax, ay, az).color(sr, sg, sb, 1f);
        vc.vertex(m, bx, by, bz).color(sr, sg, sb, 1f);
        vc.vertex(m, cx, cy, cz).color(sr, sg, sb, 1f);
        vc.vertex(m, dx, dy, dz).color(sr, sg, sb, 1f);
    }
}
