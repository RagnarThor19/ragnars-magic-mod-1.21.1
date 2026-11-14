package net.ragnar.ragnarsmagicmod.sun.client;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.sun.SunEntity;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a Tome of the Sun's sun as a real, solid thing: a ball of molten blocks - shroomlight, glowstone, ochre
 * froglight, and magma for the darker sunspots - all at full brightness. It turns slowly on a tilted axis, and its
 * surface boils: every block swells and sinks on its own rhythm, and now and then becomes another kind. Around it, a
 * blazing corona and slowly turning rays of light. At the end it's gone in a flash, leaving a shell of fire racing out.
 * <p>
 * The blocks are a shell (the inside is never seen), sized so the ball is always the same few hundred blocks however
 * big the sun has grown.
 */
public class SunRenderer extends EntityRenderer<SunEntity> {
    private static final RenderLayer GLOW = RenderLayer.of("ragnarsmagicmod_sun_glow",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 16384, false, true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.LIGHTNING_TRANSPARENCY)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    /** The ball is this many blocks across its radius, whatever its real size. */
    private static final double CELLS = 5.0;

    private static final BlockState[] MOLTEN = {
            Blocks.SHROOMLIGHT.getDefaultState(), Blocks.SHROOMLIGHT.getDefaultState(), Blocks.SHROOMLIGHT.getDefaultState(),
            Blocks.GLOWSTONE.getDefaultState(), Blocks.GLOWSTONE.getDefaultState(),
            Blocks.OCHRE_FROGLIGHT.getDefaultState(), Blocks.OCHRE_FROGLIGHT.getDefaultState(),
            Blocks.MAGMA_BLOCK.getDefaultState(), Blocks.MAGMA_BLOCK.getDefaultState()
    };

    /** One block of the shell: where it sits (in cells), which way is out, and its own rhythm. */
    private record Voxel(double x, double y, double z, Vec3d out, float phase, int seed) {}

    private static final List<Voxel> SHELL = new ArrayList<>();

    static {
        int n = (int) Math.ceil(CELLS + 1);
        int i = 0;
        for (int x = -n; x <= n; x++) {
            for (int y = -n; y <= n; y++) {
                for (int z = -n; z <= n; z++) {
                    double d = Math.sqrt(x * x + y * y + z * z);
                    if (d > CELLS + 0.2 || d < CELLS - 1.25) continue;
                    Vec3d out = d < 1e-6 ? new Vec3d(0, 1, 0) : new Vec3d(x / d, y / d, z / d);
                    int h = (x * 73856093) ^ (y * 19349663) ^ (z * 83492791);
                    SHELL.add(new Voxel(x, y, z, out, (h & 0xFFFF) / 65535f * MathHelper.TAU, h ^ (i++ * 0x9E3779B9)));
                }
            }
        }
    }

    private final BlockRenderManager blocks;

