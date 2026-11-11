package net.ragnar.ragnarsmagicmod.geysir.client;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.ragnar.ragnarsmagicmod.geysir.GeysirEntity;
import org.joml.Matrix4f;

/**
 * Draws a geysir the blocky way, out of Minecraft's own animated water. While it warns, chips of the ground it stands on
 * jitter and hop, harder and harder, round a spreading wet patch. Then the column: a stack of rings of chunky water
 * blocks, each ring turned a little from the one below and all of them turning, so the water spirals up the column as
 * it climbs; deep blue at the foot paling to white foam at the top. Over it a crown of foam blocks bursts out and falls
 * away, and a ring of foam churns round its foot. When it's spent the column drops back into the ground.
 */
public class GeysirRenderer extends EntityRenderer<GeysirEntity> {
    private static final RenderLayer LAYER = RenderLayer.getEntityTranslucent(PlayerScreenHandler.BLOCK_ATLAS_TEXTURE);
    /** One ring of the column is this tall. */
    private static final double RING = 0.25;
    private static final int PER_RING = 8, CROWN = 14, FOAM_RING = 10, CHIPS = 10;
    /** Water blue (vanilla's plains water) and the white of foam. */
    private static final float[] WATER = {0.25f, 0.46f, 0.89f}, FOAM = {0.86f, 0.94f, 1.0f};

