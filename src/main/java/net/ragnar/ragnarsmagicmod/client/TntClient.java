package net.ragnar.ragnarsmagicmod.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.*;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.network.TntPayloads;
import net.ragnar.ragnarsmagicmod.util.TntArc;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/**
 * Aiming the Tome of TNT: a marching dotted arc showing the throw, and the block where it will land outlined
 * in orange. The scroll wheel throws nearer or further.
 */
public final class TntClient {
    private TntClient() {}

    private static final RenderLayer MARKER = RenderLayer.of("ragnarsmagicmod_tnt_marker",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 4096, false, true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.TRANSLUCENT_TRANSPARENCY)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    private static boolean aiming;
    private static float power = TntArc.DEFAULT_POWER;
    private static double scrollAccumulator;

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(TntPayloads.Aiming.ID, (payload, context) -> {
            aiming = payload.active();
            if (aiming) power = payload.power();
            scrollAccumulator = 0;
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> aiming = false);
        WorldRenderEvents.AFTER_ENTITIES.register(TntClient::render);
    }

    /** Called from the mouse scroll hook. Returns true when the wheel was used to aim. */
    public static boolean onScroll(double vertical) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!aiming || client.player == null || client.currentScreen != null || client.getOverlay() != null) return false;
        scrollAccumulator += vertical;
        int steps = (int) scrollAccumulator;
        scrollAccumulator -= steps;
        if (steps != 0) {
            float next = TntArc.clampPower(power + steps * TntArc.POWER_STEP);
            if (next != power) {
                power = next;
                ClientPlayNetworking.send(new TntPayloads.Power(power));
                float pitch = 0.6f + (power - TntArc.MIN_POWER) / (TntArc.MAX_POWER - TntArc.MIN_POWER);
                client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK.value(), pitch, 0.25f));
            }
        }
        return true;
    }

    private static void render(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (!aiming || player == null || context.world() == null) return;

        float tickDelta = context.tickCounter().getTickDelta(false);
        TntArc.Flight flight = TntArc.simulate(context.world(), player, power);
        Vec3d cam = context.camera().getPos();
        Quaternionf camRot = context.camera().getRotation();
        Vector3f right = camRot.transform(new Vector3f(1, 0, 0));
        Vector3f up = camRot.transform(new Vector3f(0, 1, 0));

        MatrixStack matrices = context.matrixStack() != null ? context.matrixStack() : new MatrixStack();
        matrices.push();
        Matrix4f m = matrices.peek().getPositionMatrix();
        VertexConsumerProvider.Immediate buffers = client.getBufferBuilders().getEntityVertexConsumers();
        VertexConsumer vc = buffers.getBuffer(MARKER);

        // The arc: dots that march along the path toward the landing spot
        float time = player.age + tickDelta;
        List<Vec3d> points = flight.points();
        float march = (time * 0.35f) % 1f;
        for (int i = 1; i < points.size() - 1; i++) {
            Vec3d a = points.get(i), b = points.get(i + 1);
            Vec3d p = a.lerp(b, march).subtract(cam);
            if (p.lengthSquared() < 1.0) continue; // don't draw right in your face
            float along = i / (float) points.size();
            float size = 0.07f + along * 0.03f;
            float r = 1f, g = MathHelper.lerp(along, 0.75f, 0.25f), bl = MathHelper.lerp(along, 0.25f, 0.1f);
            billboard(vc, m, p, right, up, size, r, g, bl, 0.9f);
        }

        // Where it lands: the block it'll come to rest in, glowing faintly with a crisp outline
        Box block = landingBlock(flight);
        if (block != null) {
            float pulse = 0.5f + 0.5f * MathHelper.sin(time * 0.3f);
            cube(vc, m, block.offset(-cam.x, -cam.y, -cam.z), 1f, 0.55f, 0.15f, 0.12f + 0.1f * pulse);
        }
        buffers.draw(MARKER);

        if (block != null) {
            float pulse = 0.5f + 0.5f * MathHelper.sin(time * 0.3f);
            WorldRenderer.drawBox(matrices, buffers.getBuffer(RenderLayer.getLines()),
                    block.offset(-cam.x, -cam.y, -cam.z).expand(0.002), 1f, 0.6f, 0.15f, 0.7f + 0.3f * pulse);
            buffers.draw(RenderLayer.getLines());
        }
        matrices.pop();
    }

    private static void billboard(VertexConsumer vc, Matrix4f m, Vec3d p, Vector3f right, Vector3f up, float size,
                                  float r, float g, float b, float a) {
        float x = (float) p.x, y = (float) p.y, z = (float) p.z;
        float rx = right.x * size, ry = right.y * size, rz = right.z * size;
        float ux = up.x * size, uy = up.y * size, uz = up.z * size;
        vc.vertex(m, x - rx - ux, y - ry - uy, z - rz - uz).color(r, g, b, a);
        vc.vertex(m, x + rx - ux, y + ry - uy, z + rz - uz).color(r, g, b, a);
        vc.vertex(m, x + rx + ux, y + ry + uy, z + rz + uz).color(r, g, b, a);
        vc.vertex(m, x - rx + ux, y - ry + uy, z - rz + uz).color(r, g, b, a);
    }

    /** The block space the TNT settles in: the air just in front of whatever it hits. */
    private static Box landingBlock(TntArc.Flight flight) {
        if (flight.landing() == null || flight.points().size() < 2) return null;
        List<Vec3d> points = flight.points();
        Vec3d land = flight.landing();
        Vec3d before = points.get(points.size() - 2);
        Vec3d back = before.subtract(land);
        Vec3d inAir = back.lengthSquared() < 1.0e-6 ? land.add(0, 0.05, 0) : land.add(back.normalize().multiply(0.05));
        return new Box(BlockPos.ofFloored(inAir));
    }

    /** The six faces of a box. */
    private static void cube(VertexConsumer vc, Matrix4f m, Box b, float r, float g, float bl, float a) {
        float x0 = (float) b.minX, y0 = (float) b.minY, z0 = (float) b.minZ;
        float x1 = (float) b.maxX, y1 = (float) b.maxY, z1 = (float) b.maxZ;
        face(vc, m, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, r, g, bl, a);
        face(vc, m, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, r, g, bl, a);
        face(vc, m, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, r, g, bl, a);
        face(vc, m, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, r, g, bl, a);
        face(vc, m, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, r, g, bl, a);
        face(vc, m, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, r, g, bl, a);
    }

    private static void face(VertexConsumer vc, Matrix4f m, float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz, float r, float g, float b, float a) {
        vc.vertex(m, ax, ay, az).color(r, g, b, a);
        vc.vertex(m, bx, by, bz).color(r, g, b, a);
        vc.vertex(m, cx, cy, cz).color(r, g, b, a);
        vc.vertex(m, dx, dy, dz).color(r, g, b, a);
    }
}