    public SunRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
        this.blocks = ctx.getBlockRenderManager();
        this.shadowRadius = 0f;
    }

    @Override
    public boolean shouldRender(SunEntity sun, Frustum frustum, double x, double y, double z) {
        return sun.shouldRender(x, y, z) && frustum.isVisible(sun.getVisibilityBoundingBox());
    }

    @Override
    public void render(SunEntity sun, float yaw, float tickDelta, MatrixStack ms, VertexConsumerProvider buffers, int light) {
        Camera camera = dispatcher.camera;
        Vec3d fwd = Vec3d.fromPolar(camera.getPitch(), camera.getYaw());
        Vec3d right = fwd.crossProduct(new Vec3d(0, 1, 0));
        right = right.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : right.normalize();
        Vec3d up = right.crossProduct(fwd).normalize();
        float time = sun.age + tickDelta;
        float stageAge = sun.clientStageAge + tickDelta;
        float r = sun.radius();
        Matrix4f pose = ms.peek().getPositionMatrix();

        if (sun.stage() == SunEntity.NOVA) {
            float t = stageAge / SunEntity.NOVA_TICKS;
            nova(buffers.getBuffer(GLOW), pose, right, up, r, t);
            shatter(ms, buffers, r, time, t);
            return;
        }
        boolean collapsing = sun.stage() == SunEntity.COLLAPSING;
        float squeeze = collapsing ? MathHelper.clamp(stageAge / SunEntity.COLLAPSE_TICKS, 0f, 1f) : 0f;
        float igniting = sun.stage() == SunEntity.IGNITING ? 1f - MathHelper.clamp(stageAge / 30f, 0f, 1f) : 0f;

        // The corona and its rays (added light round the ball, never over its face)
        VertexConsumer glow = buffers.getBuffer(GLOW);
        float flare = 1f + 0.6f * igniting + 1.2f * squeeze;
        annulus(glow, pose, right, up, r * 0.9f, r * (1.55f + 0.1f * MathHelper.sin(time * 0.15f)), 1f, 0.85f, 0.45f, 0.55f * flare);
        annulus(glow, pose, right, up, r * 0.95f, r * 2.35f, 1f, 0.5f, 0.12f, 0.28f * flare);
        rays(glow, pose, right, up, r, time, (1f - squeeze) * 0.22f);

        // The ball itself
        ms.push();
        ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(time * 1.1f));
        ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(18f));
        ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(time * 0.35f));
        float cell = (float) (r / CELLS);
        float boilAmp = collapsing ? 0.35f : 0.13f;
        int slotTime = (int) (time / 14f);
        for (Voxel v : SHELL) {
            float boil = MathHelper.sin(time * (collapsing ? 0.9f : 0.32f) + v.phase()) * boilAmp;
            // Each block changes now and then, on its own clock, so the surface churns
            int slot = slotTime + (v.seed() & 7);
            int h = v.seed() * 31 + slot * 0x9E3779B9;
            h ^= h >>> 15;
            BlockState state = MOLTEN[Math.floorMod(h, MOLTEN.length)];
            ms.push();
            ms.translate((v.x() + v.out().x * boil) * cell, (v.y() + v.out().y * boil) * cell, (v.z() + v.out().z * boil) * cell);
            float s = cell * 1.06f;
            ms.scale(s, s, s);
            ms.translate(-0.5f, -0.5f, -0.5f);
            blocks.renderBlockAsEntity(state, ms, buffers, LightmapTextureManager.MAX_LIGHT_COORDINATE, OverlayTexture.DEFAULT_UV);
            ms.pop();
        }
        ms.pop();
        super.render(sun, yaw, tickDelta, ms, buffers, light);
    }

    /**
     * The burst: a blinding flash, then a fireball swelling out to the blast's full reach - white-hot in the middle,
     * orange, then red at its rolling edge - burning away to nothing.
     */
    private static void nova(VertexConsumer vc, Matrix4f m, Vec3d right, Vec3d up, float r0, float t) {
        t = MathHelper.clamp(t, 0f, 1f);
        if (t < 0.3f) {
            float k = 1f - t / 0.3f;
            disc(vc, m, right, up, r0 * (2.2f + 5f * t), 1f, 1f, 0.92f, k);
            disc(vc, m, right, up, r0 * (4f + 8f * t), 1f, 0.9f, 0.6f, 0.5f * k);
        }
        float ease = 1f - (1f - t) * (1f - t);
        float shell = r0 + ease * (float) SunEntity.NOVA_RADIUS * 1.25f;
        float fade = (1f - t) * (1f - t);
        disc(vc, m, right, up, shell * 0.85f, 1f, 0.75f, 0.3f, 0.7f * fade);
        annulus(vc, m, right, up, shell * 0.45f, shell, 1f, 0.5f, 0.1f, 0.75f * fade);
        annulus(vc, m, right, up, shell * 0.85f, shell * 1.2f, 1f, 0.85f, 0.4f, 0.6f * fade);
        annulus(vc, m, right, up, shell, shell * 1.6f, 0.9f, 0.25f, 0.05f, 0.35f * fade);
    }

    /** The sun's own blocks blasted apart: every one flung straight out, tumbling, shrinking away as it flies. */
    private void shatter(MatrixStack ms, VertexConsumerProvider buffers, float r0, float time, float t) {
        if (t >= 1f) return;
        float cell = (float) (r0 / CELLS);
        float shrink = 1f - t;
        if (shrink <= 0.05f) return;
        ms.push();
        ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(18f));
        int i = 0;
        for (Voxel v : SHELL) {
            if ((i++ & 1) == 1) continue; // half of them: plenty of debris, half the drawing
            float speed = 0.8f + ((v.seed() >>> 8) & 0xFF) / 255f * 0.9f;
            float d = (float) SunEntity.NOVA_RADIUS * 1.3f * speed * (1f - (1f - t) * (1f - t));
            int h = v.seed() * 31;
            h ^= h >>> 15;
            BlockState state = MOLTEN[Math.floorMod(h, MOLTEN.length)];
            ms.push();
            ms.translate(v.x() * cell + v.out().x * d, v.y() * cell + v.out().y * d, v.z() * cell + v.out().z * d);
            ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(t * 400f * speed + v.phase() * 57f));
            ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(t * 300f * speed));
            float s = cell * 1.06f * shrink;
            ms.scale(s, s, s);
            ms.translate(-0.5f, -0.5f, -0.5f);
            blocks.renderBlockAsEntity(state, ms, buffers, LightmapTextureManager.MAX_LIGHT_COORDINATE, OverlayTexture.DEFAULT_UV);
            ms.pop();
        }
        ms.pop();
    }

    /** Rays of light round the sun, slowly turning and breathing. */
    private static void rays(VertexConsumer vc, Matrix4f m, Vec3d right, Vec3d up, float r, float time, float alpha) {
        if (alpha <= 0.01f) return;
        int n = 14;
        for (int i = 0; i < n; i++) {
            double a = i * Math.PI * 2 / n + time * 0.004;
            double len = r * (1.9 + 0.5 * Math.sin(time * 0.05 + i * 1.7));
            double w = r * 0.11;
            Vec3d dir = right.multiply(Math.cos(a)).add(up.multiply(Math.sin(a)));
            Vec3d side = right.multiply(-Math.sin(a)).add(up.multiply(Math.cos(a))).multiply(w);
            Vec3d base = dir.multiply(r * 0.85), tip = dir.multiply(len);
            v(vc, m, base.subtract(side), 1f, 0.82f, 0.4f, alpha);
            v(vc, m, base.add(side), 1f, 0.82f, 0.4f, alpha);
            v(vc, m, tip.add(side.multiply(0.15)), 1f, 0.6f, 0.2f, 0f);
            v(vc, m, tip.subtract(side.multiply(0.15)), 1f, 0.6f, 0.2f, 0f);
        }
    }

    /** A ring of light facing the camera: brightest at its inner edge, fading out to its outer one. */
    private static void annulus(VertexConsumer vc, Matrix4f m, Vec3d right, Vec3d up, float inner, float outer,
                                float r, float g, float b, float alpha) {
        int n = 40;
        for (int k = 0; k < n; k++) {
            double a0 = k * Math.PI * 2 / n, a1 = (k + 1) * Math.PI * 2 / n;
            Vec3d d0 = right.multiply(Math.cos(a0)).add(up.multiply(Math.sin(a0)));
            Vec3d d1 = right.multiply(Math.cos(a1)).add(up.multiply(Math.sin(a1)));
            v(vc, m, d0.multiply(inner), r, g, b, alpha);
            v(vc, m, d0.multiply(outer), r, g, b, 0f);
            v(vc, m, d1.multiply(outer), r, g, b, 0f);
            v(vc, m, d1.multiply(inner), r, g, b, alpha);
        }
    }

    /** A round soft blaze of light facing the camera. */
    private static void disc(VertexConsumer vc, Matrix4f m, Vec3d right, Vec3d up, float radius, float r, float g, float b, float alpha) {
        int n = 32;
        for (int k = 0; k < n; k++) {
            double a0 = k * Math.PI * 2 / n, a1 = (k + 1) * Math.PI * 2 / n;
            v(vc, m, Vec3d.ZERO, r, g, b, alpha);
            v(vc, m, Vec3d.ZERO, r, g, b, alpha);
            v(vc, m, right.multiply(Math.cos(a0) * radius).add(up.multiply(Math.sin(a0) * radius)), r, g, b, 0f);
            v(vc, m, right.multiply(Math.cos(a1) * radius).add(up.multiply(Math.sin(a1) * radius)), r, g, b, 0f);
        }
    }

    private static void v(VertexConsumer vc, Matrix4f m, Vec3d p, float r, float g, float b, float a) {
        vc.vertex(m, (float) p.x, (float) p.y, (float) p.z).color(r, g, b, MathHelper.clamp(a, 0f, 1f));
    }

    @Override
    public Identifier getTexture(SunEntity sun) {
        return Identifier.ofVanilla("textures/block/shroomlight.png");
    }
}
