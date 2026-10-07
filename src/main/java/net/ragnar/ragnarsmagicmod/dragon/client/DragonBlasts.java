package net.ragnar.ragnarsmagicmod.dragon.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
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
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.ragnar.ragnarsmagicmod.dragon.Dragon;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * The Tome of the Dragon's blast, drawn on each client: a swelling magenta flash with a white-hot heart and two
 * shockwave rings racing out, then a purple haze that hangs in the air for a few seconds, full of drifting dragon's
 * breath, ender sparks rising out of it, and end-rod motes swirling slowly round where it went off - like a breath of
 * the End left behind.
 */
public final class DragonBlasts {
    private DragonBlasts() {}

    private static final int FLASH_TICKS = 14;
    private static final int LINGER_TICKS = 80; // 4 seconds

    private static final DustParticleEffect PURPLE = new DustParticleEffect(new Vector3f(0.65f, 0.15f, 0.95f), 2.5f);
    private static final DustParticleEffect VIOLET = new DustParticleEffect(new Vector3f(0.35f, 0.05f, 0.55f), 1.8f);

    private static final RenderLayer GLOW = RenderLayer.of("ragnarsmagicmod_dragon_blast",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 16384, false, true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.LIGHTNING_TRANSPARENCY)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    /** See-through colour: tints what's behind it purple, even against a bright sky (additive light would wash out). */
    private static final RenderLayer TINT = RenderLayer.of("ragnarsmagicmod_dragon_blast_tint",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 16384, false, true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.TRANSLUCENT_TRANSPARENCY)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    private record Blast(Vec3d at, float radius, int[] age) {}

