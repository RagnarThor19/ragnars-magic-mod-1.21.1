package net.ragnar.ragnarsmagicmod.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.ragnar.ragnarsmagicmod.network.PortalPayloads;
import net.ragnar.ragnarsmagicmod.util.PortalNetwork;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * How the Tome of Portals looks and sounds. Each portal is an oval window onto the End's starfield, the same
 * shimmering sky you see in an End gateway, framed in a glowing magenta or dark purple rim. A ring of enchanting-table
 * runes slowly turns around it, and more runes drift in off the air and sink into its face. A portal waiting
 * for its twin is smaller and its rim flickers; linking the pair flares them both.
 *
 * <p>All of it is drawn here, on the client, from the list of portals the server sends when it changes.
 */
public final class PortalClient {
    private PortalClient() {}

    private static final class Shown {
        PortalPayloads.View view;
        int age = 0;
        int closing = -1;
        int linkAge = 1000;
        final float seed;

        Shown(PortalPayloads.View view) {
            this.view = view;
            this.seed = (view.owner().hashCode() & 0xFFFF) / 997f + view.slot() * 3.1f;
        }
    }

    private static final Map<String, Shown> SHOWN = new LinkedHashMap<>();

    /** Additive light, like DimensionSplitClient's: overlapping glows add up. */
    private static final RenderLayer GLOW = RenderLayer.of("ragnarsmagicmod_portal_glow",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 16384, false, true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.LIGHTNING_TRANSPARENCY)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    private static final int SEGMENTS = 48;
    private static final int RUNE_COUNT = 18;
    private static final float OPEN_TICKS = 12f;
    private static final float CLOSE_TICKS = 8f;
    private static final double VIEW_DISTANCE = 160.0;
    private static final double PARTICLE_DISTANCE = 48.0;
    private static final String RUNES = "abcdefghijklmnopqrstuvwxyz";
    // The enchanting table's alphabet
    private static final Style RUNE_STYLE = Style.EMPTY.withFont(Identifier.ofVanilla("alt"));

