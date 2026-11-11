package net.ragnar.ragnarsmagicmod.tornado.client;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
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
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.tornado.TornadoEntity;
import org.joml.Matrix4f;

/**
 * Draws the tornado the blocky way. The funnel is a stack of rings, each {@link #RING 4 pixels} tall with a pixel's gap
 * between them, and every ring is a loop of chunky little slabs - some missing, so you see through it. Each ring spins
 * at its own speed and the gaps line up into stripes that spiral up the funnel, the whole stack snaking about and
 * flaring out toward the top.
 * <p>
 * The slabs are a palette of wind colours - slate and steel blue low down, pale sky, aqua and lilac through the middle,
 * white at the lip - and about one in five is a real block instead: panes of clear, white and light blue glass, calcite,
 * snow and wool caught up in it. The bottom rings are made of the very ground it's tearing up. Inside, a darker core of
 * slabs spins the other way. Round its foot a ring of dust blocks whirls along the ground, trails of coloured wind
 * pixels spiral up the outside, and chunks of soil and the odd shard of glass pane tumble round it.
 */
public class TornadoRenderer extends EntityRenderer<TornadoEntity> {
    /** One pixel, in blocks. */
    private static final double PX = 1 / 16.0;
    private static final double RING = 4 * PX, GAP = PX, THICK = 2 * PX;

