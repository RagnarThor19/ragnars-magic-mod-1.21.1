package net.ragnar.ragnarsmagicmod.jaunting.client;

import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.ragnar.ragnarsmagicmod.jaunting.Jaunting;
import net.ragnar.ragnarsmagicmod.jaunting.JauntingKunaiEntity;
import org.joml.Matrix4f;

/**
 * Draws the kunai as its own item sprite, extruded the way Minecraft draws a held sword, laid along the way it's
 * flying (or pointing out of whatever it's stuck in). It glows full bright, and little arcs of lightning keep
 * crackling off the blade.
 */
public class KunaiRenderer extends EntityRenderer<JauntingKunaiEntity> {
    /** Item model size: the sprite runs corner to corner, so this makes the kunai about three quarters of a block long. */
    private static final float SCALE = 0.5625f;

    private final ItemRenderer items;
    private final ItemStack stack;

    public KunaiRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
        this.items = ctx.getItemRenderer();
        this.stack = new ItemStack(Jaunting.KUNAI_ITEM);
    }

    @Override
    public void render(JauntingKunaiEntity kunai, float entityYaw, float tickDelta, MatrixStack ms,
                       VertexConsumerProvider buffers, int light) {
        // Like a thrown snowball: not drawn for its first moment, while it's still inside the thrower's face
        if (kunai.age < 2 && dispatcher.camera.getPos().squaredDistanceTo(kunai.getPos()) < 12.25) return;
        ms.push();
        float yaw, pitch;
        Entity host = kunai.host();
        if (host != null) {
            // Ride along with the host exactly, rather than trailing its synced position
            Vec3d at = kunai.stuckPos(host, tickDelta).subtract(kunai.getLerpedPos(tickDelta));
            ms.translate(at.x, at.y, at.z);
            Vec3d d = kunai.stuckDir(host, tickDelta);
            yaw = (float) (MathHelper.atan2(d.x, d.z) * MathHelper.DEGREES_PER_RADIAN);
            pitch = (float) (MathHelper.atan2(d.y, d.horizontalLength()) * MathHelper.DEGREES_PER_RADIAN);
        } else {
            yaw = MathHelper.lerp(tickDelta, kunai.prevYaw, kunai.getYaw());
            pitch = MathHelper.lerp(tickDelta, kunai.prevPitch, kunai.getPitch());
        }
        // From here on +X is the way the blade points
        ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(yaw - 90f));
        ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(pitch));
        float shake = kunai.shake - tickDelta;
        if (shake > 0f) ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(-MathHelper.sin(shake * 3f) * shake));

        // Two crossed copies, like an arrow, so it never vanishes edge-on
        for (int i = 0; i < 2; i++) {
            ms.push();
            ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(i * 90f));
            ms.scale(SCALE, SCALE, SCALE);
            ms.translate(-0.14f, 0f, 0f); // the base of the blade sits on the entity's position
            ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(-45f));
            items.renderItem(stack, ModelTransformationMode.NONE, LightmapTextureManager.MAX_LIGHT_COORDINATE,
                    OverlayTexture.DEFAULT_UV, ms, buffers, kunai.getWorld(), kunai.getId());
            ms.pop();
        }

        arcs(kunai, tickDelta, ms.peek().getPositionMatrix(), buffers.getBuffer(Bolts.LAYER));
        ms.pop();
        super.render(kunai, entityYaw, tickDelta, ms, buffers, light);
    }

    /** Arcs off the blade, new ones every couple of ticks; in flight, a pair of them streaming out behind. */
    private static void arcs(JauntingKunaiEntity kunai, float tickDelta, Matrix4f pose, VertexConsumer vc) {
        int frame = kunai.age / 2;
        Random r = Random.create(kunai.getId() * 31L + frame * 7919L);
        if (kunai.isFlying()) {
            for (int i = 0; i < 2; i++) {
                Vec3d from = new Vec3d(0.05 + r.nextDouble() * 0.3, 0, 0);
                Vec3d to = new Vec3d(-0.9 - r.nextDouble() * 0.8, r.nextGaussian() * 0.15, r.nextGaussian() * 0.15);
                Bolts.bolt(vc, pose, from, to, r, 6, 0.07, 0.014f, 0.75f, 1);
            }
            return;
        }
        // Stuck: it crackles now and then, more often than not
        if (r.nextFloat() > 0.55f) return;
        int count = 1 + r.nextInt(2);
        for (int i = 0; i < count; i++) {
            Vec3d from = new Vec3d(-0.05 + r.nextDouble() * 0.4, 0, 0);
            Vec3d out = new Vec3d(r.nextGaussian() * 0.4, r.nextGaussian(), r.nextGaussian()).normalize();
            Vec3d to = from.add(out.multiply(0.15 + r.nextDouble() * 0.2));
            float flicker = 0.55f + 0.45f * MathHelper.sin((kunai.age + tickDelta) * 2.1f + i);
            Bolts.bolt(vc, pose, from, to, r, 4, 0.045, 0.011f, flicker, 0);
        }
    }

    @Override
    public Identifier getTexture(JauntingKunaiEntity entity) {
        return PlayerScreenHandler.BLOCK_ATLAS_TEXTURE;
    }
}