    // Screen flash after coming through
    private static float flash = 0f;
    private static int flashColor = 0xFFFFFF;

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(PortalPayloads.Sync.ID, (payload, context) -> apply(payload));
        ClientPlayNetworking.registerGlobalReceiver(PortalPayloads.Traveled.ID, (payload, context) -> {
            flash = 1f;
            flashColor = payload.color();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            SHOWN.clear();
            flash = 0f;
        });
        ClientTickEvents.END_CLIENT_TICK.register(PortalClient::tick);
        WorldRenderEvents.AFTER_ENTITIES.register(PortalClient::render);
        HudRenderCallback.EVENT.register(PortalClient::renderHud);
    }

    private static void apply(PortalPayloads.Sync sync) {
        MinecraftClient client = MinecraftClient.getInstance();
        Set<String> present = new HashSet<>();
        for (PortalPayloads.View v : sync.portals()) {
            String key = v.owner() + ":" + v.slot();
            present.add(key);
            Shown old = SHOWN.get(key);
            if (old == null || old.closing >= 0 || !old.view.center().equals(v.center()) || !old.view.world().equals(v.world())) {
                Shown fresh = new Shown(v);
                if (sync.snapshot()) fresh.age = 1000;
                else burst(client, fresh, true);
                SHOWN.put(key, fresh);
            } else {
                if (!old.view.linked() && v.linked()) old.linkAge = 0;
                old.view = v;
            }
        }
        for (Map.Entry<String, Shown> e : SHOWN.entrySet()) {
            Shown s = e.getValue();
            if (!present.contains(e.getKey()) && s.closing < 0) {
                s.closing = 0;
                burst(client, s, false);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Shape
    // ---------------------------------------------------------------------

    private static Vec3d n(PortalPayloads.View v) { return Vec3d.of(v.normal().getVector()); }
    private static Vec3d u(PortalPayloads.View v) { return Vec3d.of(v.up().getVector()); }
    private static Vec3d r(PortalPayloads.View v) { return u(v).crossProduct(n(v)); }

    /** A point on the portal: {@code x}, {@code y} in -1..1 across its oval, {@code depth} off its face. */
    private static Vec3d point(PortalPayloads.View v, double x, double y, double depth) {
        return v.center().add(r(v).multiply(x * PortalNetwork.HALF_WIDTH)).add(u(v).multiply(y * PortalNetwork.HALF_HEIGHT))
                .add(n(v).multiply(depth));
    }

    private static boolean inWorld(ClientWorld world, PortalPayloads.View v) {
        return world != null && world.getRegistryKey().getValue().equals(v.world());
    }

    private static float[] rgb(int color) {
        return new float[]{(color >> 16 & 255) / 255f, (color >> 8 & 255) / 255f, (color & 255) / 255f};
    }

    private static float easeOutBack(float x) {
        float c1 = 1.9f, c3 = c1 + 1f, t = x - 1f;
        return 1f + c3 * t * t * t + c1 * t * t;
    }

    /** How big the portal is right now: springing open, snapping shut, or shrunk while it waits for its twin. */
    private static float size(Shown s, float tickDelta) {
        float age = s.age + tickDelta;
        float size = age >= OPEN_TICKS ? 1f : easeOutBack(Math.max(0f, age) / OPEN_TICKS);
        if (s.closing >= 0) {
            float c = MathHelper.clamp((s.closing + tickDelta) / CLOSE_TICKS, 0f, 1f);
            size *= 1f - c * c;
        }
        if (!s.view.linked()) size *= 0.82f + 0.03f * MathHelper.sin(age * 0.21f);
        else if (s.linkAge < 10) size *= 1f + 0.14f * MathHelper.sin((s.linkAge + tickDelta) / 10f * MathHelper.PI);
        return size;
    }

    /** The edge ripples a little, like it's liquid. */
    private static float wobble(float theta, float age, float seed) {
        return 1f + 0.025f * MathHelper.sin(3 * theta + age * 0.15f + seed)
                + 0.015f * MathHelper.sin(7 * theta - age * 0.23f + seed * 2f);
    }

    // ---------------------------------------------------------------------
    // Ticking: particles, sounds
    // ---------------------------------------------------------------------

    private static void tick(MinecraftClient client) {
        if (client.isPaused()) return;
        flash = Math.max(0f, flash - 0.1f);
        if (SHOWN.isEmpty()) return;

        Iterator<Shown> it = SHOWN.values().iterator();
        while (it.hasNext()) {
            Shown s = it.next();
            s.age++;
            s.linkAge++;
            if (s.closing >= 0 && ++s.closing > CLOSE_TICKS) it.remove();
        }

        ClientWorld world = client.world;
        if (world == null || client.player == null) return;
        Vec3d eye = client.player.getEyePos();
        for (Shown s : SHOWN.values()) {
            if (s.closing >= 0 || !inWorld(world, s.view)) continue;
            if (eye.squaredDistanceTo(s.view.center()) > PARTICLE_DISTANCE * PARTICLE_DISTANCE) continue;
            ambience(world, s, eye);
        }
    }

    private static void ambience(ClientWorld world, Shown s, Vec3d eye) {
        PortalPayloads.View v = s.view;
        Random rnd = world.random;
        Vec3d n = n(v), u = u(v), r = r(v);
        float[] c = rgb(PortalNetwork.COLORS[v.slot()]);
        boolean linked = v.linked();

        // Runes drifting in off the air and sinking into the portal
        for (int i = 0; i < (linked ? 2 : 1); i++) {
            double a = rnd.nextDouble() * MathHelper.TAU, rad = Math.sqrt(rnd.nextDouble()) * 0.8;
            Vec3d p = point(v, Math.cos(a) * rad, Math.sin(a) * rad, 0.05);
            Vec3d from = n.multiply(0.8 + rnd.nextDouble() * 1.2).add(r.multiply((rnd.nextDouble() - 0.5) * 1.6))
                    .add(u.multiply((rnd.nextDouble() - 0.5) * 2.4));
            world.addParticle(ParticleTypes.ENCHANT, p.x, p.y, p.z, from.x, from.y, from.z);
        }

        // Embers of its colour flaking off the rim
        if (rnd.nextFloat() < (linked ? 0.8f : 0.45f)) {
            double a = rnd.nextDouble() * MathHelper.TAU;
            Vec3d p = point(v, Math.cos(a) * 1.05, Math.sin(a) * 1.05, 0.08);
            world.addParticle(new DustParticleEffect(new Vector3f(c[0], c[1], c[2]), 0.7f + rnd.nextFloat() * 0.4f),
                    p.x, p.y, p.z, n.x * 0.02, n.y * 0.02, n.z * 0.02);
        }

        // Motes of light floating out of the starfield
        if (linked && rnd.nextFloat() < 0.1f) {
            double a = rnd.nextDouble() * MathHelper.TAU, rad = Math.sqrt(rnd.nextDouble()) * 0.7;
            Vec3d p = point(v, Math.cos(a) * rad, Math.sin(a) * rad, 0.1);
            world.addParticle(ParticleTypes.END_ROD, p.x, p.y, p.z, n.x * 0.02, n.y * 0.02, n.z * 0.02);
        }

        // A low hum when you're close
        if (linked && rnd.nextInt(110) == 0 && eye.squaredDistanceTo(v.center()) < 14 * 14) {
            world.playSound(v.center().x, v.center().y, v.center().z, SoundEvents.BLOCK_BEACON_AMBIENT,
                    SoundCategory.BLOCKS, 0.35f, 1.5f + rnd.nextFloat() * 0.2f, false);
        }
        if (rnd.nextInt(160) == 0 && eye.squaredDistanceTo(v.center()) < 10 * 10) {
            world.playSound(v.center().x, v.center().y, v.center().z, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME,
                    SoundCategory.BLOCKS, 0.5f, 0.6f + rnd.nextFloat() * 0.4f, false);
        }
    }

    /** The burst of a portal springing open or collapsing. */
    private static void burst(MinecraftClient client, Shown s, boolean opening) {
        ClientWorld world = client.world;
        if (world == null || client.player == null || !inWorld(world, s.view)) return;
        PortalPayloads.View v = s.view;
        if (client.player.getEyePos().squaredDistanceTo(v.center()) > 64 * 64) return;
        Random rnd = world.random;
        Vec3d n = n(v);
        float[] c = rgb(PortalNetwork.COLORS[v.slot()]);
        DustParticleEffect dust = new DustParticleEffect(new Vector3f(c[0], c[1], c[2]), 1.3f);

        for (int i = 0; i < 28; i++) {
            double a = MathHelper.TAU * i / 28;
            Vec3d p = point(v, Math.cos(a), Math.sin(a), 0.1);
            Vec3d out = p.subtract(v.center()).normalize().multiply(opening ? 0.25 : -0.15).add(n.multiply(0.05));
            world.addParticle(dust, p.x, p.y, p.z, out.x, out.y, out.z);
        }
        if (opening) {
            Vec3d p = point(v, 0, 0, 0.2);
            world.addParticle(ParticleTypes.FLASH, p.x, p.y, p.z, 0, 0, 0);
            for (int i = 0; i < 30; i++) {
                double a = rnd.nextDouble() * MathHelper.TAU, rad = Math.sqrt(rnd.nextDouble()) * 0.9;
                Vec3d q = point(v, Math.cos(a) * rad, Math.sin(a) * rad, 0.05);
                Vec3d from = n.multiply(1.5 + rnd.nextDouble() * 1.5)
                        .add(new Vec3d(rnd.nextDouble() - 0.5, rnd.nextDouble() - 0.5, rnd.nextDouble() - 0.5).multiply(2.5));
                world.addParticle(ParticleTypes.ENCHANT, q.x, q.y, q.z, from.x, from.y, from.z);
            }
        } else {
            for (int i = 0; i < 40; i++) {
                double a = rnd.nextDouble() * MathHelper.TAU, rad = Math.sqrt(rnd.nextDouble());
                Vec3d q = point(v, Math.cos(a) * rad, Math.sin(a) * rad, 0.1);
                world.addParticle(ParticleTypes.REVERSE_PORTAL, q.x, q.y, q.z,
                        n.x * 0.1 + (rnd.nextDouble() - 0.5) * 0.1, n.y * 0.1 + (rnd.nextDouble() - 0.5) * 0.1,
                        n.z * 0.1 + (rnd.nextDouble() - 0.5) * 0.1);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------

    private static void render(WorldRenderContext context) {
        if (SHOWN.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        if (world == null) return;
        float tickDelta = context.tickCounter().getTickDelta(false);
        Vec3d cam = context.camera().getPos();
        MatrixStack matrices = context.matrixStack() != null ? context.matrixStack() : new MatrixStack();
        matrices.push();
        Matrix4f m = matrices.peek().getPositionMatrix();
        VertexConsumerProvider.Immediate buffers = client.getBufferBuilders().getEntityVertexConsumers();

        java.util.List<Shown> visible = new java.util.ArrayList<>();
        for (Shown s : SHOWN.values()) {
            if (!inWorld(world, s.view) || cam.squaredDistanceTo(s.view.center()) > VIEW_DISTANCE * VIEW_DISTANCE) continue;
            if (size(s, tickDelta) > 0.01f) visible.add(s);
        }
        if (visible.isEmpty()) {
            matrices.pop();
            return;
        }

        // 1) The starfield window
        RenderLayer sky = RenderLayer.getEndGateway();
        VertexConsumer vc = buffers.getBuffer(sky);
        for (Shown s : visible) starfield(vc, m, s, cam, tickDelta);
        buffers.draw(sky);

        // 2) The ring of runes
        TextRenderer text = client.textRenderer;
        for (Shown s : visible) runes(text, buffers, m, s, cam, tickDelta);
        buffers.draw();

        // 3) Light: the colour bleeding in from the edge, the burning rim, its halo
        VertexConsumer glow = buffers.getBuffer(GLOW);
        for (Shown s : visible) glow(glow, m, s, cam, tickDelta);
        buffers.draw(GLOW);

        matrices.pop();
    }

    private static Vec3d oval(Vec3d base, Vec3d r, Vec3d u, float theta, float radius) {
        return base.add(r.multiply(MathHelper.cos(theta) * PortalNetwork.HALF_WIDTH * radius))
                .add(u.multiply(MathHelper.sin(theta) * PortalNetwork.HALF_HEIGHT * radius));
    }

    private static void starfield(VertexConsumer vc, Matrix4f m, Shown s, Vec3d cam, float tickDelta) {
        PortalPayloads.View v = s.view;
        float age = s.age + tickDelta, size = size(s, tickDelta);
        Vec3d n = n(v), u = u(v), r = r(v);
        Vec3d base = v.center().subtract(cam).add(n.multiply(0.015));
        for (int k = 0; k < SEGMENTS; k++) {
            float t0 = MathHelper.TAU * k / SEGMENTS, t1 = MathHelper.TAU * (k + 1) / SEGMENTS;
            Vec3d p0 = oval(base, r, u, t0, size * wobble(t0, age, s.seed));
            Vec3d p1 = oval(base, r, u, t1, size * wobble(t1, age, s.seed));
            // Both windings, so it shows from behind too when it's hanging in the air
            vertex(vc, m, base); vertex(vc, m, p0); vertex(vc, m, p1); vertex(vc, m, p1);
            vertex(vc, m, base); vertex(vc, m, p1); vertex(vc, m, p0); vertex(vc, m, p0);
        }
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, Vec3d p) {
        vc.vertex(m, (float) p.x, (float) p.y, (float) p.z);
    }

    private static void runes(TextRenderer text, VertexConsumerProvider buffers, Matrix4f m, Shown s, Vec3d cam, float tickDelta) {
        PortalPayloads.View v = s.view;
        float age = s.age + tickDelta, size = size(s, tickDelta);
        Vec3d n = n(v), u = u(v), r = r(v);
        Vec3d base = v.center().subtract(cam).add(n.multiply(0.03));
        int rgb = PortalNetwork.COLORS[v.slot()];
        boolean linked = v.linked();

        // The two portals of a pair turn opposite ways; a waiting one turns slowly
        float spin = age * (linked ? 0.012f : 0.004f) * (v.slot() == 0 ? 1f : -1f);
        double a = PortalNetwork.HALF_WIDTH * size + 0.3, b = PortalNetwork.HALF_HEIGHT * size + 0.3;
        float scale = 0.02f * Math.min(1f, size);
        int shift = (int) (s.seed * 7);

        for (int i = 0; i < RUNE_COUNT; i++) {
            double theta = MathHelper.TAU * i / RUNE_COUNT + spin;
            double ct = Math.cos(theta), st = Math.sin(theta);
            Vec3d pos = base.add(r.multiply(a * ct)).add(u.multiply(b * st));
            // Along the ring, and outwards from the middle
            Vec3d along = r.multiply(-a * st).add(u.multiply(b * ct)).normalize();
            Vec3d out = r.multiply(b * ct).add(u.multiply(a * st)).normalize();

            float shimmer = MathHelper.sin(age * 0.08f + i * 1.3f);
            float alpha = (linked ? 0.55f + 0.45f * shimmer * shimmer : 0.3f + 0.25f * shimmer * shimmer) * Math.min(1f, size);
            if (alpha < 0.12f) continue;
            int color = (MathHelper.clamp((int) (alpha * 255), 0, 255) << 24) | rgb;
            Text rune = Text.literal(String.valueOf(RUNES.charAt(Math.floorMod(i * 7 + shift, RUNES.length())))).setStyle(RUNE_STYLE);
            float x = -text.getWidth(rune) / 2f;

            // One copy facing each way (text is only drawn from the front)
            for (int side = -1; side <= 1; side += 2) {
                Matrix4f glyph = new Matrix4f(m).mul(new Matrix4f(
                        (float) (along.x * scale * side), (float) (along.y * scale * side), (float) (along.z * scale * side), 0f,
                        (float) (-out.x * scale), (float) (-out.y * scale), (float) (-out.z * scale), 0f,
                        (float) (n.x * side), (float) (n.y * side), (float) (n.z * side), 0f,
                        (float) pos.x, (float) pos.y, (float) pos.z, 1f));
                text.draw(rune, x, -3.5f, color, false, glyph, buffers, TextRenderer.TextLayerType.NORMAL, 0,
                        LightmapTextureManager.MAX_LIGHT_COORDINATE);
            }
        }
    }

    private static void glow(VertexConsumer vc, Matrix4f m, Shown s, Vec3d cam, float tickDelta) {
        PortalPayloads.View v = s.view;
        float age = s.age + tickDelta, size = size(s, tickDelta);
        Vec3d n = n(v), u = u(v), r = r(v);
        float[] c = rgb(PortalNetwork.COLORS[v.slot()]);

        // Steady and breathing when linked; a waiting portal gutters like a candle
        float bright = v.linked()
                ? 0.85f + 0.15f * MathHelper.sin(age * 0.12f + s.seed)
                : 0.5f + 0.25f * MathHelper.sin(age * 1.7f + s.seed) * MathHelper.sin(age * 0.37f);
        float wr = 0.6f * c[0] + 0.4f, wg = 0.6f * c[1] + 0.4f, wb = 0.6f * c[2] + 0.4f; // white-hot

        for (double depth : new double[]{0.02, -0.02}) {
            Vec3d base = v.center().subtract(cam).add(n.multiply(depth));
            // Colour bleeding in from the edge over the stars
            ring(vc, m, base, r, u, s, age, size, 0.3f, 0.97f, c[0], c[1], c[2], 0f, c[0], c[1], c[2], 0.6f * bright);
        }
        Vec3d base = v.center().subtract(cam).add(n.multiply(0.025));
        // The rim: white-hot inside, its colour outside
        ring(vc, m, base, r, u, s, age, size, 0.93f, 1.0f, wr, wg, wb, 0f, wr, wg, wb, bright);
        ring(vc, m, base, r, u, s, age, size, 1.0f, 1.07f, wr, wg, wb, bright, c[0], c[1], c[2], 0.8f * bright);
        // The halo round it
        ring(vc, m, base, r, u, s, age, size, 1.07f, 1.55f, c[0], c[1], c[2], 0.5f * bright, c[0], c[1], c[2], 0f);

        // Flare as it opens or links up
        float flare = Math.max(1f - age / OPEN_TICKS, 1f - (s.linkAge + tickDelta) / 16f);
        if (s.closing >= 0) flare = Math.max(flare, 1f - (s.closing + tickDelta) / CLOSE_TICKS);
        if (flare > 0f) {
            ring(vc, m, base, r, u, s, age, Math.max(size, 0.5f), 0.9f, 2.4f, 1f, 1f, 1f, 0.8f * flare, c[0], c[1], c[2], 0f);
        }
    }

    /** A band around the oval from {@code inner} to {@code outer} times its size, shading between two colours. */
    private static void ring(VertexConsumer vc, Matrix4f m, Vec3d base, Vec3d r, Vec3d u, Shown s, float age, float size,
                             float inner, float outer,
                             float r0, float g0, float b0, float a0, float r1, float g1, float b1, float a1) {
        for (int k = 0; k < SEGMENTS; k++) {
            float t0 = MathHelper.TAU * k / SEGMENTS, t1 = MathHelper.TAU * (k + 1) / SEGMENTS;
            float w0 = size * wobble(t0, age, s.seed), w1 = size * wobble(t1, age, s.seed);
            Vec3d i0 = oval(base, r, u, t0, w0 * inner), i1 = oval(base, r, u, t1, w1 * inner);
            Vec3d o0 = oval(base, r, u, t0, w0 * outer), o1 = oval(base, r, u, t1, w1 * outer);
            vc.vertex(m, (float) i0.x, (float) i0.y, (float) i0.z).color(r0, g0, b0, a0);
            vc.vertex(m, (float) i1.x, (float) i1.y, (float) i1.z).color(r0, g0, b0, a0);
            vc.vertex(m, (float) o1.x, (float) o1.y, (float) o1.z).color(r1, g1, b1, a1);
            vc.vertex(m, (float) o0.x, (float) o0.y, (float) o0.z).color(r1, g1, b1, a1);
        }
    }

    // ---------------------------------------------------------------------
    // On screen
    // ---------------------------------------------------------------------

    private static void renderHud(DrawContext context, RenderTickCounter counter) {
        if (flash <= 0f) return;
        float f = Math.max(0f, flash - counter.getTickDelta(false) * 0.1f);
        int alpha = MathHelper.clamp((int) (f * f * 0.45f * 255), 0, 255);
        if (alpha == 0) return;
        context.fill(0, 0, context.getScaledWindowWidth(), context.getScaledWindowHeight(), (alpha << 24) | flashColor);
    }
}