    /** Plain coloured blocks: depth-written so they hide each other properly. */
    static final RenderLayer SOLID = RenderLayer.of("ragnarsmagicmod_tornado_solid",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 65536, false, false,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .writeMaskState(RenderPhase.ALL_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    /** The see-through wind pixels. */
    static final RenderLayer WIND = RenderLayer.of("ragnarsmagicmod_tornado_wind",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 16384, false, true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.TRANSLUCENT_TRANSPARENCY)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    /** Real block textures (glass and all), lit like any entity. */
    private static final RenderLayer TEXTURED = RenderLayer.getEntityTranslucent(PlayerScreenHandler.BLOCK_ATLAS_TEXTURE);

    /** The wind colours. */
    private static final float[][] PALETTE = {
            {0.95f, 0.97f, 0.99f}, // 0 white
            {0.80f, 0.90f, 0.97f}, // 1 pale sky
            {0.72f, 0.88f, 0.90f}, // 2 pale aqua
            {0.83f, 0.80f, 0.95f}, // 3 pale lilac
            {0.63f, 0.72f, 0.83f}, // 4 steel blue
            {0.49f, 0.55f, 0.68f}, // 5 slate
    };
    /** Which colours each fifth of the height (bottom to top) is made of. */
    private static final int[][] BANDS = {{5, 4, 5}, {4, 2, 3}, {1, 2, 3}, {1, 0, 3}, {0, 1, 0}};
    private static final float[] CORE = {0.40f, 0.45f, 0.57f};

    /** The blocks caught up in it, and how often each turns up (by repeats). */
    private static final BlockState[] CAUGHT = {
            Blocks.GLASS.getDefaultState(), Blocks.GLASS.getDefaultState(), Blocks.GLASS.getDefaultState(),
            Blocks.WHITE_STAINED_GLASS.getDefaultState(), Blocks.WHITE_STAINED_GLASS.getDefaultState(),
            Blocks.LIGHT_BLUE_STAINED_GLASS.getDefaultState(), Blocks.LIGHT_BLUE_STAINED_GLASS.getDefaultState(),
            Blocks.CALCITE.getDefaultState(), Blocks.SNOW_BLOCK.getDefaultState(), Blocks.WHITE_WOOL.getDefaultState(),
    };
    /** How many of {@link #CAUGHT} (from the start) are glass, drawn as whole framed panes. */
    private static final int GLASSES = 7;

    public TornadoRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
    }

    private static Sprite sprite(BlockState state) {
        return MinecraftClient.getInstance().getBlockRenderManager().getModels().getModelParticleSprite(state);
    }

    @Override
    public void render(TornadoEntity tornado, float entityYaw, float tickDelta, MatrixStack ms,
                       VertexConsumerProvider buffers, int light) {
        float s = MathHelper.lerp(tickDelta, tornado.prevShownScale, tornado.shownScale);
        if (s < 0.01f) return;
        Camera camera = dispatcher.camera;
        Vec3d cam = camera.getPos();
        Vec3d base = tornado.getLerpedPos(tickDelta).subtract(cam);
        Vec3d lean = tornado.prevLean.lerp(tornado.lean, tickDelta);
        double t = tornado.age + tickDelta;
        float lit = brightness(tornado, light, tickDelta);

        // Work in camera-relative space
        ms.push();
        Vec3d origin = tornado.getLerpedPos(tickDelta).subtract(cam);
        ms.translate(-origin.x, -origin.y, -origin.z);
        MatrixStack.Entry entry = ms.peek();
        Matrix4f pose = entry.getPositionMatrix();

        Draw solid = new Draw(buffers.getBuffer(SOLID), pose, null, lit, light);
        rings(solid, tornado, base, lean, t, s, false);
        core(solid, tornado, base, lean, t, s);
        skirt(solid, tornado, base, t, s);
        debris(solid, tornado, cam, tickDelta, false);

        Draw tex = new Draw(buffers.getBuffer(TEXTURED), pose, entry, lit, light);
        rings(tex, tornado, base, lean, t, s, true);
        debris(tex, tornado, cam, tickDelta, true);

        streaks(new Draw(buffers.getBuffer(WIND), pose, null, lit, light), tornado, base, lean, t, s);
        ms.pop();
        super.render(tornado, entityYaw, tickDelta, ms, buffers, light);
    }

    /** How lit it is where it stands, so the plain colours aren't glowing at night or in a cave. */
    private static float brightness(TornadoEntity tornado, int light, float tickDelta) {
        float block = LightmapTextureManager.getBlockLightCoordinates(light) / 15f;
        float sky = LightmapTextureManager.getSkyLightCoordinates(light) / 15f;
        if (tornado.getWorld() instanceof ClientWorld cw) sky *= cw.getSkyBrightness(tickDelta);
        return 0.22f + 0.78f * Math.max(block, sky);
    }

    /** A fixed pseudo-random number in [0, 1) for a ring and a slab. */
    private static double hash(int a, int b) {
        long h = a * 73856093L ^ b * 19349663L;
        h = (h ^ (h >>> 13)) * 0x5bd1e995L;
        h ^= h >>> 15;
        return (h & 0xffff) / 65536.0;
    }

    private static double snap(double v) {
        return Math.round(v / PX) * PX;
    }

    // ---------------------------------------------------------------------
    // The funnel
    // ---------------------------------------------------------------------

    /** The rings: the plain coloured slabs on the solid pass, the real blocks on the textured one. */
    private static void rings(Draw d, TornadoEntity tornado, Vec3d base, Vec3d lean, double t, float s, boolean textured) {
        int rings = (int) Math.floor(TornadoEntity.HEIGHT / RING);
        int gc = tornado.groundColor;
        float[] ground = {((gc >> 16) & 255) / 255f, ((gc >> 8) & 255) / 255f, (gc & 255) / 255f};
        Sprite soil = textured ? sprite(tornado.soilState) : null;
        // Spinning up it builds from the ground; blowing out, it falls apart slab by slab
        int shown = (int) Math.ceil(rings * Math.min(1f, s * 1.15f));
        for (int i = 0; i < shown; i++) {
            double hu = i * RING;
            double k = hu / TornadoEntity.HEIGHT;
            Vec3d axis = TornadoEntity.spine(hu + RING * 0.5, t, tornado.getId(), lean);
            double r = snap(TornadoEntity.radiusAt(hu + RING * 0.5, t) * (0.75 + 0.25 * s));
            // Narrow rings whip round faster than the wide ones at the top
            double turn = t * (0.42 - 0.22 * k) * (1 + 0.12 * Math.sin(i * 1.7)) + i * 0.3;
            int slabs = Math.max(6, (int) Math.round(Math.PI * 2 * r / 0.5));
            double y0 = hu, y1 = hu + RING - GAP;
            Vec3d c = base.add(axis);
            for (int j = 0; j < slabs; j++) {
                // Gaps line up into stripes spiralling up the funnel, with a few odd holes
                int stripe = Math.floorMod(j * 3 - i, slabs);
                if (stripe % 5 == 0 || hash(i, j) < 0.12) continue;
                if (hash(j, i + 999) > s * 1.2) continue; // falling apart as it fades
                double a0 = Math.PI * 2 * j / slabs + turn, a1 = Math.PI * 2 * (j + 0.8) / slabs + turn;
                boolean bottom = i < 3 && !tornado.overWater;
                double pick = hash(i + 333, j);

                if (bottom) {
                    // Torn-up ground: mostly the soil itself, some of it plain surface colour
                    boolean realSoil = (i + j) % 3 != 0;
                    if (realSoil != textured) continue;
                    if (textured) d.texSlab(c, r, a0, a1, y0, y1, soil, false, i, j);
                    else d.slab(c, r, a0, a1, y0, y1, ground);
                } else if (i < 2) {
                    if (!textured) d.slab(c, r, a0, a1, y0, y1, new float[]{0.55f, 0.72f, 0.92f}); // spray over water
                } else if (pick < 0.2 && i < shown - 1) {
                    // A block caught up in the wind
                    if (!textured) continue;
                    int which = (int) (hash(j + 77, i) * CAUGHT.length);
                    d.texSlab(c, r, a0, a1, y0, y1, sprite(CAUGHT[which]), which < GLASSES, i, j);
                } else {
                    if (textured) continue;
                    int band = Math.min(BANDS.length - 1, (int) (k * BANDS.length));
                    float[] col = PALETTE[BANDS[band][(int) (hash(i, j + 7) * 3)]];
                    // The edge of each stripe catches the light
                    if (stripe % 5 == 1) col = lighter(col);
                    d.slab(c, r, a0, a1, y0, y1, col);
                }
            }
        }
    }

    private static float[] lighter(float[] c) {
        return new float[]{c[0] + (1 - c[0]) * 0.4f, c[1] + (1 - c[1]) * 0.4f, c[2] + (1 - c[2]) * 0.4f};
    }

    /** A darker core of slabs inside, spinning the other way, so the funnel has some depth. */
    private static void core(Draw d, TornadoEntity tornado, Vec3d base, Vec3d lean, double t, float s) {
        int rings = (int) Math.floor(TornadoEntity.HEIGHT / RING);
        int shown = (int) Math.ceil(rings * Math.min(1f, s * 1.15f));
        for (int i = 2; i < shown - 3; i += 2) {
            double hu = i * RING;
            Vec3d axis = TornadoEntity.spine(hu + RING * 0.5, t, tornado.getId(), lean);
            double r = snap(TornadoEntity.radiusAt(hu + RING * 0.5, t) * 0.42 * (0.75 + 0.25 * s));
            double turn = -t * 0.55 + i * 0.5;
            for (int j = 0; j < 5; j++) {
                if ((j + i / 2) % 5 == 0) continue;
                double a0 = Math.PI * 2 * j / 5 + turn, a1 = Math.PI * 2 * (j + 0.7) / 5 + turn;
                d.slab(base.add(axis), r, a0, a1, hu, hu + RING - GAP, CORE);
            }
        }
    }

    /** A ring of dust blocks whirling round its foot. */
    private static void skirt(Draw d, TornadoEntity tornado, Vec3d base, double t, float s) {
        int gc = tornado.overWater ? 0x9cc4ee : tornado.groundColor, sc = tornado.overWater ? 0xd8ecff : tornado.soilColor;
        for (int ring = 0; ring < 2; ring++) {
            double r = (1.0 + ring * 0.7) * s;
            int n = 10 + ring * 6;
            double turn = t * (0.3 - ring * 0.1) + ring;
            for (int j = 0; j < n; j++) {
                if (hash(j, ring + 50) < 0.3) continue;
                double a = Math.PI * 2 * j / n + turn;
                double bob = Math.abs(Math.sin(t * 0.3 + j * 1.9 + ring)) * 0.25;
                double size = (2 + (int) (hash(j, ring + 70) * 3)) * PX * s;
                int c = hash(j, ring + 90) < 0.5 ? gc : sc;
                Vec3d p = base.add(Math.cos(a) * r, size * 0.5 + bob, Math.sin(a) * r);
                d.cube(p, size * 0.5, size * 0.5, size * 0.5, a, new float[]{((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f}, null, 0);
            }
        }
    }

    /** Trails of wind pixels spiralling up round the outside: mostly white, some sky blue and lilac. */
    private static void streaks(Draw d, TornadoEntity tornado, Vec3d base, Vec3d lean, double t, float s) {
        Vec3d toCam = base.add(0, TornadoEntity.HEIGHT * 0.5, 0).multiply(-1);
        Vec3d fwd = toCam.lengthSquared() > 1e-6 ? toCam.normalize() : new Vec3d(0, 0, 1);
        Vec3d right = fwd.crossProduct(new Vec3d(0, 1, 0));
        right = right.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : right.normalize();
        Vec3d up = right.crossProduct(fwd).normalize();
        float[][] colours = {{1f, 1f, 1f}, {0.75f, 0.9f, 1f}, {1f, 1f, 1f}, {0.86f, 0.8f, 1f}};
        int n = 12, pts = 7;
        for (int k = 0; k < n; k++) {
            double speed = 0.006 * (1 + (k % 3) * 0.35);
            double u0 = (k / (double) n + t * speed) % 1.0;
            double a0 = k * 2.39 + t * 0.3 * (1 + (k % 2) * 0.2);
            float fadeEnds = (float) Math.min(1, Math.min(u0 / 0.1, (1 - u0) / 0.15));
            float[] col = colours[k % colours.length];
            for (int m = 0; m < pts; m++) {
                double hu = TornadoEntity.HEIGHT * u0 * 0.95 - m * 0.06;
                if (hu < 0 || hu > TornadoEntity.HEIGHT * s) continue;
                double a = a0 - m * 0.16;
                Vec3d axis = TornadoEntity.spine(hu, t, tornado.getId(), lean);
                double r = TornadoEntity.radiusAt(hu, t) + 0.2;
                Vec3d p = base.add(axis.x + Math.cos(a) * r, hu, axis.z + Math.sin(a) * r);
                float tail = 1f - m / (float) pts;
                double size = (m < 2 ? 2 : 1) * PX * 1.5;
                d.square(p, right, up, size, col, 0.85f * tail * fadeEnds * s);
            }
        }
    }

    /** The chunks whirling round it: plain flecks on the solid pass; soil chunks and glass shards on the textured one. */
    private static void debris(Draw d, TornadoEntity tornado, Vec3d cam, float tickDelta, boolean textured) {
        Sprite glass = textured ? sprite(Blocks.GLASS_PANE.getDefaultState()) : null;
        int n = 0;
        for (TornadoEntity.Debris b : tornado.debris) {
            n++;
            if (b.pos == null) continue;
            boolean isTextured = b.pane || b.state != null;
            if (isTextured != textured) continue;
            Vec3d c = b.prev.lerp(b.pos, tickDelta).subtract(cam);
            float spin = MathHelper.lerp(tickDelta, b.prevSpin, b.spin);
            double h = b.size * 0.5;
            if (b.pane) {
                // A thin square of glass pane, five pixels across and one thick
                d.cube(c, 2.5 * PX, 0.5 * PX, 2.5 * PX, spin, new float[]{1f, 1f, 1f}, glass, -1);
            } else if (b.state != null) {
                d.cube(c, h, h, h, spin, new float[]{1f, 1f, 1f}, sprite(b.state), n);
            } else {
                d.cube(c, h, h, h, spin, new float[]{b.r, b.g, b.b}, null, 0);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------

    /**
     * Draws into one buffer: plain coloured quads (shaded by {@code lit}), or textured ones with real lighting when
     * {@code entry} is given.
     */
    private record Draw(VertexConsumer vc, Matrix4f pose, MatrixStack.Entry entry, float lit, int light) {

        /** One plain slab of a ring: a little curved block between two angles. */
        void slab(Vec3d c, double r, double a0, double a1, double y0, double y1, float[] col) {
            Vec3d[] v = slabCorners(c, r, a0, a1, y0, y1);
            float r_ = col[0] * lit, g = col[1] * lit, b = col[2] * lit;
            quad(v[4], v[5], v[7], v[6], r_, g, b, 1f);                               // outside
            quad(v[0], v[1], v[3], v[2], r_ * 0.62f, g * 0.62f, b * 0.62f, 1f);       // inside, in shadow
            quad(v[2], v[3], v[7], v[6], r_ * 1.08f, g * 1.08f, b * 1.08f, 1f);       // top
            quad(v[0], v[1], v[5], v[4], r_ * 0.55f, g * 0.55f, b * 0.55f, 1f);       // bottom
            quad(v[0], v[4], v[6], v[2], r_ * 0.8f, g * 0.8f, b * 0.8f, 1f);          // ends
            quad(v[1], v[5], v[7], v[3], r_ * 0.8f, g * 0.8f, b * 0.8f, 1f);
        }

        /**
         * A slab made of a real block. Glass is squeezed whole onto its faces, frame and all, so it reads as a little
         * pane; anything else shows a true-to-scale patch of its texture.
         */
        void texSlab(Vec3d c, double r, double a0, double a1, double y0, double y1, Sprite sprite, boolean wholePane, int i, int j) {
            Vec3d[] v = slabCorners(c, r, a0, a1, y0, y1);
            double len = v[4].distanceTo(v[5]), tall = y1 - y0;
            float fw = wholePane ? 1f : (float) Math.min(1, len), fh = wholePane ? 1f : (float) Math.min(1, tall);
            float ou = (float) (hash(i, j + 11) * (1 - fw)), ov = (float) (hash(j, i + 13) * (1 - fh));
            float th = wholePane ? 1f : (float) THICK;
            double mid = (a0 + a1) * 0.5;
            Vec3d out = new Vec3d(Math.cos(mid), 0, Math.sin(mid));
            Vec3d along = new Vec3d(-Math.sin(mid), 0, Math.cos(mid));
            texQuad(v[4], v[5], v[7], v[6], sprite, ou, ov, ou + fw, ov + fh, 1f, out);
            texQuad(v[0], v[1], v[3], v[2], sprite, ou, ov, ou + fw, ov + fh, 0.7f, out.multiply(-1));
            texQuad(v[2], v[3], v[7], v[6], sprite, ou, ov, ou + fw, ov + Math.min(fh, th), 1f, new Vec3d(0, 1, 0));
            texQuad(v[0], v[1], v[5], v[4], sprite, ou, ov, ou + fw, ov + Math.min(fh, th), 0.6f, new Vec3d(0, -1, 0));
            texQuad(v[0], v[4], v[6], v[2], sprite, ou, ov, ou + Math.min(fw, th), ov + fh, 0.8f, along.multiply(-1));
            texQuad(v[1], v[5], v[7], v[3], sprite, ou, ov, ou + Math.min(fw, th), ov + fh, 0.8f, along);
        }

        /** Corners of a ring slab: 0-3 inside (bottom a0, bottom a1, top a0, top a1), 4-7 the same outside. */
        private static Vec3d[] slabCorners(Vec3d c, double r, double a0, double a1, double y0, double y1) {
            double ri = r - THICK * 0.5, ro = r + THICK * 0.5;
            double c0 = Math.cos(a0), s0 = Math.sin(a0), c1 = Math.cos(a1), s1 = Math.sin(a1);
            return new Vec3d[]{
                    c.add(c0 * ri, y0, s0 * ri), c.add(c1 * ri, y0, s1 * ri), c.add(c0 * ri, y1, s0 * ri), c.add(c1 * ri, y1, s1 * ri),
                    c.add(c0 * ro, y0, s0 * ro), c.add(c1 * ro, y0, s1 * ro), c.add(c0 * ro, y1, s0 * ro), c.add(c1 * ro, y1, s1 * ro),
            };
        }

        /**
         * A box with half-sizes {@code hx, hy, hz}, tumbled by {@code spin}, shaded like a block. With a sprite it's
         * textured: {@code patch} picks which bit of the texture shows ({@code -1} squeezes the whole texture onto it).
         */
        void cube(Vec3d c, double hx, double hy, double hz, double spin, float[] col, Sprite sprite, int patch) {
            double cy = Math.cos(spin), sy = Math.sin(spin), cx = Math.cos(spin * 0.7 + 1), sx = Math.sin(spin * 0.7 + 1);
            Vec3d[] v = new Vec3d[8];
            for (int i = 0; i < 8; i++) {
                double x = (i & 1) == 0 ? -hx : hx, y = (i & 2) == 0 ? -hy : hy, z = (i & 4) == 0 ? -hz : hz;
                v[i] = c.add(turn(x, y, z, cy, sy, cx, sx));
            }
            int[][] faces = {{2, 3, 7, 6}, {0, 1, 5, 4}, {0, 2, 6, 4}, {1, 3, 7, 5}, {0, 1, 3, 2}, {4, 5, 7, 6}};
            float[] shades = {1f, 0.5f, 0.7f, 0.7f, 0.85f, 0.85f};
            Vec3d[] normals = {turn(0, 1, 0, cy, sy, cx, sx), turn(0, -1, 0, cy, sy, cx, sx), turn(-1, 0, 0, cy, sy, cx, sx),
                    turn(1, 0, 0, cy, sy, cx, sx), turn(0, 0, -1, cy, sy, cx, sx), turn(0, 0, 1, cy, sy, cx, sx)};
            float fw = patch < 0 ? 1f : (float) Math.min(1, Math.max(hx, hz) * 2), ou = patch < 0 ? 0 : (float) (hash(patch, 5) * (1 - fw));
            float ov = patch < 0 ? 0 : (float) (hash(patch, 9) * (1 - fw));
            for (int f = 0; f < 6; f++) {
                Vec3d a = v[faces[f][0]], b = v[faces[f][1]], cc = v[faces[f][2]], dd = v[faces[f][3]];
                if (sprite == null) {
                    quad(a, b, cc, dd, col[0] * lit * shades[f], col[1] * lit * shades[f], col[2] * lit * shades[f], 1f);
                } else {
                    // The pane's thin edges just take a sliver of the frame
                    boolean edge = patch < 0 && f >= 2;
                    texQuad(a, b, cc, dd, sprite, ou, ov, ou + fw, ov + (edge ? 1 / 16f : fw), shades[f] * 0.3f + 0.7f, normals[f]);
                }
            }
        }

        /** Turned about y, then about x. */
        private static Vec3d turn(double x, double y, double z, double cy, double sy, double cx, double sx) {
            double x1 = x * cy - z * sy, z1 = x * sy + z * cy;
            return new Vec3d(x1, y * cx - z1 * sx, y * sx + z1 * cx);
        }

        void square(Vec3d c, Vec3d right, Vec3d up, double size, float[] col, float alpha) {
            Vec3d x = right.multiply(size * 0.5), y = up.multiply(size * 0.5);
            quad(c.subtract(x).subtract(y), c.add(x).subtract(y), c.add(x).add(y), c.subtract(x).add(y),
                    col[0] * lit, col[1] * lit, col[2] * lit, alpha);
        }

        void quad(Vec3d a, Vec3d b, Vec3d c, Vec3d d, float r, float g, float bl, float alpha) {
            if (alpha <= 0.004f) return;
            v(a, r, g, bl, alpha);
            v(b, r, g, bl, alpha);
            v(c, r, g, bl, alpha);
            v(d, r, g, bl, alpha);
        }

        private void v(Vec3d p, float r, float g, float b, float a) {
            vc.vertex(pose, (float) p.x, (float) p.y, (float) p.z)
                    .color(MathHelper.clamp(r, 0f, 1f), MathHelper.clamp(g, 0f, 1f), MathHelper.clamp(b, 0f, 1f), MathHelper.clamp(a, 0f, 1f));
        }

        /** A textured quad showing the {@code u0..u1, v0..v1} fraction of {@code sprite}. */
        void texQuad(Vec3d a, Vec3d b, Vec3d c, Vec3d d, Sprite sprite, float u0, float v0, float u1, float v1, float shade, Vec3d n) {
            float U0 = sprite.getFrameU(u0), U1 = sprite.getFrameU(Math.min(1f, u1));
            float V0 = sprite.getFrameV(v0), V1 = sprite.getFrameV(Math.min(1f, v1));
            tv(a, U0, V1, shade, n);
            tv(b, U1, V1, shade, n);
            tv(c, U1, V0, shade, n);
            tv(d, U0, V0, shade, n);
        }

        private void tv(Vec3d p, float u, float v, float shade, Vec3d n) {
            vc.vertex(pose, (float) p.x, (float) p.y, (float) p.z)
                    .color(shade, shade, shade, 1f)
                    .texture(u, v)
                    .overlay(OverlayTexture.DEFAULT_UV)
                    .light(light)
                    .normal(entry, (float) n.x, (float) n.y, (float) n.z);
        }
    }

    @Override
    public Identifier getTexture(TornadoEntity tornado) {
        return PlayerScreenHandler.BLOCK_ATLAS_TEXTURE;
    }
}
