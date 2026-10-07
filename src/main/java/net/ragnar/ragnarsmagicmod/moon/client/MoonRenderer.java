package net.ragnar.ragnarsmagicmod.moon.client;

import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.ragnar.ragnarsmagicmod.moon.MoonEntity;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Draws the moon and everything around it.
 * <ul>
 *   <li>The moon: one huge block of moon, every face a sheet of pale silver pixels with dusty speckle, dark seas and
 *       craters whose floors sink into the block and whose rims stand up out of it. It tumbles slowly as it comes
 *       down, lit from above like any block, in layers of cold blue halo.</li>
 *   <li>Under it while it falls: its shadow spreading and darkening on the ground, a faint beam of moonlight down to
 *       the landing spot, and two rings of light turning on the ground, marking how far its pull reaches.</li>
 *   <li>When it lands: it sinks into the ground and comes apart in a burst of light, a pillar of light shoots up,
 *       shockwave rings race out, and the crater glows and fades.</li>
 * </ul>
 */
public class MoonRenderer extends EntityRenderer<MoonEntity> {
    /** Pixels across each face of the moon block. */
    private static final int PX = 32;

    /** Plain solid colour, for the moon itself (it makes its own light). */
    private static final RenderLayer SOLID = RenderLayer.of("ragnarsmagicmod_moon",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 65536, false, false,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.NO_TRANSPARENCY)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    /** See-through colour: shadows, and the moon fading out. */
    private static final RenderLayer TINT = RenderLayer.of("ragnarsmagicmod_moon_tint",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 16384, false, true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.TRANSLUCENT_TRANSPARENCY)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    /** Added light: halos, beams, rings. */
    private static final RenderLayer GLOW = RenderLayer.of("ragnarsmagicmod_moon_glow",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 65536, false, true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.LIGHTNING_TRANSPARENCY)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    /** Light falls on the moon from up here (high, and a little to one side). */
    private static final Vector3f LIGHT = new Vector3f(0.45f, 0.8f, 0.35f).normalize();

    /**
     * One face of the moon block, worked out once: how bright each pixel is, and whether it's sunk in (a crater floor,
     * -1), raised (a crater rim, +1) or flat. The outermost ring of pixels is always flat, so the block's edges stay
     * clean and square.
     */
    private record Face(Vector3f normal, Vector3f u, Vector3f v, float[][] albedo, int[][] height) {}

