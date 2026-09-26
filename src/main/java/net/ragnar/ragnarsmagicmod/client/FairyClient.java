package net.ragnar.ragnarsmagicmod.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MovementType;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.ragnar.ragnarsmagicmod.network.FairyPayload;
import net.ragnar.ragnarsmagicmod.util.FairyForm;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/**
 * Tome of the Fairy, client side.
 *
 * Flight (your own fairy): momentum-based and a little slippery. W thrusts where you look (look up to climb, down to
 * dive), S brakes, A/D drift sideways, Space flutters upward, Shift sinks. There's light gravity and a bit of
 * turbulence, and you keep drifting after you let go - easy to fly, takes practice to fly well.
 *
 * Looks: the player is hidden and drawn as a little solid white block turning inside a faint glowing cube, with
 * four fluttering pixel-art wings, bobbing as it flies and trailing white sparkles. Your own view switches to third person while you're a fairy. In the last three
 * seconds the fairy flickers faster and faster, and your timer under the crosshair turns red.
 */
public final class FairyClient {
    private FairyClient() {}

    // Flight tuning (per tick)
    private static final double THRUST = 0.032;
    private static final double BRAKE = 0.02;
    private static final double STRAFE = 0.016;
    private static final double LIFT = 0.03;
    private static final double SINK = 0.035;
    private static final double GRAVITY = 0.012;
    private static final double DRAG = 0.955;
    private static final double DRAG_Y = 0.92;
    private static final double MAX_SPEED = 0.85;
    private static final double TURBULENCE = 0.004;

    private static final int WARN_TICKS = 60;
    private static final Identifier VIGNETTE = Identifier.ofVanilla("textures/misc/vignette.png");
    private static final DustParticleEffect SPARKLE = new DustParticleEffect(new Vector3f(1.0f, 1.0f, 1.0f), 0.7f);
    private static final float PIXEL = 1f / 16f;

    private static final RenderLayer GLOW = RenderLayer.of("ragnarsmagicmod_fairy",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 8192, false, true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.LIGHTNING_TRANSPARENCY)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    /** Plain see-through colour, for the solid white core. */
    private static final RenderLayer SOLID = RenderLayer.of("ragnarsmagicmod_fairy_core",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 4096, false, true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.TRANSLUCENT_TRANSPARENCY)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    private static final class Fairy {
        int left, total, age;

        Fairy(int ticks) {
            this.left = this.total = ticks;
        }
    }

    private static final Map<Integer, Fairy> FAIRIES = new HashMap<>();
    private static Perspective perspectiveBefore;
    private static int flutterSound;

