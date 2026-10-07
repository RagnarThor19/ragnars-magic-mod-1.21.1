package net.ragnar.ragnarsmagicmod.bubbles.client;

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
import net.ragnar.ragnarsmagicmod.bubbles.BubbleEntity;
import org.joml.Matrix4f;

/**
 * Draws the bubble: a faceted ball of thin film, nearly clear where you look straight through it and banded with
 * soap-film colours toward the rim, swirling slowly. Its surface jiggles all the time, stretches out the way it's
 * drifting, wobbles hard when it swallows something or bounces, and quivers more and more as it nears popping. Two
 * square glints sit on its top left and a little arc of light on its bottom right, and tiny square bubbles come off
 * its film and drift away behind it.
 */
public class BubbleRenderer extends EntityRenderer<BubbleEntity> {
    private static final int LAT = 12, LON = 20;

    public BubbleRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
    }

    @Override
    public void render(BubbleEntity bubble, float entityYaw, float tickDelta, MatrixStack ms,
                       VertexConsumerProvider buffers, int light) {
        Camera camera = dispatcher.camera;
        Vec3d cam = camera.getPos();
        Vec3d c = bubble.lerpedCenter(tickDelta).subtract(cam);
        Vec3d fwd = Vec3d.fromPolar(camera.getPitch(), camera.getYaw());
        Vec3d right = fwd.crossProduct(new Vec3d(0, 1, 0));
        right = right.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : right.normalize();
        Vec3d up = right.crossProduct(fwd).normalize();

        // Work in camera-relative space so every face knows which way the camera is
        ms.push();
        Vec3d origin = bubble.getLerpedPos(tickDelta).subtract(cam);
        ms.translate(-origin.x, -origin.y, -origin.z);
        Matrix4f pose = ms.peek().getPositionMatrix();
        VertexConsumer vc = buffers.getBuffer(BubbleDraw.LAYER);

        float t = bubble.age + tickDelta;
        float r = MathHelper.lerp(tickDelta, bubble.prevShownRadius, bubble.shownRadius);
        Vec3d moving = bubble.getPos().subtract(bubble.prevX, bubble.prevY, bubble.prevZ);

        film(vc, pose, bubble, c, r, t, moving);
        glints(vc, pose, c, r, right, up, strain(t));
        droplets(vc, pose, bubble, cam, right, up, tickDelta);
        ms.pop();
        super.render(bubble, entityYaw, tickDelta, ms, buffers, light);
    }

    /** 0 for most of its life, rising to 1 over its last couple of seconds. */
    private static float strain(float t) {
        return MathHelper.clamp((t - (BubbleEntity.LIFE - 50)) / 50f, 0f, 1f);
    }

    private static void film(VertexConsumer vc, Matrix4f pose, BubbleEntity bubble, Vec3d c, float r, float t, Vec3d moving) {
        float strain = strain(t);
        float kick = (float) Math.exp(-(t - bubble.kickAt) / 6.0);
        double wobble = 0.03 + 0.06 * strain + 0.12 * kick;
        double fast = 1.0 + 2.5 * strain;
        // Stretched a little along the way it's drifting
        double speed = moving.length();
        Vec3d along = speed > 1e-4 ? moving.multiply(1 / speed) : null;
        double stretch = 1 + Math.min(speed * 1.6, 0.12);

        Vec3d[][] pts = new Vec3d[LAT + 1][LON + 1];
        Vec3d[][] normals = new Vec3d[LAT + 1][LON + 1];
        for (int i = 0; i <= LAT; i++) {
            double phi = Math.PI * i / LAT;
            for (int j = 0; j <= LON; j++) {
                double theta = Math.PI * 2 * j / LON;
                Vec3d n = new Vec3d(Math.sin(phi) * Math.cos(theta), Math.cos(phi), Math.sin(phi) * Math.sin(theta));
                normals[i][j] = n;
                double jiggle = wobble * (0.5 * Math.sin(t * 0.33 * fast + n.y * 3.1 + bubble.getId())
                        + 0.35 * Math.sin(t * 0.27 * fast + n.x * 2.7 + 1.3)
                        + 0.35 * Math.sin(t * 0.38 * fast + n.z * 2.9 + 2.1)
                        + kick * 0.8 * Math.sin(t * 1.4 + (n.x + n.y) * 4));
                Vec3d p = n.multiply(r * (1 + jiggle));
                if (along != null) {
                    double a = p.dotProduct(along);
                    Vec3d across = p.subtract(along.multiply(a));
                    p = along.multiply(a * stretch).add(across.multiply(1 / Math.sqrt(stretch)));
                }
                pts[i][j] = c.add(p);
            }
        }

        // Back faces first, seen through the front ones
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < LAT; i++) {
                for (int j = 0; j < LON; j++) {
                    Vec3d a = pts[i][j], b = pts[i][j + 1], d = pts[i + 1][j + 1], e = pts[i + 1][j];
                    Vec3d n = normals[i][j].add(normals[i][j + 1]).add(normals[i + 1][j + 1]).add(normals[i + 1][j]).normalize();
                    Vec3d mid = a.add(b).add(d).add(e).multiply(0.25);
                    Vec3d toCam = mid.multiply(-1).normalize();
                    double facing = n.dotProduct(toCam);
                    boolean front = facing > 0;
                    if (front != (pass == 1)) continue;
                    double edge = 1 - Math.abs(facing);
                    // Bands of colour sliding over the film, thicker toward the rim
                    double swirl = 0.12 * Math.sin(t * 0.045 + n.x * 2.5 + n.z * 1.5) + t * 0.004;
                    float[] col = BubbleDraw.film(0.1 + edge * 1.05 + n.y * 0.22 + swirl);
                    float alpha = (float) (0.05 + 0.6 * Math.pow(edge, 1.8));
                    if (!front) alpha *= 0.55f;
                    // Flickers as it strains to hold together
                    if (strain > 0) alpha *= 1f + strain * 0.5f * (((int) (t * 1.5f) + i + j) % 3 == 0 ? 1 : 0);
                    float w = 0.18f; // a touch of white through all of it
                    BubbleDraw.quad(vc, pose, a, b, d, e,
                            col[0] + (1 - col[0]) * w, col[1] + (1 - col[1]) * w, col[2] + (1 - col[2]) * w, alpha);
                }
            }
        }
    }

    /** The window glints: a big square and a little one on its top left, and an arc of light on its bottom right. */
    private static void glints(VertexConsumer vc, Matrix4f pose, Vec3d c, float r, Vec3d right, Vec3d up, float strain) {
        Vec3d toCam = c.lengthSquared() > 1e-6 ? c.multiply(-1).normalize() : Vec3d.ZERO;
        Vec3d at = c.add(toCam.multiply(0.75).add(up.multiply(0.45)).subtract(right.multiply(0.45)).normalize().multiply(r * 0.97));
        float a = 0.85f - strain * 0.3f;
        BubbleDraw.square(vc, pose, at, right, up, r * 0.24f, 1f, 1f, 1f, a);
        BubbleDraw.square(vc, pose, at.add(right.multiply(r * 0.2)).subtract(up.multiply(r * 0.2)), right, up, r * 0.1f, 1f, 1f, 1f, a * 0.9f);
        for (int k = 0; k < 6; k++) {
            double ang = Math.toRadians(-80 + k * 9);
            Vec3d p = c.add(toCam.multiply(r * 0.45)).add(right.multiply(Math.cos(ang) * r * 0.8)).add(up.multiply(Math.sin(ang) * r * 0.8));
            BubbleDraw.square(vc, pose, p, right, up, r * 0.07f, 0.9f, 0.97f, 1f, a * 0.45f);
        }
    }

    private static void droplets(VertexConsumer vc, Matrix4f pose, BubbleEntity bubble, Vec3d cam, Vec3d right, Vec3d up, float tickDelta) {
        for (BubbleEntity.Droplet d : bubble.droplets) {
            Vec3d p = d.prev.lerp(d.pos, tickDelta).subtract(cam);
            float k = (d.age + tickDelta) / d.life;
            float fade = k < 0.8f ? 1f : (1f - k) / 0.2f;
            BubbleDraw.ring(vc, pose, p, right, up, d.size * 2f, 0.75f * fade);
        }
    }

    @Override
    public Identifier getTexture(BubbleEntity bubble) {
        return PlayerScreenHandler.BLOCK_ATLAS_TEXTURE;
    }
}
