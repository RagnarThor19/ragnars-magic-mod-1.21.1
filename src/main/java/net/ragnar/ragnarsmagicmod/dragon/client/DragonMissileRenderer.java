package net.ragnar.ragnarsmagicmod.dragon.client;

import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EnderDragonEntityRenderer;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.ragnar.ragnarsmagicmod.dragon.DragonMissileEntity;
import org.jetbrains.annotations.Nullable;

/**
 * Draws the missile as a tiny Ender Dragon: the real dragon model, shrunk, beating its wings fast, its neck and tail
 * curving through the turns it's just made. A stand-in dragon (never added to the world) carries the pose into the
 * vanilla renderer.
 */
public class DragonMissileRenderer extends EntityRenderer<DragonMissileEntity> {
    private static final float SCALE = 0.13f;
    private final EnderDragonEntityRenderer dragonRenderer;
    @Nullable private EnderDragonEntity standIn;
    @Nullable private ClientWorld standInWorld;

    public DragonMissileRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
        dragonRenderer = new EnderDragonEntityRenderer(ctx);
        shadowRadius = 0.4f;
    }

    @Override
    public void render(DragonMissileEntity missile, float yaw, float tickDelta, MatrixStack ms, VertexConsumerProvider buffers, int light) {
        // Not drawn for its first moment, while it's still in the caster's face
        if (missile.age < 2 && dispatcher.camera.getPos().squaredDistanceTo(missile.getPos()) < 4) return;
        EnderDragonEntity dragon = standIn(missile);
        if (dragon == null) return;
        pose(dragon, missile, tickDelta);

        float heading = MathHelper.lerpAngleDegrees(tickDelta, missile.prevYaw, missile.getYaw());
        float pitch = MathHelper.lerp(tickDelta, missile.prevPitch, missile.getPitch());
        ms.push();
        ms.translate(0f, missile.getHeight() / 2f, 0f);
        // The dragon model faces -Z: turn it round to face its heading, then tip it to its climb or dive
        ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180f - heading));
        ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-pitch));
        ms.scale(SCALE, SCALE, SCALE);
        ms.translate(0f, -1.0f, 0f); // the model's body sits about a block above its feet
        dragonRenderer.render(dragon, 0f, tickDelta, ms, buffers, light);
        ms.pop();
        super.render(missile, yaw, tickDelta, ms, buffers, light);
    }

    @Nullable
    private EnderDragonEntity standIn(DragonMissileEntity missile) {
        if (!(missile.getWorld() instanceof ClientWorld world)) return null;
        if (standIn == null || standInWorld != world) {
            standIn = EntityType.ENDER_DRAGON.create(world);
            standInWorld = world;
        }
        return standIn;
    }

    /** Wings beating fast, and the body bent through the missile's recent turns. */
    private static void pose(EnderDragonEntity dragon, DragonMissileEntity missile, float tickDelta) {
        float t = missile.age + tickDelta;
        dragon.prevWingPosition = (t - 1f) * 0.11f;
        dragon.wingPosition = t * 0.11f;
        dragon.age = missile.age;
        // The vanilla renderer reads its pose out of this history (yaw, height, -) - relative to now, so it's straight
        // when flying straight and curves when turning
        float now = missile.yawHistory[missile.historyIndex];
        for (int i = 0; i < 64; i++) {
            dragon.segmentCircularBuffer[i][0] = MathHelper.wrapDegrees(missile.yawHistory[i] - now);
            dragon.segmentCircularBuffer[i][1] = 0;
            dragon.segmentCircularBuffer[i][2] = 0;
        }
        dragon.latestSegment = missile.historyIndex;
    }

    @Override
    public Identifier getTexture(DragonMissileEntity missile) {
        return Identifier.ofVanilla("textures/entity/enderdragon/dragon.png");
    }
}
