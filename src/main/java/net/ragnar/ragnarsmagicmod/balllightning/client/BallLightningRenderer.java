package net.ragnar.ragnarsmagicmod.balllightning.client;

import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.ragnar.ragnarsmagicmod.balllightning.BallLightningEntity;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Draws the ball: a pulsing white-hot core in layers of yellow and amber glow, wrapped in a tangle of little arcs
 * that writhe over its surface like a plasma globe, the odd longer one lashing out into the air around it, and a
 * crackling streak trailing behind.
 */
public class BallLightningRenderer extends EntityRenderer<BallLightningEntity> {
    public BallLightningRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
    }

    @Override
    public void render(BallLightningEntity ball, float entityYaw, float tickDelta, MatrixStack ms,
                       VertexConsumerProvider buffers, int light) {
        // Like a thrown snowball: not drawn for its first moment, while it's still in the caster's face
        if (ball.age < 1 && dispatcher.camera.getPos().squaredDistanceTo(ball.getPos()) < 4) return;
        Camera camera = dispatcher.camera;
        Vec3d cam = camera.getPos();
        Vec3d here = ball.getLerpedPos(tickDelta);
        Vec3d c = here.subtract(cam);
        Vec3d fwd = Vec3d.fromPolar(camera.getPitch(), camera.getYaw());
        Vec3d right = fwd.crossProduct(new Vec3d(0, 1, 0));
        right = right.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : right.normalize();
        Vec3d up = right.crossProduct(fwd).normalize();

        // Work in camera-relative space so every ribbon can turn to face the camera
        ms.push();
        ms.translate(-c.x, -c.y, -c.z);
        Matrix4f pose = ms.peek().getPositionMatrix();
        VertexConsumer vc = buffers.getBuffer(Arcs.LAYER);
        float t = ball.age + tickDelta;

        trail(ball, here, cam, pose, vc, ball.age);

        float pulse = 1f + 0.12f * MathHelper.sin(t * 1.7f) + 0.06f * MathHelper.sin(t * 4.3f);
        Arcs.glow(vc, pose, c, right, up, 0.85f * pulse, 1f, 0.7f, 0.15f, 0.45f);
        Arcs.glow(vc, pose, c, right, up, 0.45f * pulse, 1f, 0.9f, 0.4f, 0.8f);
        Arcs.glow(vc, pose, c, right, up, 0.22f * pulse, 1f, 1f, 0.95f, 1f);

        // Plasma-globe arcs over the surface, a new tangle every tick
        Random r = Random.create(ball.getId() * 7919L + ball.age * 104729L);
        for (int i = 0; i < 7; i++) {
            Vec3d to = c.add(Arcs.randomDir(r).multiply(0.4 + r.nextDouble() * 0.35));
            Arcs.bolt(vc, pose, c, to, r, 0.55f, 0.85f, false);
        }
        // Now and then one lashes out into the air, mostly trailing behind
        int lashes = r.nextFloat() < 0.6f ? 1 + r.nextInt(2) : 0;
        Vec3d back = ball.trail.size() > 1 ? ball.trail.getLast().subtract(here) : Vec3d.ZERO;
        back = back.lengthSquared() > 1e-4 ? back.normalize() : Vec3d.ZERO;
        for (int i = 0; i < lashes; i++) {
            Vec3d dir = Arcs.randomDir(r).add(back.multiply(0.8)).normalize();
            Arcs.bolt(vc, pose, c, c.add(dir.multiply(1.0 + r.nextDouble() * 1.4)), r, 0.6f, 0.7f, true);
        }
        ms.pop();
        super.render(ball, entityYaw, tickDelta, ms, buffers, light);
    }

    /** A crackling streak through where it's been, thinning and fading toward the tail. */
    private static void trail(BallLightningEntity ball, Vec3d here, Vec3d cam, Matrix4f pose, VertexConsumer vc, int age) {
        if (ball.trail.size() < 2) return;
        List<Vec3d> pts = new ArrayList<>();
        pts.add(here);
        Iterator<Vec3d> it = ball.trail.iterator();
        it.next(); // where it's headed this tick, which it hasn't reached yet
        while (it.hasNext()) pts.add(it.next());
        Random r = Random.create(ball.getId() * 31L + age * 7L);
        int n = pts.size() - 1;
        for (int i = 0; i < n; i++) {
            float k = 1f - (i + 0.5f) / n;
            Vec3d a = pts.get(i).subtract(cam), b = pts.get(i + 1).subtract(cam);
            Arcs.ribbon(vc, pose, a, b, 0.55f * k, 1f, 0.75f, 0.2f, 0.25f * k);
            Vec3d[] jag = Arcs.path(a, b, r, 3, 0.12 * k + 0.03);
            for (int j = 0; j < jag.length - 1; j++) Arcs.segment(vc, pose, jag[j], jag[j + 1], 0.7f * k + 0.2f, 0.8f * k);
        }
    }

    @Override
    public Identifier getTexture(BallLightningEntity ball) {
        return PlayerScreenHandler.BLOCK_ATLAS_TEXTURE;
    }
}