    private static final Face[] FACES = {
            face(new Vector3f(0, 1, 0), new Vector3f(1, 0, 0), new Vector3f(0, 0, 1), 1L),
            face(new Vector3f(0, -1, 0), new Vector3f(1, 0, 0), new Vector3f(0, 0, -1), 2L),
            face(new Vector3f(1, 0, 0), new Vector3f(0, 0, -1), new Vector3f(0, 1, 0), 3L),
            face(new Vector3f(-1, 0, 0), new Vector3f(0, 0, 1), new Vector3f(0, 1, 0), 4L),
            face(new Vector3f(0, 0, 1), new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), 5L),
            face(new Vector3f(0, 0, -1), new Vector3f(-1, 0, 0), new Vector3f(0, 1, 0), 6L),
    };

    /** Paints one face: dusty speckle, a couple of dark seas, and craters - dark sunken bowls with bright raised rims. */
    private static Face face(Vector3f normal, Vector3f u, Vector3f v, long seed) {
        Random r = Random.create(19690720L * 31 + seed); // a moon landing
        float[][] a = new float[PX][PX];
        int[][] h = new int[PX][PX];
        // Seas: big soft dark patches
        int seas = 2 + r.nextInt(2);
        float[][] sea = new float[seas][3];
        for (float[] s : sea) {
            s[0] = r.nextFloat() * PX;
            s[1] = r.nextFloat() * PX;
            s[2] = 6f + r.nextFloat() * 7f;
        }
        for (int x = 0; x < PX; x++) {
            for (int y = 0; y < PX; y++) {
                float b = 0.86f + (r.nextFloat() - 0.5f) * 0.09f;
                for (float[] s : sea) {
                    float d = MathHelper.sqrt(MathHelper.square(x + 0.5f - s[0]) + MathHelper.square(y + 0.5f - s[1])) / s[2];
                    if (d < 1f) b -= 0.17f * (0.5f + 0.5f * MathHelper.cos(d * MathHelper.PI));
                }
                a[x][y] = b;
            }
        }
        // Craters, big ones first so the small ones land on top of them
        int craters = 7 + r.nextInt(4);
        float[][] list = new float[craters][3];
        for (float[] c : list) {
            c[0] = 2f + r.nextFloat() * (PX - 4);
            c[1] = 2f + r.nextFloat() * (PX - 4);
            c[2] = 1.6f + r.nextFloat() * r.nextFloat() * 5.5f;
        }
        java.util.Arrays.sort(list, (p, q) -> Float.compare(q[2], p[2]));
        for (float[] c : list) {
            for (int x = 0; x < PX; x++) {
                for (int y = 0; y < PX; y++) {
                    float d = MathHelper.sqrt(MathHelper.square(x + 0.5f - c[0]) + MathHelper.square(y + 0.5f - c[1]));
                    if (d < c[2] - 0.7f) {
                        h[x][y] = -1;
                        // Darker toward the middle, with a lit peak in the big ones
                        float k = d / c[2];
                        a[x][y] = 0.5f + 0.16f * k + (c[2] > 4.5f && d < 0.9f ? 0.3f : 0f);
                    } else if (d < c[2] + 0.5f) {
                        h[x][y] = 1;
                        a[x][y] = Math.min(1f, a[x][y] + 0.14f);
                    }
                }
            }
        }
        // Clean square edges, and colour in steps like a hand-painted texture
        for (int x = 0; x < PX; x++) {
            for (int y = 0; y < PX; y++) {
                if (x == 0 || y == 0 || x == PX - 1 || y == PX - 1) h[x][y] = 0;
                a[x][y] = Math.round(MathHelper.clamp(a[x][y], 0.4f, 1f) * 20f) / 20f;
            }
        }
        return new Face(normal, u, v, a, h);
    }

    private Matrix4f pose = new Matrix4f();

    public MoonRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
    }

    @Override
    public boolean shouldRender(MoonEntity moon, net.minecraft.client.render.Frustum frustum, double x, double y, double z) {
        return moon.shouldRender(x, y, z) && frustum.isVisible(moon.getVisibilityBoundingBox());
    }

    @Override
    public void render(MoonEntity moon, float yaw, float tickDelta, MatrixStack ms, VertexConsumerProvider buffers, int light) {
        pose = ms.peek().getPositionMatrix();
        Camera camera = dispatcher.camera;
        Vec3d fwd = Vec3d.fromPolar(camera.getPitch(), camera.getYaw());
        Vec3d right = fwd.crossProduct(new Vec3d(0, 1, 0));
        right = right.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : right.normalize();
        Vec3d up = right.crossProduct(fwd).normalize();

        Vec3d origin = moon.getLerpedPos(tickDelta);
        Vec3d cam = camera.getPos();
        Vec3d ground = moon.ground().subtract(origin).add(0, 0.04, 0);
        float age = moon.clientStageAge + tickDelta;
        float time = moon.age + tickDelta;
        float r = MoonEntity.RADIUS;

        switch (moon.stage()) {
            case MoonEntity.FORMING -> {
                float t = MathHelper.clamp(age / MoonEntity.FORM_TICKS, 0f, 1f);
                float s = backOut(t);
                // Light first: the solid block then draws over whatever part of it the block is in front of
                VertexConsumer glow = buffers.getBuffer(GLOW);
                halo(glow, Vec3d.ZERO, right, up, r * Math.max(0.1f, s), 0.6f + 0.6f * (1f - t));
                drawMoon(buffers, Vec3d.ZERO, r * Math.max(0.03f, s), time, origin, cam, 1f);
                drawShadow(buffers, ground, r * 0.7f, 0.15f * t, time);
            }
            case MoonEntity.FALLING -> {
                double height = origin.y - moon.ground().y;
                double span = Math.max(1.0, moon.startY() - moon.ground().y);
                float close = (float) MathHelper.clamp(1.0 - height / span, 0.0, 1.0);
                // Light first: the solid block then draws over whatever part of it the block is in front of
                VertexConsumer glow = buffers.getBuffer(GLOW);
                halo(glow, Vec3d.ZERO, right, up, r, 1f + 0.4f * close);
                beam(glow, ground, Vec3d.ZERO, cam.subtract(origin), r * 0.55f, 0.6f, 0.75f, 1f, 0.1f + 0.15f * close);
                // The reach of its pull, marked out on the ground in two turning rings of light
                float pulse = 0.75f + 0.25f * MathHelper.sin(time * 0.25f);
                dashedRing(glow, ground, (float) MoonEntity.PULL_RADIUS, 0.35f, time * 0.02f, 32, 0.5f, 0.7f, 1f, 0.55f * pulse);
                dashedRing(glow, ground.add(0, 0.02, 0), 5.5f, 0.25f, -time * 0.035f, 18, 0.75f, 0.85f, 1f, 0.45f * pulse);
                flatRing(glow, ground, (float) MoonEntity.PULL_RADIUS + 0.6f, 0.15f, 0.4f, 0.55f, 1f, 0.3f * pulse);
                drawMoon(buffers, Vec3d.ZERO, r, time, origin, cam, 1f);
                drawShadow(buffers, ground, r * (0.8f + 1.5f * close), 0.25f + 0.5f * close, time);
            }
            case MoonEntity.AFTERGLOW -> drawLanding(buffers, moon, ground, age, time, right, up, origin, cam);
            default -> {}
        }
        super.render(moon, yaw, tickDelta, ms, buffers, light);
    }

    // ---------------------------------------------------------------------
    // The moon
    // ---------------------------------------------------------------------

    /**
     * The moon block: each face a sheet of pixels, crater floors sunk in and rims raised, tumbling slowly with the
     * light falling on it from above like on any block. {@code radius} sets its size; {@code alpha} below 1 fades it.
     */
    private void drawMoon(VertexConsumerProvider buffers, Vec3d c, float radius, float time, Vec3d origin, Vec3d cam, float alpha) {
        VertexConsumer vc = buffers.getBuffer(alpha >= 1f ? SOLID : TINT);
        float half = radius * 0.86f;
        float px = 2f * half / PX;
        float depth = px * 0.7f;
        Quaternionf spin = spin(time);
        Matrix4f outer = pose;
        pose = new Matrix4f(outer).translate((float) c.x, (float) c.y, (float) c.z).rotate(spin);

        for (Face f : FACES) {
            Vector3f n = f.normal(), u = f.u(), v = f.v();
            float top = shade(spin, n);
            float east = shade(spin, u), west = shade(spin, new Vector3f(u).negate());
            float north = shade(spin, v), south = shade(spin, new Vector3f(v).negate());
            for (int x = 0; x < PX; x++) {
                float x0 = -half + x * px, x1 = x0 + px;
                for (int y = 0; y < PX; y++) {
                    float y0 = -half + y * px, y1 = y0 + px;
                    int hh = f.height()[x][y];
                    float out = half + hh * depth;
                    float a = f.albedo()[x][y];
                    // The pixel itself
                    quad(vc, n, u, v, out, x0, y0, x1, y0, x1, y1, x0, y1, a * top, alpha);
                    // Walls where it steps up or down to the next pixel along
                    if (x + 1 < PX && f.height()[x + 1][y] != hh) {
                        int hn = f.height()[x + 1][y];
                        boolean higher = hh > hn;
                        float b = (higher ? a : f.albedo()[x + 1][y]) * (higher ? east : west);
                        wall(vc, n, u, v, half + hn * depth, out, x1, y0, y1, true, b, alpha);
                    }
                    if (y + 1 < PX && f.height()[x][y + 1] != hh) {
                        int hn = f.height()[x][y + 1];
                        boolean higher = hh > hn;
                        float b = (higher ? a : f.albedo()[x][y + 1]) * (higher ? north : south);
                        wall(vc, n, u, v, half + hn * depth, out, y1, x0, x1, false, b, alpha);
                    }
                }
            }
        }
        pose = outer;
    }

    /** How it turns as it falls: a slow tumble on a tilted axis, so three faces are always in view. */
    private static Quaternionf spin(float time) {
        return new Quaternionf().rotationY(time * 0.012f).rotateX(0.42f).rotateZ(0.33f);
    }

    /** How lit a surface facing {@code local} (in the block's own frame) is: bright on top, dim underneath. */
    private static float shade(Quaternionf spin, Vector3f local) {
        Vector3f n = spin.transform(new Vector3f(local));
        float lit = MathHelper.clamp(n.dot(LIGHT) * 0.5f + 0.5f, 0f, 1f);
        return Math.min(1.05f, 0.55f + 0.55f * lit * lit);
    }

    /** One pixel's face, {@code out} from the middle along {@code n}, its corners given across the face (u, v). */
    private void quad(VertexConsumer vc, Vector3f n, Vector3f u, Vector3f v, float out,
                      float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3, float b, float alpha) {
        pixel(vc, n, u, v, out, x0, y0, b, alpha);
        pixel(vc, n, u, v, out, x1, y1, b, alpha);
        pixel(vc, n, u, v, out, x2, y2, b, alpha);
        pixel(vc, n, u, v, out, x3, y3, b, alpha);
    }

    /**
     * The step between two pixels: a wall standing at {@code at} along u ({@code alongU}) or v, between heights
     * {@code from} and {@code to}, running from {@code s0} to {@code s1} the other way.
     */
    private void wall(VertexConsumer vc, Vector3f n, Vector3f u, Vector3f v, float from, float to,
                      float at, float s0, float s1, boolean alongU, float b, float alpha) {
        if (alongU) {
            pixel(vc, n, u, v, from, at, s0, b, alpha);
            pixel(vc, n, u, v, to, at, s0, b, alpha);
            pixel(vc, n, u, v, to, at, s1, b, alpha);
            pixel(vc, n, u, v, from, at, s1, b, alpha);
        } else {
            pixel(vc, n, u, v, from, s0, at, b, alpha);
            pixel(vc, n, u, v, to, s0, at, b, alpha);
            pixel(vc, n, u, v, to, s1, at, b, alpha);
            pixel(vc, n, u, v, from, s1, at, b, alpha);
        }
    }

    /** A point on the block: {@code out} along the face's normal, (x, y) across it. Pale silver, cool in the shadows. */
    private void pixel(VertexConsumer vc, Vector3f n, Vector3f u, Vector3f v, float out, float x, float y, float b, float alpha) {
        float cool = MathHelper.clamp(1f - b, 0f, 0.5f) * 0.25f;
        vc.vertex(pose, n.x * out + u.x * x + v.x * y, n.y * out + u.y * x + v.y * y, n.z * out + u.z * x + v.z * y)
                .color(MathHelper.clamp(b * 0.93f, 0f, 1f), MathHelper.clamp(b * 0.95f + cool * 0.3f, 0f, 1f),
                        MathHelper.clamp(b + cool, 0f, 1f), alpha);
    }

    /** Layers of cold light around it. */
    private void halo(VertexConsumer glow, Vec3d c, Vec3d right, Vec3d up, float radius, float strength) {
        glow(glow, c, right, up, radius * 1.3f, 0.75f, 0.85f, 1f, 0.5f * strength);
        glow(glow, c, right, up, radius * 2.1f, 0.5f, 0.65f, 1f, 0.22f * strength);
        glow(glow, c, right, up, radius * 3.6f, 0.35f, 0.45f, 0.9f, 0.1f * strength);
    }

    /** Its shadow on the ground: square, like the block casting it, turning as it turns and soft at the edges. */
    private void drawShadow(VertexConsumerProvider buffers, Vec3d ground, float radius, float alpha, float time) {
        if (alpha <= 0.003f) return;
        VertexConsumer vc = buffers.getBuffer(TINT);
        float yaw = time * 0.012f;
        Vec3d a = new Vec3d(Math.cos(yaw), 0, -Math.sin(yaw)), b = new Vec3d(Math.sin(yaw), 0, Math.cos(yaw));
        float in = radius * 0.75f, out = radius * 1.15f;
        // The solid middle, then a soft fade out past each edge
        Vec3d[] inner = {a.multiply(-in).add(b.multiply(-in)), a.multiply(in).add(b.multiply(-in)), a.multiply(in).add(b.multiply(in)), a.multiply(-in).add(b.multiply(in))};
        Vec3d[] outer = {a.multiply(-out).add(b.multiply(-out)), a.multiply(out).add(b.multiply(-out)), a.multiply(out).add(b.multiply(out)), a.multiply(-out).add(b.multiply(out))};
        for (Vec3d p : inner) v(vc, ground.add(p), 0.01f, 0.02f, 0.06f, alpha);
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            v(vc, ground.add(inner[i]), 0.01f, 0.02f, 0.06f, alpha);
            v(vc, ground.add(inner[j]), 0.01f, 0.02f, 0.06f, alpha);
            v(vc, ground.add(outer[j]), 0.01f, 0.02f, 0.06f, 0f);
            v(vc, ground.add(outer[i]), 0.01f, 0.02f, 0.06f, 0f);
        }
    }

    // ---------------------------------------------------------------------
    // The landing
    // ---------------------------------------------------------------------

    private void drawLanding(VertexConsumerProvider buffers, MoonEntity moon, Vec3d ground, float age, float time,
                             Vec3d right, Vec3d up, Vec3d origin, Vec3d cam) {
        float r = MoonEntity.RADIUS;
        // The moon sinking into the ground and coming apart in light
        if (age < 10f) {
            float k = age / 10f;
            Vec3d c = ground.add(0, r * 0.45f - k * r * 0.6f, 0);
            drawMoon(buffers, c, r * (1f - 0.5f * k), time, origin, cam, 1f - k);
        }
        float left = 1f - MathHelper.clamp(age / MoonEntity.AFTERGLOW_TICKS, 0f, 1f);
        VertexConsumer glow = buffers.getBuffer(GLOW);
        Vec3d mid = ground.add(0, 1.0, 0);

        // The flash
        float flash = MathHelper.clamp(1f - age / 12f, 0f, 1f);
        if (flash > 0f) {
            glow(glow, mid, right, up, 14f * (1.1f - flash * 0.4f), 0.75f, 0.85f, 1f, 0.8f * flash * flash);
            glow(glow, mid, right, up, 5f, 1f, 1f, 1f, flash);
        }
        // A pillar of light shooting up out of the crater
        float pillar = MathHelper.clamp(1f - age / 28f, 0f, 1f);
        if (pillar > 0f) {
            float height = 45f * MathHelper.clamp(age / 4f, 0f, 1f);
            beam(glow, ground, ground.add(0, height, 0), cam.subtract(origin), 2.6f * pillar, 0.8f, 0.9f, 1f, 0.7f * pillar);
            beam(glow, ground, ground.add(0, height * 0.8f, 0), cam.subtract(origin), 0.9f * pillar, 1f, 1f, 1f, 0.9f * pillar);
        }
        // Shockwave rings racing out over the ground
        for (int i = 0; i < 3; i++) {
            float t = MathHelper.clamp((age - i * 3f) / 16f, 0f, 1f);
            if (t <= 0f || t >= 1f) continue;
            float grow = 1f - (1f - t) * (1f - t);
            float fade = (1f - t) * (1f - t);
            float radius = (float) MoonEntity.IMPACT_RADIUS * 1.5f * grow;
            flatRing(glow, ground.add(0, 0.05 + i * 0.4, 0), radius, 1.6f - i * 0.4f, 0.65f, 0.8f, 1f, 0.9f * fade);
        }
        // The crater, glowing with moonlight and slowly going dark
        float shimmer = 0.85f + 0.15f * MathHelper.sin(time * 0.4f);
        disc(glow, ground, 6.5f, 0.55f, 0.7f, 1f, 0.9f * left * left * shimmer, 0f);
        disc(glow, ground.add(0, 0.02, 0), 3f, 0.85f, 0.9f, 1f, 0.8f * left * shimmer, 0f);
        glow(glow, mid, right, up, 5f * shimmer, 0.5f, 0.65f, 1f, 0.35f * left);
        dashedRing(glow, ground.add(0, 0.03, 0), 5.5f, 0.3f, time * 0.03f, 18, 0.75f, 0.85f, 1f, 0.5f * left);
    }

    // ---------------------------------------------------------------------
    // Shapes
    // ---------------------------------------------------------------------

    /** A soft round glow facing the camera: brightest in the middle, fading to nothing at the edge. */
    private void glow(VertexConsumer vc, Vec3d c, Vec3d right, Vec3d up, float size, float r, float g, float b, float alpha) {
        if (alpha <= 0.003f || size <= 0.01f) return;
        int n = 32;
        for (int i = 0; i < n; i++) {
            double t0 = i * Math.PI * 2 / n, t1 = (i + 1) * Math.PI * 2 / n;
            Vec3d e0 = c.add(right.multiply(Math.cos(t0) * size)).add(up.multiply(Math.sin(t0) * size));
            Vec3d e1 = c.add(right.multiply(Math.cos(t1) * size)).add(up.multiply(Math.sin(t1) * size));
            Vec3d m0 = c.add(e0.subtract(c).multiply(0.3)), m1 = c.add(e1.subtract(c).multiply(0.3));
            v(vc, c, r, g, b, alpha);
            v(vc, c, r, g, b, alpha);
            v(vc, m1, r, g, b, alpha * 0.5f);
            v(vc, m0, r, g, b, alpha * 0.5f);
            v(vc, m0, r, g, b, alpha * 0.5f);
            v(vc, m1, r, g, b, alpha * 0.5f);
            v(vc, e1, r, g, b, 0f);
            v(vc, e0, r, g, b, 0f);
        }
    }

    /** A flat disc lying on the ground, {@code alpha} in the middle fading to {@code edgeAlpha} at its rim. */
    private void disc(VertexConsumer vc, Vec3d c, float radius, float r, float g, float b, float alpha, float edgeAlpha) {
        if (alpha <= 0.003f || radius <= 0.01f) return;
        int n = 40;
        for (int i = 0; i < n; i++) {
            double t0 = i * Math.PI * 2 / n, t1 = (i + 1) * Math.PI * 2 / n;
            Vec3d e0 = c.add(Math.cos(t0) * radius, 0, Math.sin(t0) * radius);
            Vec3d e1 = c.add(Math.cos(t1) * radius, 0, Math.sin(t1) * radius);
            Vec3d m0 = c.add(e0.subtract(c).multiply(0.55)), m1 = c.add(e1.subtract(c).multiply(0.55));
            float mid = MathHelper.lerp(0.55f, alpha, edgeAlpha) * 1.1f;
            v(vc, c, r, g, b, alpha);
            v(vc, c, r, g, b, alpha);
            v(vc, m1, r, g, b, mid);
            v(vc, m0, r, g, b, mid);
            v(vc, m0, r, g, b, mid);
            v(vc, m1, r, g, b, mid);
            v(vc, e1, r, g, b, edgeAlpha);
            v(vc, e0, r, g, b, edgeAlpha);
        }
    }

    /** A glowing band lying flat at {@code radius}, bright along its middle and soft at both edges. */
    private void flatRing(VertexConsumer vc, Vec3d c, float radius, float width, float r, float g, float b, float alpha) {
        if (alpha <= 0.003f || radius <= 0.05f) return;
        int n = 64;
        float in = Math.max(0f, radius - width), out = radius + width * 0.5f;
        for (int i = 0; i < n; i++) {
            double t0 = i * Math.PI * 2 / n, t1 = (i + 1) * Math.PI * 2 / n;
            double c0 = Math.cos(t0), s0 = Math.sin(t0), c1 = Math.cos(t1), s1 = Math.sin(t1);
            v(vc, c.add(c0 * in, 0, s0 * in), r, g, b, 0f);
            v(vc, c.add(c1 * in, 0, s1 * in), r, g, b, 0f);
            v(vc, c.add(c1 * radius, 0, s1 * radius), r, g, b, alpha);
            v(vc, c.add(c0 * radius, 0, s0 * radius), r, g, b, alpha);
            v(vc, c.add(c0 * radius, 0, s0 * radius), r, g, b, alpha);
            v(vc, c.add(c1 * radius, 0, s1 * radius), r, g, b, alpha);
            v(vc, c.add(c1 * out, 0, s1 * out), r, g, b, 0f);
            v(vc, c.add(c0 * out, 0, s0 * out), r, g, b, 0f);
        }
    }

    /** A ring of {@code dashes} glowing dashes lying flat, turned by {@code turn} radians - like a rune circle. */
    private void dashedRing(VertexConsumer vc, Vec3d c, float radius, float width, float turn, int dashes,
                            float r, float g, float b, float alpha) {
        if (alpha <= 0.003f) return;
        float in = radius - width, out = radius + width;
        double step = Math.PI * 2 / dashes;
        for (int i = 0; i < dashes; i++) {
            double t0 = turn + i * step, t1 = t0 + step * 0.55;
            double c0 = Math.cos(t0), s0 = Math.sin(t0), c1 = Math.cos(t1), s1 = Math.sin(t1);
            v(vc, c.add(c0 * in, 0, s0 * in), r, g, b, alpha);
            v(vc, c.add(c1 * in, 0, s1 * in), r, g, b, alpha);
            v(vc, c.add(c1 * out, 0, s1 * out), r, g, b, alpha * 0.6f);
            v(vc, c.add(c0 * out, 0, s0 * out), r, g, b, alpha * 0.6f);
        }
    }

    /** A soft upright beam from {@code from} to {@code to}, turned to face the camera, brightest down its middle. */
    private void beam(VertexConsumer vc, Vec3d from, Vec3d to, Vec3d camRel, float width, float r, float g, float b, float alpha) {
        if (alpha <= 0.003f || width <= 0.01f) return;
        Vec3d axis = to.subtract(from);
        if (axis.lengthSquared() < 1e-4) return;
        Vec3d mid = from.add(axis.multiply(0.5));
        Vec3d side = axis.crossProduct(camRel.subtract(mid));
        side = side.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : side.normalize().multiply(width);
        // Faded at the top, so it melts into the sky
        v(vc, from, r, g, b, alpha);
        v(vc, to, r, g, b, 0f);
        v(vc, to.add(side), r, g, b, 0f);
        v(vc, from.add(side), r, g, b, 0f);
        v(vc, from, r, g, b, alpha);
        v(vc, to, r, g, b, 0f);
        v(vc, to.subtract(side), r, g, b, 0f);
        v(vc, from.subtract(side), r, g, b, 0f);
    }

    private void v(VertexConsumer vc, Vec3d p, float r, float g, float b, float a) {
        vc.vertex(pose, (float) p.x, (float) p.y, (float) p.z).color(r, g, b, MathHelper.clamp(a, 0f, 1f));
    }

    // ---------------------------------------------------------------------

    /** Overshoots a little past 1 and settles back: things swelling into being. */
    private static float backOut(float t) {
        float s = 1.4f, u = t - 1f;
        return 1f + (s + 1f) * u * u * u + s * u * u;
    }

    @Override
    public Identifier getTexture(MoonEntity moon) {
        return PlayerScreenHandler.BLOCK_ATLAS_TEXTURE;
    }
}
