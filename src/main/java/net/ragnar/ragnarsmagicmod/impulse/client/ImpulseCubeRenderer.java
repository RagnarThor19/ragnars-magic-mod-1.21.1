package net.ragnar.ragnarsmagicmod.impulse.client;

import net.minecraft.client.render.Camera;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.impulse.ImpulseCubeEntity;

/**
 * Draws the impulse cube: a little ender-pearl-ish block of swirling dark blue with a glowing cyan heart, full bright,
 * in a soft blue glow. It tumbles end over end in flight; once stuck it stops dead and starts to throb, faster and
 * brighter the closer it gets to going off.
 */
public class ImpulseCubeRenderer extends EntityRenderer<ImpulseCubeEntity> {
    private static final Identifier TEXTURE = Identifier.of(RagnarsMagicMod.MOD_ID, "textures/entity/impulse_cube.png");
    private static final float HALF = 0.125f;

    public ImpulseCubeRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
    }

    @Override
    public void render(ImpulseCubeEntity cube, float entityYaw, float tickDelta, MatrixStack ms,
                       VertexConsumerProvider buffers, int light) {
        // Like a thrown pearl: not drawn for its first moment, while it's still in the caster's face
        if (cube.age < 1 && dispatcher.camera.getPos().squaredDistanceTo(cube.getPos()) < 2) return;
        float t = cube.age + tickDelta;
        int fuse = cube.fuse();

        float spin;
        float scale = 1f, glow = 0.55f;
        if (fuse < 0) {
            spin = t * 22f;
        } else {
            if (cube.frozenSpin < 0) cube.frozenSpin = t * 22f;
            spin = cube.frozenSpin;
            float close = 1f - MathHelper.clamp((fuse - tickDelta) / ImpulseCubeEntity.FUSE, 0f, 1f);
            float beat = 0.5f + 0.5f * MathHelper.sin(t * (1.2f + 2.6f * close));
            scale = 1f + (0.08f + 0.22f * close) * beat;
            glow = 0.6f + 0.4f * close * beat + 0.25f * close;
        }

        Camera camera = dispatcher.camera;
        Vec3d fwd = Vec3d.fromPolar(camera.getPitch(), camera.getYaw());
        Vec3d right = fwd.crossProduct(new Vec3d(0, 1, 0));
        right = right.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : right.normalize();
        Vec3d up = right.crossProduct(fwd).normalize();
        VertexConsumer glowVc = buffers.getBuffer(ImpulseClient.GLOW);
        ImpulseClient.glow(glowVc, ms.peek().getPositionMatrix(), Vec3d.ZERO, right, up, 0.42f * scale, 0.15f, 0.3f, 1f, 0.5f * glow);
        ImpulseClient.glow(glowVc, ms.peek().getPositionMatrix(), Vec3d.ZERO, right, up, 0.22f * scale, 0.45f, 0.8f, 1f, 0.6f * glow);

        ms.push();
        ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(spin * 0.7f + cube.getId() * 37f));
        ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(spin));
        ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(spin * 0.45f));
        ms.scale(scale, scale, scale);
        cube(ms.peek(), buffers.getBuffer(RenderLayer.getEntityCutout(TEXTURE)));
        ms.pop();
        super.render(cube, entityYaw, tickDelta, ms, buffers, light);
    }

    /** A cube {@link #HALF} * 2 across, the whole texture on every face. */
    private static void cube(MatrixStack.Entry e, VertexConsumer vc) {
        float h = HALF;
        // +X, -X
        face(e, vc, h, -h, -h, h, h, -h, h, h, h, h, -h, h, 1, 0, 0);
        face(e, vc, -h, -h, h, -h, h, h, -h, h, -h, -h, -h, -h, -1, 0, 0);
        // +Y, -Y
        face(e, vc, -h, h, -h, -h, h, h, h, h, h, h, h, -h, 0, 1, 0);
        face(e, vc, -h, -h, h, -h, -h, -h, h, -h, -h, h, -h, h, 0, -1, 0);
        // +Z, -Z
        face(e, vc, h, -h, h, h, h, h, -h, h, h, -h, -h, h, 0, 0, 1);
        face(e, vc, -h, -h, -h, -h, h, -h, h, h, -h, h, -h, -h, 0, 0, -1);
    }

    private static void face(MatrixStack.Entry e, VertexConsumer vc,
                             float x0, float y0, float z0, float x1, float y1, float z1,
                             float x2, float y2, float z2, float x3, float y3, float z3,
                             float nx, float ny, float nz) {
        vertex(e, vc, x0, y0, z0, 0f, 1f, nx, ny, nz);
        vertex(e, vc, x1, y1, z1, 0f, 0f, nx, ny, nz);
        vertex(e, vc, x2, y2, z2, 1f, 0f, nx, ny, nz);
        vertex(e, vc, x3, y3, z3, 1f, 1f, nx, ny, nz);
    }

    private static void vertex(MatrixStack.Entry e, VertexConsumer vc, float x, float y, float z, float u, float v,
                               float nx, float ny, float nz) {
        vc.vertex(e, x, y, z).color(-1).texture(u, v).overlay(OverlayTexture.DEFAULT_UV)
                .light(LightmapTextureManager.MAX_LIGHT_COORDINATE).normal(e, nx, ny, nz);
    }

    @Override
    public Identifier getTexture(ImpulseCubeEntity cube) {
        return TEXTURE;
    }
}
