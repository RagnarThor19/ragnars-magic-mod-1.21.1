package net.ragnar.ragnarsmagicmod.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.ragnar.ragnarsmagicmod.item.spell.RecallingSpell;
import org.joml.Matrix4f;

/**
 * The Tome of Recalling's mark, as its owner sees it (nobody else does): a tiny lodestone - the block that remembers a
 * place - hovering over the spot, slowly turning and bobbing, with enchanting glyphs drifting in to it the way they
 * fly to an enchanting table's book, a faint ring of runes turning on the ground beneath and a thread of light
 * between them. Now and then an ender wisp rises from it, and close up there's the odd soft chime.
 * <p>
 * All of it is drawn here, on the client: the server only says where the mark is when it changes.
 */
public final class RecallMarkerClient {
    private RecallMarkerClient() {}

    private static final RenderLayer GLOW = RenderLayer.of("ragnarsmagicmod_recall_marker",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 2048, false, true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.LIGHTNING_TRANSPARENCY)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    private static final BlockState LODESTONE = Blocks.LODESTONE.getDefaultState();
    /** How far off it's drawn, and how close for the glyphs and chimes. */
    private static final double SHOW = 96, CLOSE = 24, HEAR = 8;
    private static final int POP_TICKS = 10;

    private record Mark(String dimension, BlockPos pos, long since) {}