    public GeysirRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
    }

    private static Sprite sprite(BlockState state) {
        return MinecraftClient.getInstance().getBlockRenderManager().getModels().getModelParticleSprite(state);
    }

    @Override
    public void render(GeysirEntity g, float yaw, float tickDelta, MatrixStack ms, VertexConsumerProvider buffers, int light) {
        double t = g.age + tickDelta;
        Box draw = new Box(buffers.getBuffer(LAYER), ms.peek(), light);
        Sprite water = sprite(Blocks.WATER.getDefaultState());

        double shake = GeysirEntity.shakeAt(t);
        if (shake > 0.01) ground(draw, g, t, shake, water);

        double h = GeysirEntity.heightAt(t);
        if (h > 0.05) {
            column(draw, t, h, water);
            crown(draw, t, h, water);
            foot(draw, t, h, water);
        }
        super.render(g, yaw, tickDelta, ms, buffers, light);
    }

    /** The warning: chips of ground jittering and hopping, and a wet patch spreading under them. */
    private static void ground(Box draw, GeysirEntity g, double t, double shake, Sprite water) {
        BlockState under = g.getWorld().getBlockState(g.getBlockPos().down());
        if (!under.isAir()) {
            Sprite s = sprite(under);
            for (int i = 0; i < CHIPS; i++) {
                double a = i * Math.PI * 2 / CHIPS + hash(i, 1) * 0.6;
                double d = 0.3 + hash(i, 2) * 0.75;
                double hop = shake * 0.14 * Math.abs(Math.sin(t * 2.1 + i * 1.7));
                double half = 0.07 + hash(i, 3) * 0.06;
                draw.cube(Math.cos(a) * d, half + 0.01 + hop, Math.sin(a) * d, half, half, half,
                        a + shake * Math.sin(t * 1.3 + i) * 0.4, s, 1f, 1f, 1f, 1f);
            }
        }
        double wet = Math.min(1, shake * 1.3) * 0.65;
        draw.cube(0, 0.015, 0, wet, 0.012, wet, 0, water, WATER[0] * 0.8f, WATER[1] * 0.8f, WATER[2] * 0.9f, 0.75f);
    }

    /** The column: rings of water blocks spiralling up, blue at the foot and foamy at the top. */
    private static void column(Box draw, double t, double h, Sprite water) {
        int rings = (int) Math.ceil(h / RING);
        for (int j = 0; j < rings; j++) {
            double y0 = j * RING, y1 = Math.min(h, y0 + RING - 0.02);
            if (y1 - y0 < 0.03) continue;
            double up = y0 / GeysirEntity.MAX_HEIGHT;
            double r = 0.6 - 0.22 * up + 0.05 * Math.sin(t * 0.9 + j * 0.8);
            double fromTop = (h - y0) / 1.5; // the last block and a half whitens into foam
            float foam = (float) MathHelper.clamp(1 - fromTop, 0, 1);
            float cr = MathHelper.lerp(foam, WATER[0], FOAM[0]), cg = MathHelper.lerp(foam, WATER[1], FOAM[1]), cb = MathHelper.lerp(foam, WATER[2], FOAM[2]);
            double cy = (y0 + y1) / 2, hy = (y1 - y0) / 2;
            for (int i = 0; i < PER_RING; i++) {
                double a = i * Math.PI * 2 / PER_RING + j * 0.45 - t * 0.28;
                draw.cube(Math.cos(a) * r, cy, Math.sin(a) * r, 0.17, hy, 0.13, a, water, cr, cg, cb, 0.85f);
            }
            // A paler core of rushing water inside
            draw.cube(0, cy, 0, 0.32, hy, 0.32, t * 0.2 + j, water, cr * 1.4f + 0.15f, cg * 1.2f + 0.15f, cb, 0.55f);
        }
    }

    /** Foam bursting out over the top and falling away, round and round. */
    private static void crown(Box draw, double t, double h, Sprite water) {
        for (int i = 0; i < CROWN; i++) {
            double p = frac(t * 0.07 + i * 0.071);
            double a = i * 2.4 + hash(i, 7) * 0.5;
            double d = 0.25 + p * 1.9;
            double y = h + 2.6 * p - 4.2 * p * p;
            if (y < 0.1) continue;
            double half = 0.13 * (1 - 0.55 * p);
            draw.cube(Math.cos(a) * d, y, Math.sin(a) * d, half, half, half, a + p * 4, water,
                    FOAM[0], FOAM[1], FOAM[2], (float) (0.9 * (1 - 0.6 * p)));
        }
    }

    /** A ring of foam churning round the foot. */
    private static void foot(Box draw, double t, double h, Sprite water) {
        float k = (float) Math.min(1, h / 2.0);
        for (int i = 0; i < FOAM_RING; i++) {
            double a = i * Math.PI * 2 / FOAM_RING + t * 0.15;
            double d = 0.85 + 0.12 * Math.sin(t * 0.7 + i * 2);
            double half = 0.15 * k;
            draw.cube(Math.cos(a) * d, half * 0.5, Math.sin(a) * d, half, half * 0.5, half, a, water,
                    FOAM[0], FOAM[1], FOAM[2], 0.75f * k);
        }
    }

    private static double frac(double v) {
        return v - Math.floor(v);
    }

    /** A steady pseudo-random 0..1 for {@code (a, b)}. */
    private static double hash(int a, int b) {
        long x = a * 73856093L ^ b * 19349663L;
        x = (x ^ (x >>> 13)) * 0x5bd1e995L;
        return ((x ^ (x >>> 15)) & 0xffff) / 65535.0;
    }

    /** Draws textured, tinted, see-through boxes turned about the vertical. */
    private record Box(VertexConsumer vc, MatrixStack.Entry entry, int light) {
        void cube(double cx, double cy, double cz, double hx, double hy, double hz, double yaw, Sprite s,
                  float r, float g, float b, float a) {
            if (a <= 0.01f) return;
            double c = Math.cos(yaw), sn = Math.sin(yaw);
            double[][] v = new double[8][];
            for (int i = 0; i < 8; i++) {
                double x = (i & 1) == 0 ? -hx : hx, y = (i & 2) == 0 ? -hy : hy, z = (i & 4) == 0 ? -hz : hz;
                v[i] = new double[]{cx + x * c - z * sn, cy + y, cz + x * sn + z * c};
            }
            // The patch of texture shown is to scale, so the water ripples at the size it does in the world
            float fu = (float) Math.min(1, hx * 2), fv = (float) Math.min(1, hy * 2), fz = (float) Math.min(1, hz * 2);
            face(v, 2, 3, 7, 6, s, fu, fz, r, g, b, a, 0, 1, 0, 1f);
            face(v, 4, 5, 1, 0, s, fu, fz, r, g, b, a, 0, -1, 0, 0.55f);
            face(v, 0, 1, 3, 2, s, fu, fv, r, g, b, a, (float) sn, 0, (float) -c, 0.85f);
            face(v, 5, 4, 6, 7, s, fu, fv, r, g, b, a, (float) -sn, 0, (float) c, 0.85f);
            face(v, 4, 0, 2, 6, s, fz, fv, r, g, b, a, (float) -c, 0, (float) -sn, 0.7f);
            face(v, 1, 5, 7, 3, s, fz, fv, r, g, b, a, (float) c, 0, (float) sn, 0.7f);
        }

        private void face(double[][] v, int i0, int i1, int i2, int i3, Sprite s, float fu, float fv,
                          float r, float g, float b, float a, float nx, float ny, float nz, float shade) {
            float u0 = s.getFrameU(0), u1 = s.getFrameU(fu), v0 = s.getFrameV(0), v1 = s.getFrameV(fv);
            vert(v[i0], u0, v1, r * shade, g * shade, b * shade, a, nx, ny, nz);
            vert(v[i1], u1, v1, r * shade, g * shade, b * shade, a, nx, ny, nz);
            vert(v[i2], u1, v0, r * shade, g * shade, b * shade, a, nx, ny, nz);
            vert(v[i3], u0, v0, r * shade, g * shade, b * shade, a, nx, ny, nz);
        }

        private void vert(double[] p, float u, float v, float r, float g, float b, float a, float nx, float ny, float nz) {
            Matrix4f pose = entry.getPositionMatrix();
            vc.vertex(pose, (float) p[0], (float) p[1], (float) p[2])
                    .color(MathHelper.clamp(r, 0f, 1f), MathHelper.clamp(g, 0f, 1f), MathHelper.clamp(b, 0f, 1f), a)
                    .texture(u, v)
                    .overlay(OverlayTexture.DEFAULT_UV)
                    .light(light)
                    .normal(entry, nx, ny, nz);
        }
    }

    @Override
    public Identifier getTexture(GeysirEntity g) {
        return PlayerScreenHandler.BLOCK_ATLAS_TEXTURE;
    }
}
