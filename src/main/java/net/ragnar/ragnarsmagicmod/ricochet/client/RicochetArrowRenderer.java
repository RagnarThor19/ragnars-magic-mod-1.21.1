package net.ragnar.ragnarsmagicmod.ricochet.client;

import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.ricochet.RicochetArrowEntity;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Draws the Ricochet arrow: the vanilla arrow model in a white skin, full bright, inside a soft white glow. While it
 * winds up it hangs over its caster (drawn from the caster's own interpolated position, so it never lags behind
 * them), pointing where they look, drawing back a little and shaking harder and harder. In flight a bright white
 * streak trails behind it.
 */
public class RicochetArrowRenderer extends EntityRenderer<RicochetArrowEntity> {
    private static final Identifier TEXTURE = Identifier.of(RagnarsMagicMod.MOD_ID, "textures/entity/ricochet_arrow.png");

    static final RenderLayer GLOW = RenderLayer.of("ragnarsmagicmod_ricochet_glow",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 4096, false, true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.LIGHTNING_TRANSPARENCY)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    public RicochetArrowRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
    }

    @Override
    public void render(RicochetArrowEntity arrow, float entityYaw, float tickDelta, MatrixStack ms,
                       VertexConsumerProvider buffers, int light) {
        int charge = arrow.charge();
        Vec3d here = arrow.getLerpedPos(tickDelta);
        Vec3d offset = Vec3d.ZERO;
        float yaw, pitch;
        float progress = 0f; // how far through the wind-up, 0..1

        Entity owner = charge > 0 ? arrow.getWorld().getEntityById(arrow.ownerNetId()) : null;
        if (owner != null) {
            offset = RicochetArrowEntity.hoverPos(owner, tickDelta).subtract(here);
            Vec3d look = owner.getRotationVec(tickDelta);
            yaw = RicochetArrowEntity.yawOf(look);
            pitch = RicochetArrowEntity.pitchOf(look);
            progress = MathHelper.clamp(1f - (charge - tickDelta) / RicochetArrowEntity.CHARGE_TICKS, 0f, 1f);
        } else {
            yaw = MathHelper.lerpAngleDegrees(tickDelta, arrow.prevYaw, arrow.getYaw());
            pitch = MathHelper.lerp(tickDelta, arrow.prevPitch, arrow.getPitch());
        }

        if (charge <= 0) trail(arrow, here, ms.peek().getPositionMatrix(), buffers.getBuffer(GLOW));

        ms.push();
        ms.translate(offset.x, offset.y, offset.z);
        float t = arrow.age + tickDelta;
        float yawShake = 0f, pitchShake = 0f;
        if (charge > 0) {
            // Eager to go: a quick buzzing shake that builds up the whole time, plus a gentle bob
            float p2 = progress * progress;
            float jolt = 0.015f + 0.075f * p2;
            ms.translate(MathHelper.sin(t * 7.9f) * jolt, MathHelper.sin(t * 9.4f + 2f) * jolt + MathHelper.sin(t * 0.35f) * 0.04f,
                    MathHelper.sin(t * 6.6f + 4f) * jolt);
            float deg = 2.5f + 16f * p2;
            yawShake = MathHelper.sin(t * 5.3f) * deg + MathHelper.sin(t * 11.1f) * deg * 0.35f;
            pitchShake = MathHelper.sin(t * 6.1f + 1.3f) * deg + MathHelper.sin(t * 12.7f) * deg * 0.35f;
        }
        // From here on +X is the way the tip points
        ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(yaw - 90f + yawShake));
        ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(pitch + pitchShake));
        if (charge > 0) ms.translate(-0.18f * progress, 0f, 0f); // drawn back, like a bowstring

        halo(ms.peek().getPositionMatrix(), buffers.getBuffer(GLOW), charge > 0 ? 0.35f + 0.45f * progress + 0.15f * MathHelper.sin(t * 3f) : 0.8f);
        arrowModel(ms, buffers.getBuffer(RenderLayer.getEntityCutout(TEXTURE)));
        ms.pop();
        super.render(arrow, entityYaw, tickDelta, ms, buffers, light);
    }

    /** The vanilla arrow: two crossed fins along the shaft and the little square at the nock, centred on the entity. */
    private static void arrowModel(MatrixStack ms, VertexConsumer vc) {
        int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;
        ms.push();
        ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(45f));
        ms.scale(0.05625f, 0.05625f, 0.05625f);
        MatrixStack.Entry e = ms.peek();
        vertex(e, vc, -7, -2, -2, 0.0f, 0.15625f, -1, 0, 0, light);
        vertex(e, vc, -7, -2, 2, 0.15625f, 0.15625f, -1, 0, 0, light);
        vertex(e, vc, -7, 2, 2, 0.15625f, 0.3125f, -1, 0, 0, light);
        vertex(e, vc, -7, 2, -2, 0.0f, 0.3125f, -1, 0, 0, light);
        vertex(e, vc, -7, 2, -2, 0.0f, 0.15625f, 1, 0, 0, light);
        vertex(e, vc, -7, 2, 2, 0.15625f, 0.15625f, 1, 0, 0, light);
        vertex(e, vc, -7, -2, 2, 0.15625f, 0.3125f, 1, 0, 0, light);
        vertex(e, vc, -7, -2, -2, 0.0f, 0.3125f, 1, 0, 0, light);
        for (int i = 0; i < 4; i++) {
            ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(90f));
            e = ms.peek();
            vertex(e, vc, -8, -2, 0, 0.0f, 0.0f, 0, 1, 0, light);
            vertex(e, vc, 8, -2, 0, 0.5f, 0.0f, 0, 1, 0, light);
            vertex(e, vc, 8, 2, 0, 0.5f, 0.15625f, 0, 1, 0, light);
            vertex(e, vc, -8, 2, 0, 0.0f, 0.15625f, 0, 1, 0, light);
        }
        ms.pop();
    }

    private static void vertex(MatrixStack.Entry e, VertexConsumer vc, int x, int y, int z, float u, float v,
                               int nx, int ny, int nz, int light) {
        vc.vertex(e, x, y, z).color(-1).texture(u, v).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(e, nx, ny, nz);
    }

    /** A soft white glow along the shaft, with a brighter core. */
    private static void halo(Matrix4f pose, VertexConsumer vc, float alpha) {
        Vec3d back = new Vec3d(-0.5, 0, 0), tip = new Vec3d(0.5, 0, 0);
        strip(vc, pose, back, tip, 0.42f, 0.75f, 0.85f, 1f, alpha * 0.22f);
        strip(vc, pose, back, tip, 0.2f, 1f, 1f, 1f, alpha * 0.4f);
    }

    /** A white streak through where it was the last few ticks, thinning and fading toward the tail. */
    private static void trail(RicochetArrowEntity arrow, Vec3d here, Matrix4f pose, VertexConsumer vc) {
        if (arrow.trail.size() < 2) return;
        List<Vec3d> pts = new ArrayList<>();
        pts.add(Vec3d.ZERO);
        Iterator<Vec3d> it = arrow.trail.iterator();
        it.next(); // where it's headed this tick, which the arrow hasn't reached yet
        while (it.hasNext()) pts.add(it.next().subtract(here));
        int n = pts.size() - 1;
        for (int i = 0; i < n; i++) {
            float f0 = 1f - i / (float) n, f1 = 1f - (i + 1) / (float) n;
            float a = (f0 + f1) / 2f;
            strip(vc, pose, pts.get(i), pts.get(i + 1), 0.34f * a, 0.75f, 0.85f, 1f, 0.3f * a);
            strip(vc, pose, pts.get(i), pts.get(i + 1), 0.09f * a + 0.02f, 1f, 1f, 1f, 0.95f * a);
        }
    }

    private static Vec3d perpendicular(Vec3d d) {
        Vec3d p = Math.abs(d.y) < 0.9 ? d.crossProduct(new Vec3d(0, 1, 0)) : d.crossProduct(new Vec3d(1, 0, 0));
        return p.normalize();
    }

    /** One straight band from {@code from} to {@code to}: two crossed quads so it looks solid from any side. */
    private static void strip(VertexConsumer vc, Matrix4f pose, Vec3d from, Vec3d to, float width,
                              float r, float g, float b, float a) {
        Vec3d d = to.subtract(from);
        if (d.lengthSquared() < 1e-8 || a <= 0.004f) return;
        Vec3d n = d.normalize();
        Vec3d s1 = perpendicular(n).multiply(width / 2);
        Vec3d s2 = n.crossProduct(s1);
        quad(vc, pose, from, to, s1, r, g, b, a);
        quad(vc, pose, from, to, s2, r, g, b, a);
    }

    private static void quad(VertexConsumer vc, Matrix4f pose, Vec3d from, Vec3d to, Vec3d side,
                             float r, float g, float b, float a) {
        v(vc, pose, from.subtract(side), r, g, b, a);
        v(vc, pose, to.subtract(side), r, g, b, a);
        v(vc, pose, to.add(side), r, g, b, a);
        v(vc, pose, from.add(side), r, g, b, a);
    }

    private static void v(VertexConsumer vc, Matrix4f pose, Vec3d p, float r, float g, float b, float a) {
        vc.vertex(pose, (float) p.x, (float) p.y, (float) p.z).color(r, g, b, MathHelper.clamp(a, 0f, 1f));
    }

    @Override
    public Identifier getTexture(RicochetArrowEntity arrow) {
        return TEXTURE;
    }
}