    private static Mark mark;

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(RecallingSpell.MarkPayload.ID, (p, ctx) -> {
            ClientWorld w = ctx.client().world;
            mark = p.has() && w != null ? new Mark(p.dimension(), p.pos(), w.getTime()) : null;
        });
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> mark = null);
        ClientTickEvents.END_CLIENT_TICK.register(RecallMarkerClient::tick);
        WorldRenderEvents.AFTER_ENTITIES.register(RecallMarkerClient::render);
    }

    /** The mark, if it's in the world being looked at. */
    private static Mark here(ClientWorld w) {
        Mark m = mark;
        return m != null && w != null && w.getRegistryKey().getValue().toString().equals(m.dimension()) ? m : null;
    }

    private static Vec3d centre(BlockPos pos, float time) {
        return new Vec3d(pos.getX() + 0.5, pos.getY() + 0.85 + 0.07 * MathHelper.sin(time * 0.09f), pos.getZ() + 0.5);
    }

    private static void tick(MinecraftClient client) {
        ClientWorld w = client.world;
        Mark m = here(w);
        if (m == null || client.player == null) return;
        Vec3d c = centre(m.pos(), w.getTime());
        double d = client.player.getPos().distanceTo(c);
        if (d > CLOSE) return;
        Random r = w.getRandom();
        long t = w.getTime();
        // Glyphs drawn in to it, as to an enchanting table (they fly from start + offset back to the start)
        if (t % 3 == 0) {
            double a = r.nextDouble() * Math.PI * 2, rad = 1.2 + r.nextDouble() * 0.8;
            w.addParticle(ParticleTypes.ENCHANT, c.x, c.y, c.z, Math.cos(a) * rad, r.nextDouble() * 0.6 - 0.1, Math.sin(a) * rad);
        }
        if (t % 37 == 0) w.addParticle(ParticleTypes.REVERSE_PORTAL, c.x + (r.nextDouble() - 0.5) * 0.3, c.y - 0.2, c.z + (r.nextDouble() - 0.5) * 0.3, 0, 0.03, 0);
        if (d < HEAR && t % 140 == 0) {
            w.playSound(c.x, c.y, c.z, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.AMBIENT, 0.2f, 1.5f, false);
        }
    }

    private static void render(WorldRenderContext ctx) {
        ClientWorld w = ctx.world();
        Mark m = here(w);
        if (m == null) return;
        MinecraftClient client = MinecraftClient.getInstance();
        float td = ctx.tickCounter().getTickDelta(false);
        float time = w.getTime() + td;
        Vec3d cam = ctx.camera().getPos();
        Vec3d c = centre(m.pos(), time);
        if (cam.squaredDistanceTo(c) > SHOW * SHOW) return;
        float pop = MathHelper.clamp((time - m.since()) / POP_TICKS, 0f, 1f);
        float grow = 1f + 2.4f * (pop - 1) * (pop - 1) * (pop - 1) + 1.4f * (pop - 1) * (pop - 1); // a little overshoot
        float pulse = 0.5f + 0.5f * MathHelper.sin(time * 0.12f);

        MatrixStack ms = ctx.matrixStack() != null ? ctx.matrixStack() : new MatrixStack();
        VertexConsumerProvider.Immediate buffers = client.getBufferBuilders().getEntityVertexConsumers();

        // The ring of runes on the ground, and the thread of light up to the stone
        Matrix4f m4 = ms.peek().getPositionMatrix();
        VertexConsumer glow = buffers.getBuffer(GLOW);
        Vec3d ground = new Vec3d(m.pos().getX() + 0.5, m.pos().getY() + 0.02, m.pos().getZ() + 0.5).subtract(cam);
        ring(glow, m4, ground, 0.42f * grow, 0.05f, 0.72f, 0.5f, 1f, (0.3f + 0.2f * pulse) * pop);
        runes(glow, m4, ground, 0.42f * grow, time, (0.45f + 0.3f * pulse) * pop);
        thread(glow, m4, ground, c.subtract(cam).add(0, -0.18 * grow, 0), cam, 0.5f, 0.4f, 1f, 0.22f * pop);
        buffers.draw(GLOW);

        // The little lodestone itself, softly lit as if holding a charge
        ms.push();
        ms.translate(c.x - cam.x, c.y - cam.y, c.z - cam.z);
        ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(time * 2.2f));
        ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(12f));
        float s = 0.34f * Math.max(grow, 0.001f);
        ms.scale(s, s, s);
        ms.translate(-0.5f, -0.5f, -0.5f);
        int light = WorldRenderer.getLightmapCoordinates(w, m.pos().up());
        int lit = LightmapTextureManager.pack(Math.max(11, LightmapTextureManager.getBlockLightCoordinates(light)),
                LightmapTextureManager.getSkyLightCoordinates(light));
        client.getBlockRenderManager().renderBlockAsEntity(LODESTONE, ms, buffers, lit, OverlayTexture.DEFAULT_UV);
        ms.pop();
        buffers.draw();
    }

    /** A thin ring lying on the ground. */
    private static void ring(VertexConsumer vc, Matrix4f m, Vec3d c, float radius, float width, float r, float g, float b, float a) {
        int n = 32;
        for (int k = 0; k < n; k++) {
            double a0 = k * Math.PI * 2 / n, a1 = (k + 1) * Math.PI * 2 / n;
            float ri = radius - width, ro = radius + width;
            v(vc, m, c.add(Math.cos(a0) * ri, 0, Math.sin(a0) * ri), r, g, b, a * 0.4f);
            v(vc, m, c.add(Math.cos(a0) * ro, 0, Math.sin(a0) * ro), r, g, b, a);
            v(vc, m, c.add(Math.cos(a1) * ro, 0, Math.sin(a1) * ro), r, g, b, a);
            v(vc, m, c.add(Math.cos(a1) * ri, 0, Math.sin(a1) * ri), r, g, b, a * 0.4f);
        }
    }

    /** Eight little rune marks just inside the ring, slowly turning the other way to the stone. */
    private static void runes(VertexConsumer vc, Matrix4f m, Vec3d c, float radius, float time, float a) {
        for (int k = 0; k < 8; k++) {
            double ang = k * Math.PI / 4 - time * 0.02;
            double rr = radius - 0.12;
            Vec3d p = c.add(Math.cos(ang) * rr, 0.001, Math.sin(ang) * rr);
            Vec3d along = new Vec3d(-Math.sin(ang), 0, Math.cos(ang)).multiply(0.035);
            Vec3d out = new Vec3d(Math.cos(ang), 0, Math.sin(ang)).multiply(k % 2 == 0 ? 0.06 : 0.035);
            float bright = 0.6f + 0.4f * MathHelper.sin(time * 0.2f + k);
            v(vc, m, p.subtract(along).subtract(out), 0.85f, 0.7f, 1f, a * bright);
            v(vc, m, p.add(along).subtract(out), 0.85f, 0.7f, 1f, a * bright);
            v(vc, m, p.add(along).add(out), 0.85f, 0.7f, 1f, a * bright);
            v(vc, m, p.subtract(along).add(out), 0.85f, 0.7f, 1f, a * bright);
        }
    }

    /** A soft thread of light from the ground up to the stone, turned to face the camera. */
    private static void thread(VertexConsumer vc, Matrix4f m, Vec3d from, Vec3d to, Vec3d cam, float r, float g, float b, float a) {
        Vec3d d = to.subtract(from);
        if (d.lengthSquared() < 1e-6) return;
        Vec3d side = d.crossProduct(from.add(to).multiply(0.5)).normalize().multiply(0.03);
        v(vc, m, from.subtract(side), r, g, b, a);
        v(vc, m, from.add(side), r, g, b, a);
        v(vc, m, to.add(side), r, g, b, a * 0.2f);
        v(vc, m, to.subtract(side), r, g, b, a * 0.2f);
    }

    private static void v(VertexConsumer vc, Matrix4f m, Vec3d p, float r, float g, float b, float a) {
        vc.vertex(m, (float) p.x, (float) p.y, (float) p.z).color(r, g, b, MathHelper.clamp(a, 0f, 1f));
    }
}
