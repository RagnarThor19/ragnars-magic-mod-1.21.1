package net.ragnar.ragnarsmagicmod.blackhole.client;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
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
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.blackhole.BlackHoleEntity;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Draws the black hole, almost all of it out of real blocks:
 * <ul>
 *   <li><b>The event horizon</b>: a sphere of pure black, with a soft shadow around it that darkens whatever is
 *       behind.</li>
 *   <li><b>The photon ring</b>: a thin, blinding ring of white blocks hugging the sphere, always facing the viewer,
 *       with a looser halo of orange streaks circling just outside it (the disk's far side, bent round by gravity).</li>
 *   <li><b>The accretion disk</b>: a couple of hundred blocks orbiting in a tilted, wobbling disk, inner ones whipping
 *       round far faster than outer ones, stretched into streaks by their speed. White-hot froglights and concrete on
 *       the inside cool through glowstone, shroomlight and magma to tumbling chunks of obsidian and crying obsidian at
 *       the rim.</li>
 *   <li><b>The jets</b>: from both poles, tapering beams of froglight sheathed in purple glass, with knots of
 *       amethyst spiralling out along them.</li>
 *   <li><b>The debris</b>: chunks of the real ground nearby, ripped loose, spiralling in and stretched thin as they
 *       go.</li>
 * </ul>
 * While it forms the sphere swells from a pinpoint and the disk spins up from the outside in; when it collapses
 * everything spirals into the middle, and when it bursts the disk is blown outward behind a ring of light.
 */
public class BlackHoleRenderer extends EntityRenderer<BlackHoleEntity> {

    /** Flat colour, opaque, writes depth: the black of the event horizon. */
    static final RenderLayer VOID = RenderLayer.of("ragnarsmagicmod_black_hole_void",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 8192, false, false,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.NO_TRANSPARENCY)
                    .writeMaskState(RenderPhase.ALL_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    /** Flat colour, see-through, no depth: the shadow round the horizon. */
    static final RenderLayer SHADOW = RenderLayer.of("ragnarsmagicmod_black_hole_shadow",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 8192, false, true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.TRANSLUCENT_TRANSPARENCY)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    private static final int FULL = LightmapTextureManager.MAX_LIGHT_COORDINATE;
    private static final float R = BlackHoleEntity.HORIZON;
    private static final float DISK_IN = R * 1.38f, DISK_OUT = R * 4.8f;
    /** Spin of the innermost orbit, radians a tick; outer ones go slower, as real orbits do (Kepler). */
    private static final float SPIN = 0.27f;
    private static final float JET_LENGTH = 10f;

    /**
     * One block of the disk: its orbit (radius, starting angle, speed, height off the disk, tilt), how big it is,
     * when it joins while the hole forms, and how it's lit ({@code -1}: by the world, but never too dark).
     */
    private record Bit(BlockState state, float r, float theta0, float omega, float h, float incl, float size,
                       float appear, int light, boolean chunk, float spinX, float spinY, float spinZ) {}

    private static final Bit[] DISK = buildDisk();
    private static final BlockState[] JET_KNOTS = {
            Blocks.AMETHYST_BLOCK.getDefaultState(), Blocks.PEARLESCENT_FROGLIGHT.getDefaultState(),
            Blocks.PURPLE_CONCRETE.getDefaultState(), Blocks.MAGENTA_CONCRETE.getDefaultState(),
            Blocks.WHITE_CONCRETE.getDefaultState(), Blocks.AMETHYST_BLOCK.getDefaultState()};
    private static final BlockState[] HALO = {
            Blocks.OCHRE_FROGLIGHT.getDefaultState(), Blocks.ORANGE_CONCRETE.getDefaultState(),
            Blocks.YELLOW_CONCRETE.getDefaultState(), Blocks.SHROOMLIGHT.getDefaultState(), Blocks.WHITE_CONCRETE.getDefaultState()};

    private static BlockState pick(Random rnd, BlockState... options) {
        return options[rnd.nextInt(options.length)];
    }

    private static Bit[] buildDisk() {
        Random rnd = new Random(0xB1AC_401EL);
        List<Bit> bits = new ArrayList<>();
        for (int i = 0; i < 210; i++) {
            float u = rnd.nextFloat();
            float r = DISK_IN + (DISK_OUT - DISK_IN) * (float) Math.pow(u, 1.35);
            float n = (r - DISK_IN) / (DISK_OUT - DISK_IN);
            BlockState state;
            int light;
            float size;
            boolean chunk = false;
            if (n < 0.12f) {
                state = pick(rnd, Blocks.WHITE_CONCRETE.getDefaultState(), Blocks.PEARLESCENT_FROGLIGHT.getDefaultState(), Blocks.WHITE_CONCRETE.getDefaultState());
                light = FULL;
                size = 0.09f + rnd.nextFloat() * 0.05f;
            } else if (n < 0.3f) {
                state = pick(rnd, Blocks.OCHRE_FROGLIGHT.getDefaultState(), Blocks.YELLOW_CONCRETE.getDefaultState(), Blocks.GLOWSTONE.getDefaultState());
                light = FULL;
                size = 0.12f + rnd.nextFloat() * 0.07f;
            } else if (n < 0.5f) {
                state = pick(rnd, Blocks.SHROOMLIGHT.getDefaultState(), Blocks.ORANGE_CONCRETE.getDefaultState(), Blocks.OCHRE_FROGLIGHT.getDefaultState());
                light = FULL;
                size = 0.14f + rnd.nextFloat() * 0.08f;
            } else if (n < 0.7f) {
                state = pick(rnd, Blocks.MAGMA_BLOCK.getDefaultState(), Blocks.ORANGE_CONCRETE.getDefaultState(),
                        Blocks.RED_CONCRETE.getDefaultState(), Blocks.CRYING_OBSIDIAN.getDefaultState());
                light = LightmapTextureManager.pack(13, 13);
                size = 0.16f + rnd.nextFloat() * 0.1f;
            } else {
                state = pick(rnd, Blocks.OBSIDIAN.getDefaultState(), Blocks.CRYING_OBSIDIAN.getDefaultState(),
                        Blocks.BLACKSTONE.getDefaultState(), Blocks.AMETHYST_BLOCK.getDefaultState(), Blocks.MAGMA_BLOCK.getDefaultState());
                light = -1;
                size = 0.2f + rnd.nextFloat() * 0.22f;
                chunk = true;
            }
            float omega = SPIN * (float) Math.pow(DISK_IN / r, 1.5);
            float h = (float) rnd.nextGaussian() * (0.03f + 0.05f * n) * r;
            float incl = rnd.nextFloat() < 0.1f ? (rnd.nextFloat() - 0.5f) * 1.1f : 0f;
            // Outer matter gathers first, the white-hot inside last
            float appear = 0.04f + 0.6f * (1f - n) + rnd.nextFloat() * 0.12f;
            bits.add(new Bit(state, r, rnd.nextFloat() * MathHelper.TAU, omega, h, incl, size, appear, light, chunk,
                    (rnd.nextFloat() - 0.5f) * 0.3f, (rnd.nextFloat() - 0.5f) * 0.3f, (rnd.nextFloat() - 0.5f) * 0.3f));
        }
        return bits.toArray(new Bit[0]);
    }

    public BlackHoleRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
        shadowRadius = 0f;
    }

    @Override
    public void render(BlackHoleEntity hole, float yaw, float tickDelta, MatrixStack ms, VertexConsumerProvider buffers, int light) {
        BlockRenderManager blocks = MinecraftClient.getInstance().getBlockRenderManager();
        float time = hole.age + tickDelta;
        int stage = hole.stage();
        float st = hole.stageTime(tickDelta);
        float form = hole.formed(tickDelta);
        float collapse = stage == BlackHoleEntity.COLLAPSING ? MathHelper.clamp(st / BlackHoleEntity.COLLAPSE_TICKS, 0f, 1f)
                : stage == BlackHoleEntity.BURST ? 1f : 0f;
        float burst = stage == BlackHoleEntity.BURST ? MathHelper.clamp(st / BlackHoleEntity.BURST_TICKS, 0f, 1f) : 0f;
        boolean forming = stage == BlackHoleEntity.FORMING;
        boolean bursting = stage == BlackHoleEntity.BURST;
        int worldLight = LightmapTextureManager.pack(Math.max(LightmapTextureManager.getBlockLightCoordinates(light), 8),
                LightmapTextureManager.getSkyLightCoordinates(light));

        // How big the sphere is: swells from a pinpoint, breathes, crushes down to a point as it collapses
        float core;
        if (forming) core = 0.04f + 0.96f * smooth(MathHelper.clamp(form / 0.8f, 0f, 1f)) * (1f + 0.05f * MathHelper.sin(time * 0.9f) * (1f - form));
        else if (stage == BlackHoleEntity.DRIFTING) core = 1f + 0.025f * MathHelper.sin(time * 0.25f);
        else core = 1f - 0.9f * (float) Math.pow(collapse, 1.5);

        // In collapse the whole disk speeds up as it falls in; this is the extra ticks of spin it gains
        float warp = 45f * collapse * collapse;

        ms.push();
        ms.translate(0f, hole.getHeight() / 2f, 0f);

        // The debris, in the world's frame
        Vec3d centre = hole.getLerpedPos(tickDelta).add(0, hole.getHeight() / 2.0, 0);
        for (BlackHoleEntity.Debris d : hole.debris) debris(d, time, centre, ms, buffers, blocks, worldLight);

        if (!bursting) {
            Quaternionf facing = dispatcher.getRotation();
            shadow(ms, buffers, facing, R * core);
            sphere(ms, buffers, R * core);
            photonRing(ms, buffers, blocks, facing, time, core);
            lensedHalo(ms, buffers, blocks, facing, time, core, form);
        }

        // The disk and the jets share a frame: tilted, slowly precessing and wobbling
        Quaternionf tilt = new Quaternionf()
                .rotationY(hole.getId() * 1.3f + time * 0.006f)
                .rotateX((float) Math.toRadians(22 + 5 * Math.sin(time * 0.017)))
                .rotateZ((float) Math.toRadians(6 * Math.sin(time * 0.011)));
        ms.push();
        ms.multiply(tilt);
        disk(ms, buffers, blocks, time, warp, form, forming, collapse, burst, worldLight);
        if (!bursting) {
            float jet = forming ? smooth(MathHelper.clamp((form - 0.55f) / 0.35f, 0f, 1f)) : 1f - collapse;
            if (jet > 0.01f) {
                jet(ms, buffers, blocks, time, jet, 1);
                jet(ms, buffers, blocks, time, jet, -1);
            }
        } else {
            shockwave(ms, buffers, blocks, time, burst);
        }
        ms.pop();

        if (bursting && burst < 0.3f) flash(ms, buffers, blocks, burst / 0.3f);

        ms.pop();
        super.render(hole, yaw, tickDelta, ms, buffers, light);
    }

    // ---------------------------------------------------------------------
    // The horizon
    // ---------------------------------------------------------------------

    /** A sphere of perfect black. */
    private static void sphere(MatrixStack ms, VertexConsumerProvider buffers, float radius) {
        if (radius < 0.01f) return;
        VertexConsumer vc = buffers.getBuffer(VOID);
        Matrix4f pose = ms.peek().getPositionMatrix();
        int lat = 16, lon = 28;
        for (int i = 0; i < lat; i++) {
            float a0 = MathHelper.PI * i / lat - MathHelper.HALF_PI, a1 = MathHelper.PI * (i + 1) / lat - MathHelper.HALF_PI;
            float y0 = MathHelper.sin(a0) * radius, y1 = MathHelper.sin(a1) * radius;
            float r0 = MathHelper.cos(a0) * radius, r1 = MathHelper.cos(a1) * radius;
            for (int j = 0; j < lon; j++) {
                float b0 = MathHelper.TAU * j / lon, b1 = MathHelper.TAU * (j + 1) / lon;
                float c0 = MathHelper.cos(b0), s0 = MathHelper.sin(b0), c1 = MathHelper.cos(b1), s1 = MathHelper.sin(b1);
                black(vc, pose, r0 * c0, y0, r0 * s0, 1f);
                black(vc, pose, r0 * c1, y0, r0 * s1, 1f);
                black(vc, pose, r1 * c1, y1, r1 * s1, 1f);
                black(vc, pose, r1 * c0, y1, r1 * s0, 1f);
            }
        }
    }

    /** A soft darkness round the horizon, facing the viewer: deepest at the edge, fading out over another radius. */
    private static void shadow(MatrixStack ms, VertexConsumerProvider buffers, Quaternionf facing, float radius) {
        if (radius < 0.01f) return;
        ms.push();
        ms.multiply(facing);
        VertexConsumer vc = buffers.getBuffer(SHADOW);
        Matrix4f pose = ms.peek().getPositionMatrix();
        float[] rings = {0.97f, 1.25f, 1.7f, 2.4f};
        float[] alpha = {0.95f, 0.7f, 0.32f, 0f};
        int seg = 48;
        for (int k = 0; k < rings.length - 1; k++) {
            float ri = rings[k] * radius, ro = rings[k + 1] * radius;
            for (int j = 0; j < seg; j++) {
                float b0 = MathHelper.TAU * j / seg, b1 = MathHelper.TAU * (j + 1) / seg;
                float c0 = MathHelper.cos(b0), s0 = MathHelper.sin(b0), c1 = MathHelper.cos(b1), s1 = MathHelper.sin(b1);
                black(vc, pose, ri * c0, ri * s0, 0, alpha[k]);
                black(vc, pose, ro * c0, ro * s0, 0, alpha[k + 1]);
                black(vc, pose, ro * c1, ro * s1, 0, alpha[k + 1]);
                black(vc, pose, ri * c1, ri * s1, 0, alpha[k]);
            }
        }
        ms.pop();
    }

    private static void black(VertexConsumer vc, Matrix4f pose, float x, float y, float z, float a) {
        vc.vertex(pose, x, y, z).color(0f, 0f, 0f, a);
    }

    /** The photon ring: a thin unbroken ring of white light right at the horizon's edge. */
    private void photonRing(MatrixStack ms, VertexConsumerProvider buffers, BlockRenderManager blocks, Quaternionf facing, float time, float core) {
        if (core < 0.05f) return;
        int n = 64;
        float radius = R * 1.055f * core;
        float seg = MathHelper.TAU * radius / n * 1.5f;
        ms.push();
        ms.multiply(facing);
        for (int i = 0; i < n; i++) {
            float a = MathHelper.TAU * i / n + time * 0.04f;
            float flicker = 0.8f + 0.35f * MathHelper.sin(time * 0.7f + i * 2.3f);
            float w = 0.065f * flicker * Math.max(core, 0.4f);
            ms.push();
            ms.multiply(new Quaternionf().rotationZ(a));
            ms.translate(radius, 0f, 0f);
            ms.scale(w, seg, w);
            ms.translate(-0.5f, -0.5f, -0.5f);
            BlockState s = i % 3 == 0 ? Blocks.PEARLESCENT_FROGLIGHT.getDefaultState() : Blocks.WHITE_CONCRETE.getDefaultState();
            blocks.renderBlockAsEntity(s, ms, buffers, FULL, OverlayTexture.DEFAULT_UV);
            ms.pop();
        }
        ms.pop();
    }

    /** The disk's far side seen bent round the hole: orange streaks circling just outside the photon ring. */
    private void lensedHalo(MatrixStack ms, VertexConsumerProvider buffers, BlockRenderManager blocks, Quaternionf facing,
                            float time, float core, float form) {
        if (core < 0.05f) return;
        int n = 44;
        ms.push();
        ms.multiply(facing);
        for (int i = 0; i < n; i++) {
            float k = (i * 0.618034f) % 1f;
            if (form < 0.35f + 0.5f * k) continue;
            float radius = R * (1.2f + 0.45f * k) * core;
            float speed = 0.16f * (float) Math.pow(1.2f / (1.2f + 0.45f * k), 1.5);
            float a = i * 2.39996f + time * speed;
            float len = 0.22f + 0.5f * (1 - k);
            float w = (0.05f + 0.05f * k) * Math.max(core, 0.4f);
            ms.push();
            ms.multiply(new Quaternionf().rotationZ(a));
            ms.translate(radius, 0f, 0f);
            ms.scale(w, len, w);
            ms.translate(-0.5f, -0.5f, -0.5f);
            blocks.renderBlockAsEntity(HALO[i % HALO.length], ms, buffers, FULL, OverlayTexture.DEFAULT_UV);
            ms.pop();
        }
        ms.pop();
    }

    // ---------------------------------------------------------------------
    // The disk and the jets
    // ---------------------------------------------------------------------

    private void disk(MatrixStack ms, VertexConsumerProvider buffers, BlockRenderManager blocks, float time, float warp,
                      float form, boolean forming, float collapse, float burst, int worldLight) {
        float fall = 1f - 0.93f * (float) Math.pow(collapse, 1.6);
        float out = easeOut(burst);
        for (Bit b : DISK) {
            float pop = 1f;
            float r = b.r() * fall;
            if (forming) {
                pop = MathHelper.clamp((form - b.appear()) / 0.16f, 0f, 1f);
                if (pop <= 0f) continue;
                // Falls in from further out and settles into its orbit
                float in = 1f - easeOut(pop);
                r *= 1f + 1.3f * in * in;
            }
            float size = b.size() * smooth(pop) * (1f - 0.45f * collapse);
            float h = b.h();
            if (burst > 0f) {
                r += out * (5f + b.r() * 2.2f);
                h *= 1f + 8f * out;
                size *= (float) Math.pow(1f - burst, 0.7);
            }
            if (size < 0.005f) continue;
            float th = b.theta0() + b.omega() * (time + warp);
            float x = r * MathHelper.cos(th), z = r * MathHelper.sin(th), y = h;
            if (b.incl() != 0f) {
                y += z * MathHelper.sin(b.incl());
                z *= MathHelper.cos(b.incl());
            }
            ms.push();
            ms.translate(x, y, z);
            ms.multiply(new Quaternionf().rotationY(-(th + MathHelper.HALF_PI)));
            if (b.chunk()) {
                float spin = time + warp;
                ms.multiply(new Quaternionf().rotationXYZ(b.spinX() * spin, b.spinY() * spin, b.spinZ() * spin));
                ms.scale(size, size, size);
            } else {
                // Stretched along its orbit by its speed: the fast inner ones blur into streaks
                float stretch = 1f + Math.min(3.2f, b.omega() * 9f * (1f + 2.5f * collapse));
                ms.scale(size * stretch, size * 0.55f, size * 0.8f);
            }
            ms.translate(-0.5f, -0.5f, -0.5f);
            blocks.renderBlockAsEntity(b.state(), ms, buffers, b.light() == -1 ? worldLight : b.light(), OverlayTexture.DEFAULT_UV);
            ms.pop();
        }
    }

    /** One jet, up ({@code dir} 1) or down (-1) the disk's axis, {@code power} 0..1 of its full length. */
    private void jet(MatrixStack ms, VertexConsumerProvider buffers, BlockRenderManager blocks, float time, float power, int dir) {
        float len = JET_LENGTH * power;
        float base = R * 0.85f;
        ms.push();
        ms.multiply(new Quaternionf().rotationY(time * 0.15f * dir));
        // Beam: a white-hot core in a sheath of glass, both tapering away
        float[] coreW = {0.2f, 0.14f, 0.08f, 0.04f};
        float[] glassW = {0.62f, 0.46f, 0.3f, 0.16f};
        int pieces = coreW.length;
        float pulse = 1f + 0.12f * MathHelper.sin(time * 0.8f + dir);
        for (int i = 0; i < pieces; i++) {
            float y0 = base + len * i / pieces, seg = len / pieces;
            column(ms, buffers, blocks, Blocks.PEARLESCENT_FROGLIGHT.getDefaultState(), y0, seg, coreW[i] * pulse * power, dir);
            column(ms, buffers, blocks, i % 2 == 0 ? Blocks.PURPLE_STAINED_GLASS.getDefaultState() : Blocks.MAGENTA_STAINED_GLASS.getDefaultState(),
                    y0 - 0.05f, seg * 0.85f, glassW[i] * pulse * power, dir);
        }
        // Knots of matter shot out along it, spiralling
        int n = 22;
        for (int i = 0; i < n; i++) {
            float p = ((float) i / n + time * 0.032f) % 1f;
            float y = (base + p * len) * dir;
            float helix = 0.1f + 0.45f * p;
            float a = i * 2.4f + time * 0.35f;
            float size = (0.3f * (1f - p) + 0.05f) * power;
            ms.push();
            ms.translate(MathHelper.cos(a) * helix, y, MathHelper.sin(a) * helix);
            ms.multiply(new Quaternionf().rotationXYZ(time * 0.2f + i, time * 0.13f, i * 0.7f));
            ms.scale(size, size, size);
            ms.translate(-0.5f, -0.5f, -0.5f);
            blocks.renderBlockAsEntity(JET_KNOTS[i % JET_KNOTS.length], ms, buffers, FULL, OverlayTexture.DEFAULT_UV);
            ms.pop();
        }
        ms.pop();
    }

    private static void column(MatrixStack ms, VertexConsumerProvider buffers, BlockRenderManager blocks, BlockState state,
                               float from, float length, float width, int dir) {
        if (width < 0.005f || length < 0.01f) return;
        ms.push();
        ms.translate(0f, dir > 0 ? from : -from - length, 0f);
        ms.scale(width, length, width);
        ms.translate(-0.5f, 0f, -0.5f);
        blocks.renderBlockAsEntity(state, ms, buffers, FULL, OverlayTexture.DEFAULT_UV);
        ms.pop();
    }

    // ---------------------------------------------------------------------
    // Debris and the burst
    // ---------------------------------------------------------------------

    /** A chunk of ground falling in: tumbling at first, then stretched out thin toward the hole (spaghettified). */
    private static void debris(BlackHoleEntity.Debris d, float time, Vec3d centre, MatrixStack ms, VertexConsumerProvider buffers,
                               BlockRenderManager blocks, int light) {
        float t = d.progress(time);
        Vec3d o = d.offset(time, centre);
        float size = d.size * (1f - 0.5f * t) * MathHelper.clamp(t * 8f + 0.25f, 0f, 1f);
        float pull = (float) Math.pow(MathHelper.clamp((t - 0.45f) / 0.55f, 0f, 1f), 2);
        ms.push();
        ms.translate(o.x, o.y, o.z);
        if (pull > 0f && o.lengthSquared() > 1e-4) {
            Vector3f to = new Vector3f((float) -o.x, (float) -o.y, (float) -o.z).normalize();
            ms.multiply(new Quaternionf().rotationTo(new Vector3f(0, 1, 0), to));
        }
        float spin = d.spin(time) * (1f - pull);
        ms.multiply(new Quaternionf().rotationXYZ((float) Math.toRadians(d.spinX * spin), (float) Math.toRadians(d.spinY * spin),
                (float) Math.toRadians(d.spinZ * spin)));
        float thin = 1f - 0.6f * pull;
        ms.scale(size * thin, size * (1f + 3.5f * pull), size * thin);
        ms.translate(-0.5f, -0.5f, -0.5f);
        blocks.renderBlockAsEntity(d.state, ms, buffers, light, OverlayTexture.DEFAULT_UV);
        ms.pop();
    }

    /** The burst: a ring of light racing outward through the disk's plane, and a thinner one across it. */
    private void shockwave(MatrixStack ms, VertexConsumerProvider buffers, BlockRenderManager blocks, float time, float burst) {
        float out = easeOut(burst);
        float size = 0.8f * (1f - burst) + 0.04f;
        ring(ms, buffers, blocks, 80, R + out * 16f, size, time, 0);
        ms.push();
        ms.multiply(new Quaternionf().rotationX(MathHelper.HALF_PI));
        ring(ms, buffers, blocks, 56, R + out * 10f, size * 0.7f, time, 1);
        ms.pop();
    }

    private static void ring(MatrixStack ms, VertexConsumerProvider buffers, BlockRenderManager blocks, int n, float radius,
                             float size, float time, int salt) {
        float seg = MathHelper.TAU * radius / n;
        for (int i = 0; i < n; i++) {
            float a = MathHelper.TAU * i / n + time * 0.02f;
            ms.push();
            ms.translate(radius * MathHelper.cos(a), 0f, radius * MathHelper.sin(a));
            ms.multiply(new Quaternionf().rotationY(-(a + MathHelper.HALF_PI)));
            ms.scale(Math.max(size, seg * 0.9f), size * 0.6f, size);
            ms.translate(-0.5f, -0.5f, -0.5f);
            BlockState s = (i + salt) % 4 == 0 ? Blocks.AMETHYST_BLOCK.getDefaultState()
                    : (i + salt) % 2 == 0 ? Blocks.PEARLESCENT_FROGLIGHT.getDefaultState() : Blocks.WHITE_CONCRETE.getDefaultState();
            blocks.renderBlockAsEntity(s, ms, buffers, FULL, OverlayTexture.DEFAULT_UV);
            ms.pop();
        }
    }

    /** The first instant of the burst: a blinding star where the hole was, swelling and gone. */
    private static void flash(MatrixStack ms, VertexConsumerProvider buffers, BlockRenderManager blocks, float k) {
        float s = R * (0.6f + 3.2f * easeOut(k)) * (1f - k);
        if (s < 0.01f) return;
        Quaternionf[] turns = {new Quaternionf(), new Quaternionf().rotationXYZ(0.785f, 0.615f, 0f),
                new Quaternionf().rotationXYZ(0f, 0.785f, 0.785f)};
        for (int i = 0; i < turns.length; i++) {
            ms.push();
            ms.multiply(turns[i]);
            ms.scale(s, s, s);
            ms.translate(-0.5f, -0.5f, -0.5f);
            blocks.renderBlockAsEntity(i == 0 ? Blocks.WHITE_CONCRETE.getDefaultState() : Blocks.PEARLESCENT_FROGLIGHT.getDefaultState(),
                    ms, buffers, FULL, OverlayTexture.DEFAULT_UV);
            ms.pop();
        }
    }

    // ---------------------------------------------------------------------

    private static float smooth(float t) {
        return t * t * (3f - 2f * t);
    }

    private static float easeOut(float t) {
        return 1f - (1f - t) * (1f - t);
    }

    @Override
    public Identifier getTexture(BlackHoleEntity hole) {
        return PlayerScreenHandler.BLOCK_ATLAS_TEXTURE;
    }
}
