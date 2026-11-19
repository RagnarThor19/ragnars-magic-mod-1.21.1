package net.ragnar.ragnarsmagicmod.water.client;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.math.Direction;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws water the blocky way, out of Minecraft's own animated water texture: the ball is a little voxel sphere
 * (only its outer faces, so it stays clear and glassy), and the bits it bursts into are single voxels.
 */
final class WaterVoxels {
    private WaterVoxels() {}

    static final RenderLayer LAYER = RenderLayer.getEntityTranslucent(PlayerScreenHandler.BLOCK_ATLAS_TEXTURE);
    /** Vanilla's plains water (a shade brighter, so it reads at a distance), and a paler blue for the ball's heart. */
    static final float[] WATER = {0.32f, 0.56f, 1.0f}, PALE = {0.7f, 0.88f, 1.0f};

    /** Voxels across the sphere, and the size of one. */
    static final int N = 6;
    static final float VOXEL = 0.15f;
    /** Every outer face of the sphere: voxel center {x, y, z} and the face's direction index. */
    private static final List<float[]> SHELL = new ArrayList<>();
    /** The centers of the voxels on the sphere's surface (for the burst). */
    static final List<float[]> SURFACE = new ArrayList<>();

    static {
        boolean[][][] in = new boolean[N][N][N];
        float lim = 0.46f * 0.46f;
        for (int i = 0; i < N; i++) for (int j = 0; j < N; j++) for (int k = 0; k < N; k++) {
            float x = c(i), y = c(j), z = c(k);
            in[i][j][k] = x * x + y * y + z * z <= lim;
        }
        for (int i = 0; i < N; i++) for (int j = 0; j < N; j++) for (int k = 0; k < N; k++) {
            if (!in[i][j][k]) continue;
            boolean outer = false;
            for (Direction d : Direction.values()) {
                int a = i + d.getOffsetX(), b = j + d.getOffsetY(), e = k + d.getOffsetZ();
                boolean open = a < 0 || b < 0 || e < 0 || a >= N || b >= N || e >= N || !in[a][b][e];
                if (open) {
                    SHELL.add(new float[]{c(i), c(j), c(k), d.ordinal()});
                    outer = true;
                }
            }
            if (outer) SURFACE.add(new float[]{c(i), c(j), c(k)});
        }
    }

    private static float c(int i) {
        return (i - (N - 1) / 2f) * VOXEL;
    }

    static Sprite water() {
        return MinecraftClient.getInstance().getBlockRenderManager().getModels().getModelParticleSprite(Blocks.WATER.getDefaultState());
    }

    /** The voxel sphere, centered on the current origin of {@code ms}. */
    static void sphere(VertexConsumer vc, MatrixStack.Entry entry, int light, Sprite s, float r, float g, float b, float a) {
        float h = VOXEL / 2f;
        for (float[] f : SHELL) {
            face(vc, entry, light, s, f[0], f[1], f[2], h, Direction.values()[(int) f[3]], r, g, b, a, true);
        }
    }

    /** A plain cube of water of half-size {@code h} at {@code (x, y, z)}. */
    static void cube(VertexConsumer vc, MatrixStack.Entry entry, int light, Sprite s, float x, float y, float z, float h,
                     float r, float g, float b, float a) {
        for (Direction d : Direction.values()) face(vc, entry, light, s, x, y, z, h, d, r, g, b, a, false);
    }

    /**
     * One face of a voxel. On the sphere the texture runs on across neighbouring voxels ({@code continuous}), so the
     * ball shows one rippling patch of water rather than a grid of tiny ones.
     */
    private static void face(VertexConsumer vc, MatrixStack.Entry entry, int light, Sprite s, float x, float y, float z, float h,
                             Direction d, float r, float g, float b, float a, boolean continuous) {
        int nx = d.getOffsetX(), ny = d.getOffsetY(), nz = d.getOffsetZ();
        // Two axes across the face, chosen so the corners wind outward
        float[] ua, va;
        switch (d.getAxis()) {
            case X -> { ua = new float[]{0, 0, 1}; va = new float[]{0, 1, 0}; }
            case Y -> { ua = new float[]{1, 0, 0}; va = new float[]{0, 0, 1}; }
            default -> { ua = new float[]{1, 0, 0}; va = new float[]{0, 1, 0}; }
        }
        // (ua x va) points along -X, -Y and +Z, so the other faces take their corners the other way round
        boolean flip = (d.getDirection() == Direction.AxisDirection.POSITIVE) != (d.getAxis() == Direction.Axis.Z);
        float cx = x + nx * h, cy = y + ny * h, cz = z + nz * h;
        // Texture coordinates: the patch under this face, at the scale water has in the world
        float pu = continuous ? dot(ua, x, y, z) + 0.5f : 0.5f, pv = continuous ? dot(va, x, y, z) + 0.5f : 0.5f;
        float u0 = s.getFrameU(clamp(pu - h)), u1 = s.getFrameU(clamp(pu + h));
        float v0 = s.getFrameV(clamp(pv - h)), v1 = s.getFrameV(clamp(pv + h));
        float[][] corners = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        Matrix4f pose = entry.getPositionMatrix();
        for (int n = 0; n < 4; n++) {
            float[] k = corners[flip ? 3 - n : n];
            float px = cx + (ua[0] * k[0] + va[0] * k[1]) * h;
            float py = cy + (ua[1] * k[0] + va[1] * k[1]) * h;
            float pz = cz + (ua[2] * k[0] + va[2] * k[1]) * h;
            vc.vertex(pose, px, py, pz)
                    .color(r, g, b, a)
                    .texture(k[0] < 0 ? u0 : u1, k[1] < 0 ? v1 : v0)
                    .overlay(OverlayTexture.DEFAULT_UV)
                    .light(light)
                    .normal(entry, nx, ny, nz);
        }
    }

    private static float dot(float[] axis, float x, float y, float z) {
        return axis[0] * x + axis[1] * y + axis[2] * z;
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