    // This frame's camera
    private static Vec3d cam = Vec3d.ZERO;
    private static Vec3d camRight = new Vec3d(1, 0, 0);
    private static Vec3d camUp = new Vec3d(0, 1, 0);
    private static Matrix4f pose = new Matrix4f();

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(FairyPayload.ID, (payload, context) -> {
            MinecraftClient client = context.client();
            int id = payload.entityId();
            boolean mine = client.player != null && client.player.getId() == id;
            if (payload.ticks() > 0) {
                boolean fresh = !FAIRIES.containsKey(id);
                FAIRIES.put(id, new Fairy(payload.ticks()));
                FairyForm.CLIENT.add(id);
                if (mine && fresh) {
                    perspectiveBefore = client.options.getPerspective();
                    client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
                }
            } else {
                FAIRIES.remove(id);
                FairyForm.CLIENT.remove(id);
                if (mine && perspectiveBefore != null) {
                    if (client.options.getPerspective() == Perspective.THIRD_PERSON_BACK) client.options.setPerspective(perspectiveBefore);
                    perspectiveBefore = null;
                }
            }
            Entity e = client.world == null ? null : client.world.getEntityById(id);
            if (e != null) e.calculateDimensions();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            FAIRIES.clear();
            FairyForm.CLIENT.clear();
            if (perspectiveBefore != null) client.options.setPerspective(perspectiveBefore);
            perspectiveBefore = null;
        });
        ClientTickEvents.END_CLIENT_TICK.register(FairyClient::tick);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(FairyClient::renderWorld);
        HudRenderCallback.EVENT.register(FairyClient::renderHud);
    }

    /** Hides the player model of anyone who's currently a fairy (the fairy is drawn instead). */
    public static boolean isHidden(Entity entity) {
        return FAIRIES.containsKey(entity.getId());
    }

    // ---------------------------------------------------------------------
    // Flight
    // ---------------------------------------------------------------------

    public static void travel(ClientPlayerEntity p) {
        Input in = p.input;
        Random r = p.getRandom();
        Vec3d look = p.getRotationVector();
        Vec3d right = new Vec3d(-look.z, 0, look.x);
        right = right.lengthSquared() < 1e-6 ? Vec3d.ZERO : right.normalize();

        Vec3d v = p.getVelocity();
        if (in.pressingForward) v = v.add(look.multiply(THRUST));
        if (in.pressingBack) v = v.multiply(1 - BRAKE * 4).add(look.multiply(-BRAKE * 0.5));
        if (in.pressingLeft) v = v.add(right.multiply(-STRAFE));
        if (in.pressingRight) v = v.add(right.multiply(STRAFE));
        if (in.jumping) v = v.add(0, LIFT, 0);
        if (in.sneaking) v = v.add(0, -SINK, 0);
        v = v.add(0, -GRAVITY, 0);
        // A fairy never flies perfectly straight
        v = v.add(r.nextGaussian() * TURBULENCE, r.nextGaussian() * TURBULENCE, r.nextGaussian() * TURBULENCE);
        v = new Vec3d(v.x * DRAG, v.y * DRAG_Y, v.z * DRAG);
        if (v.length() > MAX_SPEED) v = v.normalize().multiply(MAX_SPEED);

        double speedBefore = v.length();
        p.setVelocity(v);
        p.move(MovementType.SELF, v);
        p.updateLimbs(false);

        // Bonk
        if (p.horizontalCollision && speedBefore > 0.35) {
            p.playSound(SoundEvents.ENTITY_ALLAY_HURT, 0.5f, 1.6f);
        }
        // Wings buzzing, faster the harder you fly
        boolean working = in.pressingForward || in.jumping || in.pressingLeft || in.pressingRight;
        if (--flutterSound <= 0 && working) {
            p.playSound(SoundEvents.ENTITY_PARROT_FLY, 0.35f, 1.8f + r.nextFloat() * 0.3f);
            flutterSound = 6;
        }
    }

    // ---------------------------------------------------------------------
    // Ticking: timers and sparkles
    // ---------------------------------------------------------------------

    private static void tick(MinecraftClient client) {
        if (client.world == null) {
            FAIRIES.clear();
            FairyForm.CLIENT.clear();
            return;
        }
        if (client.isPaused()) return;
        Random r = client.world.random;
        for (Map.Entry<Integer, Fairy> entry : FAIRIES.entrySet()) {
            Fairy f = entry.getValue();
            f.age++;
            if (f.left > 0) f.left--;
            Entity e = client.world.getEntityById(entry.getKey());
            if (e == null) continue;
            // Keep the hitbox in step (e.g. a player who just came into view)
            if (e.getHeight() > FairyForm.DIMENSIONS.height() + 0.01f) e.calculateDimensions();

            // A trail of sparkles
            Vec3d c = e.getPos().add(0, 0.25, 0);
            client.world.addParticle(SPARKLE, c.x + r.nextGaussian() * 0.08, c.y + r.nextGaussian() * 0.08, c.z + r.nextGaussian() * 0.08, 0, 0, 0);
            if (r.nextInt(3) == 0) {
                client.world.addParticle(ParticleTypes.END_ROD, c.x, c.y, c.z, r.nextGaussian() * 0.01, -0.01, r.nextGaussian() * 0.01);
            }
        }
    }

    /** Blinks in the last three seconds, faster and faster. */
    private static float flicker(Fairy f) {
        if (f.left > WARN_TICKS) return 1f;
        int period = f.left > 40 ? 6 : f.left > 20 ? 4 : 2;
        return (f.age / period) % 2 == 0 ? 1f : 0.25f;
    }

    // ---------------------------------------------------------------------
    // In the world
    // ---------------------------------------------------------------------

    private static void renderWorld(WorldRenderContext context) {
        if (FAIRIES.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;
        float tickDelta = context.tickCounter().getTickDelta(false);
        Camera camera = context.camera();
        cam = camera.getPos();
        Vec3d fwd = Vec3d.fromPolar(camera.getPitch(), camera.getYaw());
        Vec3d right = fwd.crossProduct(new Vec3d(0, 1, 0));
        camRight = right.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : right.normalize();
        camUp = camRight.crossProduct(fwd).normalize();

        MatrixStack matrices = context.matrixStack() != null ? context.matrixStack() : new MatrixStack();
        pose = matrices.peek().getPositionMatrix();
        VertexConsumerProvider.Immediate buffers = client.getBufferBuilders().getEntityVertexConsumers();

        // Two passes: the shared buffers only keep one layer open at a time, so the solid cores are finished
        // before any glow is started
        for (int pass = 0; pass < 2; pass++) {
            RenderLayer layer = pass == 0 ? SOLID : GLOW;
            VertexConsumer vc = buffers.getBuffer(layer);
            for (Map.Entry<Integer, Fairy> entry : FAIRIES.entrySet()) {
                Entity e = client.world.getEntityById(entry.getKey());
                if (e == null) continue;
                // Don't draw your own fairy over your eyes in first person
                if (e == client.getCameraEntity() && !camera.isThirdPerson()) continue;
                Fairy f = entry.getValue();
                float age = f.age + tickDelta;
                float bob = 0.06f * MathHelper.sin(age * 0.25f);
                Vec3d c = e.getLerpedPos(tickDelta).add(0, 0.25 + bob, 0);
                float yaw = e.getYaw(tickDelta);
                if (pass == 0) drawCore(vc, c, yaw, age, flicker(f));
                else drawFairy(vc, c, yaw, age, flicker(f));
            }
            buffers.draw(layer);
        }
    }

    /** A solid little white block at its heart. */
    private static void drawCore(VertexConsumer solid, Vec3d c, float yaw, float age, float alpha) {
        cube(solid, c, 1.5f * PIXEL, yaw + age * 4f, 0.25f, 1f, 1f, 1f, alpha, true);
    }

    /** The glowing shells around the core, and the wings. */
    private static void drawFairy(VertexConsumer glow, Vec3d c, float yaw, float age, float alpha) {
        float spin = yaw + age * 4f;

        // A faint glowing shell turning around the core
        cube(glow, c, 3.5f * PIXEL, -spin * 0.6f, 0.4f, 1f, 1f, 1f, 0.22f * alpha, false);

        // Pixel wings: two big ones on top, two small ones below, beating fast
        Vec3d facing = Vec3d.fromPolar(0, yaw);
        Vec3d side = new Vec3d(-facing.z, 0, facing.x);
        Vec3d up = new Vec3d(0, 1, 0);
        Vec3d root = c.subtract(facing.multiply(0.06));
        float beat = MathHelper.sin(age * 1.9f) * 0.6f;
        for (int s = -1; s <= 1; s += 2) {
            wing(glow, root, side, up, facing, s, 0.55f + beat, UPPER_WING, alpha);
            wing(glow, root, side, up, facing, s, -0.4f + beat * 0.7f, LOWER_WING, alpha);
        }
    }

    // Wing shapes, drawn pixel by pixel: rows run across the wing, columns out from the body to the tip
    private static final String[] UPPER_WING = {
            ".XXX.",
            "XXXXX",
            "XXX..",
    };
    private static final String[] LOWER_WING = {
            "XXX",
            "XX.",
    };
    private static final float WING_PIXEL = 0.095f;

    /** One wing, as a grid of glassy white squares - brighter by the body, fainter toward the tip. */
    private static void wing(VertexConsumer vc, Vec3d root, Vec3d side, Vec3d up, Vec3d facing, int s,
                             float angle, String[] shape, float alpha) {
        Vec3d out = side.multiply(s * Math.cos(angle)).add(up.multiply(Math.sin(angle))).subtract(facing.multiply(0.35)).normalize();
        Vec3d du = out.multiply(WING_PIXEL), dw = facing.multiply(WING_PIXEL);
        int rows = shape.length;
        for (int row = 0; row < rows; row++) {
            String line = shape[row];
            for (int col = 0; col < line.length(); col++) {
                if (line.charAt(col) != 'X') continue;
                float a = alpha * (0.6f - 0.35f * col / Math.max(1, line.length() - 1));
                Vec3d p = root.add(du.multiply(col)).add(dw.multiply(rows / 2.0 - row - 1));
                vertex(vc, p, 1f, 1f, 1f, a);
                vertex(vc, p.add(du), 1f, 1f, 1f, a);
                vertex(vc, p.add(du).add(dw), 1f, 1f, 1f, a);
                vertex(vc, p.add(dw), 1f, 1f, 1f, a);
            }
        }
    }

    private static void vertex(VertexConsumer vc, Vec3d p, float r, float g, float b, float a) {
        vc.vertex(pose, (float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z)).color(r, g, b, a);
    }

    /**
     * A cube of half-size {@code half}, turned {@code yawDeg} around the vertical and tipped by {@code tilt} radians.
     * With {@code shade}, faces are lit like a Minecraft block: bright top, dimmer sides, darkest bottom.
     */
    private static void cube(VertexConsumer vc, Vec3d c, float half, float yawDeg, float tilt,
                             float r, float g, float b, float a, boolean shade) {
        if (a <= 0.003f) return;
        double yr = Math.toRadians(yawDeg);
        Vec3d x = new Vec3d(Math.cos(yr), 0, Math.sin(yr));
        Vec3d z = new Vec3d(-Math.sin(yr), 0, Math.cos(yr));
        Vec3d y = new Vec3d(0, 1, 0);
        // Tip it over a little around its own x axis
        Vec3d yT = y.multiply(Math.cos(tilt)).add(z.multiply(Math.sin(tilt)));
        Vec3d zT = z.multiply(Math.cos(tilt)).subtract(y.multiply(Math.sin(tilt)));
        x = x.multiply(half);
        yT = yT.multiply(half);
        zT = zT.multiply(half);
        Vec3d[][] faces = {
                {yT, x, zT},                  // top
                {yT.multiply(-1), x, zT},     // bottom
                {x, yT, zT}, {x.multiply(-1), yT, zT},
                {zT, x, yT}, {zT.multiply(-1), x, yT},
        };
        float[] light = {1f, 0.6f, 0.8f, 0.8f, 0.9f, 0.9f};
        for (int i = 0; i < 6; i++) {
            Vec3d n = faces[i][0], u = faces[i][1], v = faces[i][2];
            Vec3d fc = c.add(n);
            float k = shade ? light[i] : 1f;
            vertex(vc, fc.subtract(u).subtract(v), r * k, g * k, b * k, a);
            vertex(vc, fc.add(u).subtract(v), r * k, g * k, b * k, a);
            vertex(vc, fc.add(u).add(v), r * k, g * k, b * k, a);
            vertex(vc, fc.subtract(u).add(v), r * k, g * k, b * k, a);
        }
    }

    // ---------------------------------------------------------------------
    // On screen (your own fairy)
    // ---------------------------------------------------------------------

    private static void renderHud(DrawContext context, RenderTickCounter counter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.options.hudHidden) return;
        Fairy f = FAIRIES.get(client.player.getId());
        if (f == null) return;
        float tickDelta = counter.getTickDelta(false);
        boolean ending = f.left <= WARN_TICKS;
        int w = context.getScaledWindowWidth();
        int h = context.getScaledWindowHeight();

        // The edges of the screen pulse white in the last three seconds
        if (ending) {
            float k = (1f - f.left / (float) WARN_TICKS) * (0.6f + 0.4f * MathHelper.sin((f.age + tickDelta) * 0.9f));
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.enableBlend();
            RenderSystem.blendFunc(com.mojang.blaze3d.platform.GlStateManager.SrcFactor.ONE,
                    com.mojang.blaze3d.platform.GlStateManager.DstFactor.ONE);
            context.setShaderColor(0.55f * k, 0.55f * k, 0.6f * k, 1f);
            context.drawTexture(VIGNETTE, 0, 0, -90, 0f, 0f, w, h, w, h);
            context.setShaderColor(1f, 1f, 1f, 1f);
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
        }

        // Timer under the crosshair, like the Tome of Clones one
        float alpha = ending && (f.left / 4) % 2 == 0 ? 0.45f : 1f;
        int a = (int) (alpha * 255) << 24;
        int color = ending ? 0xFF5060 : 0xFFFFFF;
        int cx = w / 2;
        int y = h / 2 + 10;
        int half = 16;
        float left = Math.max(0f, f.left - tickDelta);
        context.fill(cx - half - 1, y - 1, cx + half + 1, y + 2, (int) (alpha * 0x88) << 24);
        context.fill(cx - half, y, cx - half + Math.round(2 * half * left / f.total), y + 1, a | color);
        String secs = (f.left + 19) / 20 + "s";
        context.getMatrices().push();
        context.getMatrices().translate(cx, y + 4, 0);
        context.getMatrices().scale(0.75f, 0.75f, 1f);
        context.drawTextWithShadow(client.textRenderer, secs, -client.textRenderer.getWidth(secs) / 2, 0, a | color);
        context.getMatrices().pop();
    }
}