    private static final List<Blast> BLASTS = new ArrayList<>();

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(Dragon.BlastPayload.ID, (payload, context) -> {
            if (BLASTS.size() >= 6) BLASTS.remove(0);
            Blast b = new Blast(new Vec3d(payload.at()), payload.radius(), new int[1]);
            BLASTS.add(b);
            if (context.client().world != null) burst(context.client().world, b);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> BLASTS.clear());
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.isPaused() || client.world == null) return;
            for (Blast b : BLASTS) linger(client.world, b);
            BLASTS.removeIf(b -> ++b.age()[0] > LINGER_TICKS);
        });
        WorldRenderEvents.AFTER_TRANSLUCENT.register(DragonBlasts::render);
    }

    // ---------------------------------------------------------------------
    // Particles
    // ---------------------------------------------------------------------

    /** The moment it goes off: everything flung out at once. */
    private static void burst(ClientWorld world, Blast b) {
        Random r = world.random;
        Vec3d c = b.at();
        double reach = b.radius();
        for (int i = 0; i < 160; i++) {
            // Slow: dragon's breath coasts a long way, and this should billow round the blast, not into your face
            Vec3d d = dir(r).multiply((0.03 + r.nextDouble() * 0.1) * reach / 6);
            world.addParticle(ParticleTypes.DRAGON_BREATH, c.x, c.y, c.z, d.x, d.y, d.z);
        }
        for (int i = 0; i < 70; i++) {
            Vec3d d = dir(r).multiply(0.15 + r.nextDouble() * 0.25);
            world.addParticle(ParticleTypes.END_ROD, c.x, c.y, c.z, d.x, d.y, d.z);
        }
        for (int i = 0; i < 90; i++) {
            Vec3d p = c.add(dir(r).multiply(r.nextDouble() * reach * 0.6));
            world.addParticle(i % 2 == 0 ? PURPLE : VIOLET, p.x, p.y, p.z, 0, 0, 0);
        }
        for (int i = 0; i < 120; i++) {
            // Portal sparks streak in from all round to where it went off
            Vec3d d = dir(r).multiply(reach * (0.5 + r.nextDouble() * 0.6));
            world.addParticle(ParticleTypes.PORTAL, c.x, c.y, c.z, d.x, d.y, d.z);
        }
        for (int i = 0; i < 40; i++) {
            Vec3d d = dir(r).multiply(0.2 + r.nextDouble() * 0.3);
            world.addParticle(ParticleTypes.REVERSE_PORTAL, c.x, c.y, c.z, d.x, d.y, d.z);
        }
    }

    /** The haze it leaves behind, thinning out as it goes. */
    private static void linger(ClientWorld world, Blast b) {
        Random r = world.random;
        int age = b.age()[0];
        float left = 1f - age / (float) LINGER_TICKS;
        Vec3d c = b.at();
        double reach = b.radius() * 0.75;
        int n = Math.round(9 * left);
        for (int i = 0; i < n; i++) {
            Vec3d p = c.add(dir(r).multiply(Math.cbrt(r.nextDouble()) * reach));
            world.addParticle(ParticleTypes.DRAGON_BREATH, p.x, p.y, p.z, r.nextGaussian() * 0.005, 0.01 + r.nextDouble() * 0.02, r.nextGaussian() * 0.005);
        }
        if (r.nextFloat() < left) {
            Vec3d p = c.add(dir(r).multiply(r.nextDouble() * reach));
            world.addParticle(r.nextBoolean() ? PURPLE : VIOLET, p.x, p.y, p.z, 0, 0, 0);
        }
        // Ender sparks rising out of it
        for (int i = 0; i < Math.round(4 * left); i++) {
            Vec3d p = c.add(dir(r).multiply(r.nextDouble() * reach));
            world.addParticle(ParticleTypes.REVERSE_PORTAL, p.x, p.y, p.z, 0, 0.04 + r.nextDouble() * 0.04, 0);
        }
        // End-rod motes circling slowly round the middle
        if (age % 2 == 0 && left > 0.15f) {
            double a = age * 0.35 + r.nextDouble() * 0.5;
            double rad = reach * (0.5 + 0.4 * r.nextDouble());
            world.addParticle(ParticleTypes.END_ROD, c.x + Math.cos(a) * rad, c.y + r.nextGaussian() * 0.6, c.z + Math.sin(a) * rad,
                    -Math.sin(a) * 0.08, 0.01, Math.cos(a) * 0.08);
        }
    }

    private static Vec3d dir(Random r) {
        Vec3d d = new Vec3d(r.nextGaussian(), r.nextGaussian(), r.nextGaussian());
        return d.lengthSquared() < 1e-6 ? new Vec3d(0, 1, 0) : d.normalize();
    }

    // ---------------------------------------------------------------------
    // Light
    // ---------------------------------------------------------------------

    private static void render(WorldRenderContext context) {
        if (BLASTS.isEmpty()) return;
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
        // Two passes: purple tint first, then the additive white-hot light on top
        for (int pass = 0; pass < 2; pass++) {
            RenderLayer layer = pass == 0 ? TINT : GLOW;
            VertexConsumer vc = buffers.getBuffer(layer);
            for (Blast b : BLASTS) {
                float age = b.age()[0] + tickDelta;
                Vec3d c = b.at().subtract(cam);
                float rad = b.radius();
                float t = MathHelper.clamp(age / FLASH_TICKS, 0f, 1f);
                float grow = 1f - (1f - t) * (1f - t) * (1f - t);
                float fade = (1f - t) * (1f - t);
                float left = 1f - MathHelper.clamp(age / LINGER_TICKS, 0f, 1f);
                float pulse = 0.85f + 0.15f * MathHelper.sin(age * 0.3f);
                if (pass == 0) {
                    if (t < 1f) {
                        // The flash swelling out, and shockwave rings racing away: one across the ground, one tipped up
                        glow(vc, pose, c, right, up, rad * (0.6f + 1.0f * grow), 0.55f, 0.1f, 0.85f, 0.75f * fade);
                        ring(vc, pose, c, new Vec3d(1, 0, 0), new Vec3d(0, 0, 1), rad * 1.7f * grow, 0.9f, 0.6f, 0.15f, 0.95f, 0.8f * fade);
                        ring(vc, pose, c, new Vec3d(1, 0, 0), new Vec3d(0, 0.7, 0.7), rad * 1.3f * grow, 0.6f, 0.75f, 0.3f, 1f, 0.6f * fade);
                    }
                    // The haze: a purple glow that hangs about, pulsing slowly as it fades
                    glow(vc, pose, c, right, up, rad * 0.9f * pulse, 0.4f, 0.05f, 0.65f, 0.45f * left * left);
                } else {
                    if (t < 1f) {
                        glow(vc, pose, c, right, up, rad * 0.55f * (0.4f + 0.6f * grow), 1f, 0.5f, 1f, 0.9f * fade);
                        glow(vc, pose, c, right, up, rad * 0.25f, 1f, 0.95f, 1f, fade);
                    }
                    glow(vc, pose, c, right, up, rad * 0.3f * pulse, 0.6f, 0.2f, 0.9f, 0.35f * left);
                }
            }
            buffers.draw(layer);
        }
    }

    /** A soft round glow facing the camera: brightest in the middle, fading to nothing at the edge. */
    private static void glow(VertexConsumer vc, Matrix4f pose, Vec3d c, Vec3d right, Vec3d up, float size,
                             float r, float g, float b, float alpha) {
        if (alpha <= 0.003f || size <= 0.01f) return;
        int n = 32;
        for (int i = 0; i < n; i++) {
            double t0 = i * Math.PI * 2 / n, t1 = (i + 1) * Math.PI * 2 / n;
            Vec3d e0 = c.add(right.multiply(Math.cos(t0) * size)).add(up.multiply(Math.sin(t0) * size));
            Vec3d e1 = c.add(right.multiply(Math.cos(t1) * size)).add(up.multiply(Math.sin(t1) * size));
            Vec3d m0 = c.add(e0.subtract(c).multiply(0.35)), m1 = c.add(e1.subtract(c).multiply(0.35));
            // Inner part: full brightness out to a third of the way, then a long fade to the rim
            v(vc, pose, c, r, g, b, alpha);
            v(vc, pose, c, r, g, b, alpha);
            v(vc, pose, m1, r, g, b, alpha * 0.55f);
            v(vc, pose, m0, r, g, b, alpha * 0.55f);
            v(vc, pose, m0, r, g, b, alpha * 0.55f);
            v(vc, pose, m1, r, g, b, alpha * 0.55f);
            v(vc, pose, e1, r, g, b, 0f);
            v(vc, pose, e0, r, g, b, 0f);
        }
    }

    /** A thin glowing band of radius {@code radius} in the plane of {@code a} and {@code b}. */
    private static void ring(VertexConsumer vc, Matrix4f pose, Vec3d c, Vec3d a, Vec3d b, float radius, float width,
                             float r, float g, float bl, float alpha) {
        if (alpha <= 0.003f || radius <= 0.01f) return;
        a = a.normalize();
        b = b.normalize();
        int n = 48;
        float inner = Math.max(0f, radius - width);
        for (int i = 0; i < n; i++) {
            double t0 = i * Math.PI * 2 / n, t1 = (i + 1) * Math.PI * 2 / n;
            Vec3d d0 = a.multiply(Math.cos(t0)).add(b.multiply(Math.sin(t0)));
            Vec3d d1 = a.multiply(Math.cos(t1)).add(b.multiply(Math.sin(t1)));
            v(vc, pose, c.add(d0.multiply(inner)), r, g, bl, 0f);
            v(vc, pose, c.add(d1.multiply(inner)), r, g, bl, 0f);
            v(vc, pose, c.add(d1.multiply(radius)), r, g, bl, alpha);
            v(vc, pose, c.add(d0.multiply(radius)), r, g, bl, alpha);
        }
    }

    private static void v(VertexConsumer vc, Matrix4f pose, Vec3d p, float r, float g, float b, float a) {
        vc.vertex(pose, (float) p.x, (float) p.y, (float) p.z).color(r, g, b, MathHelper.clamp(a, 0f, 1f));
    }
}
