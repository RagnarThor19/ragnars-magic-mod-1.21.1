package net.ragnar.ragnarsmagicmod.astral.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.PostEffectProcessor;
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
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.mob.WaterCreatureEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.astral.AstralProjection;
import net.ragnar.ragnarsmagicmod.client.ScreenShake;
import net.ragnar.ragnarsmagicmod.client.SpellSwitcher;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.mixin.astral.AstralGameRendererAccessor;
import org.joml.Matrix4f;

/**
 * Tome of Astral Projection, client side: everything the spirit sees, hears and does.
 * <p>
 * Leaving: the view floats up and back out of your head, so the first thing you see is your own body standing below
 * you. The world drains to pale violet (shaders/post/astral.json), a low hum fills your ears and a cord of amethyst
 * light runs from your body's chest to just under your eyes.
 * <p>
 * Drifting: W/A/S/D/Space/Shift float the spirit where you look, unhurried and a little floaty (sprint to drift faster).
 * It passes through anything. Living things within range glow through the walls - red monsters, green animals, gold
 * players, your own body in violet. Past three quarters of the cord's length it starts to drag and creak, and it won't
 * let you go any further than {@link AstralProjection#LEASH}. In the last seconds your body's heartbeat thuds in your
 * ears, louder and faster, and the cord pulls.
 * <p>
 * Returning: the cord reels you back along its length, faster and faster, the view widening with the rush, until
 * you land back behind your eyes with a jolt and a breath. If your body was hurt it's a much harder yank, with a red
 * flash.
 * <p>
 * Your body is the player: it stays where it is (movement keys drive the spirit instead) and can't attack, mine or use
 * anything - right-clicking with the tome selected is the only thing it does, and that brings you back early.
 */
public final class AstralClient {
    private AstralClient() {}

    private static final Identifier SHADER = Identifier.of(RagnarsMagicMod.MOD_ID, "shaders/post/astral.json");
    private static final Identifier VIGNETTE = Identifier.ofVanilla("textures/misc/vignette.png");

    // Flight tuning (blocks per tick)
    private static final double SPEED = 0.4;
    private static final double SURGE = 0.65;
    private static final double ACCEL = 0.14;
    private static final double STOP = 0.07;    // a gentle glide to a stop when you let go
    private static final double DRAG_FROM = 0.72; // fraction of the cord's length where it starts to pull
    private static final double SENSE = 32.0;   // how far you sense living things through the walls

    // Colours: the cord's bright core, its violet glow, how it whitens when pulled taut
    private static final float[] CORE = {0.9f, 0.74f, 1.0f};
    private static final float[] GLOW = {0.55f, 0.22f, 1.0f};
    private static final float[] TAUT = {1.0f, 0.62f, 0.9f};

    private static RenderLayer layer(String name, RenderPhase.Transparency transparency, boolean xray) {
        return RenderLayer.of("ragnarsmagicmod_astral_" + name,
                VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 8192, false, true,
                RenderLayer.MultiPhaseParameters.builder()
                        .program(RenderPhase.COLOR_PROGRAM)
                        .transparency(transparency)
                        .writeMaskState(RenderPhase.COLOR_MASK)
                        .cull(RenderPhase.DISABLE_CULLING)
                        .depthTest(xray ? RenderPhase.ALWAYS_DEPTH_TEST : RenderPhase.LEQUAL_DEPTH_TEST)
                        .build(false));
    }

    /** The cord: an additive glow, and a faint copy that shows through walls so you can always find your way back. */
    private static final RenderLayer CORD = layer("cord", RenderPhase.LIGHTNING_TRANSPARENCY, false);
    private static final RenderLayer CORD_XRAY = layer("cord_xray", RenderPhase.LIGHTNING_TRANSPARENCY, true);

    private enum Phase { NONE, OUT, RETURN }

    private static Phase phase = Phase.NONE;
    private static int left, total, age;
    private static Vec3d pos = Vec3d.ZERO, prevPos = Vec3d.ZERO, vel = Vec3d.ZERO;
    private static double taut;
    private static boolean inWall, jolted;

    // Coming back
    private static Vec3d returnFrom = Vec3d.ZERO;
    private static int returnTicks, returnTotal;

    // Captured movement keys (your body doesn't get them)
    private static boolean kForward, kBack, kLeft, kRight, kUp, kDown;

    // Effects
    private static Perspective perspectiveBefore;
    private static AstralAmbience ambience;
    private static float flash, flashRed, beat, leaveKick, landKick;
    private static int beatTimer, dubTimer, tautTimer, twinkleTimer;

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(AstralProjection.StatePayload.ID, (payload, context) -> {
            MinecraftClient client = context.client();
            if (payload.ticks() > 0) {
                if (phase == Phase.NONE) leave(client, payload.ticks());
                else left = payload.ticks();
            } else if (phase == Phase.OUT) {
                reelBack(client, payload.jolted());
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset(client));
        ClientTickEvents.END_CLIENT_TICK.register(AstralClient::tick);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(AstralClient::renderCord);
        HudRenderCallback.EVENT.register(AstralClient::renderHud);
    }

    // ---------------------------------------------------------------------
    // Hooks for the mixins
    // ---------------------------------------------------------------------

    /** True from the moment you leave your body until you're back behind your eyes. */
    public static boolean isActive() {
        return phase != Phase.NONE;
    }

    /** Where the camera is this frame, or null when you're in your body. */
    public static Vec3d cameraPos(float tickDelta) {
        if (phase == Phase.NONE) return null;
        return prevPos.lerp(pos, tickDelta);
    }

    /** Draw your body once the view is clear of your head (not while you're still inside it, leaving or landing). */
    public static boolean showBody(Vec3d camera, float tickDelta) {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        return p != null && camera.squaredDistanceTo(p.getCameraPosVec(tickDelta)) > 1.2 * 1.2;
    }

    /** The movement keys steer the spirit; the body gets nothing. */
    public static void captureInput(Input input) {
        if (phase == Phase.NONE) return;
        kForward = input.pressingForward;
        kBack = input.pressingBack;
        kLeft = input.pressingLeft;
        kRight = input.pressingRight;
        kUp = input.jumping;
        kDown = input.sneaking;
        input.movementForward = 0;
        input.movementSideways = 0;
        input.pressingForward = false;
        input.pressingBack = false;
        input.pressingLeft = false;
        input.pressingRight = false;
        input.jumping = false;
        input.sneaking = false;
    }

    /** Right-click only goes through while drifting with this tome selected: that's how you go back early. */
    public static boolean allowItemUse() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (phase != Phase.OUT || client.player == null) return false;
        Hand hand = SpellSwitcher.findStaffHand(client.player);
        return hand != null && StaffItem.getSelectedTome(client.player.getStackInHand(hand)) == AstralProjection.TOME_OF_ASTRAL_PROJECTION;
    }

    /** Living things near the spirit glow through the walls. */
    public static boolean hasOutline(Entity e) {
        if (phase == Phase.NONE || !(e instanceof LivingEntity living) || !living.isAlive()) return false;
        MinecraftClient client = MinecraftClient.getInstance();
        if (e == client.player) return true;
        return !e.isInvisible() && e.squaredDistanceTo(pos) < SENSE * SENSE;
    }

    /** The glow's colour, or -1 to leave it alone. */
    public static int outlineColor(Entity e) {
        if (phase == Phase.NONE || !hasOutline(e)) return -1;
        if (e == MinecraftClient.getInstance().player) return 0xC79BFF; // your own body
        if (e instanceof PlayerEntity) return 0xFFD45A;
        if (e instanceof Monster) return 0xFF4848;
        if (e instanceof AnimalEntity || e instanceof WaterCreatureEntity) return 0x62FF8A;
        return 0x8EC5FF; // villagers, golems and the rest
    }

    /** The view: a kick wider as you leave, wider still with speed and the rush back, a squeeze as you land. */
    public static double fovMultiplier(float tickDelta) {
        double k = 1.0 + 0.22 * leaveKick - 0.14 * landKick;
        if (phase == Phase.OUT) {
            k += 0.08 * Math.min(1.0, vel.length() / SURGE);
        } else if (phase == Phase.RETURN) {
            k += 0.3 * returnEase(tickDelta);
        }
        return k;
    }

    // ---------------------------------------------------------------------
    // Leaving, drifting, coming back
    // ---------------------------------------------------------------------

    private static void leave(MinecraftClient client, int ticks) {
        ClientPlayerEntity p = client.player;
        if (p == null) return;
        phase = Phase.OUT;
        left = total = ticks;
        age = 0;
        jolted = false;
        taut = 0;
        inWall = false;
        beatTimer = dubTimer = tautTimer = 0;
        twinkleTimer = 25;
        // Float up and back out of your head, so you look down on your own body
        pos = prevPos = p.getEyePos();
        vel = p.getRotationVector().multiply(-0.17).add(0, 0.17, 0);

        perspectiveBefore = client.options.getPerspective();
        client.options.setPerspective(Perspective.FIRST_PERSON);
        ((AstralGameRendererAccessor) client.gameRenderer).ragnarsmagicmod$loadPostProcessor(SHADER);

        ambience = new AstralAmbience();
        client.getSoundManager().play(ambience);
        // The rushing swell of leaving: a nether portal's pull, quick and far away
        client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.BLOCK_PORTAL_TRAVEL, 1.5f, 0.22f));
        client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.BLOCK_AMETHYST_CLUSTER_PLACE, 0.6f, 0.8f));
        flash = 1f;
        flashRed = 0f;
        leaveKick = 1f;
    }

    private static void reelBack(MinecraftClient client, boolean hurt) {
        if (client.player == null) {
            reset(client);
            return;
        }
        phase = Phase.RETURN;
        jolted = hurt;
        returnFrom = pos;
        returnTicks = 0;
        double dist = pos.distanceTo(client.player.getEyePos());
        returnTotal = hurt ? 5 : MathHelper.clamp((int) Math.round(6 + dist * 0.35), 7, 16);
        if (ambience != null) ambience.fadeOut();
        client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_ENDER_EYE_LAUNCH, hurt ? 0.9f : 0.55f, 0.6f));
        if (hurt) {
            flash = 0.8f;
            flashRed = 1f;
            ScreenShake.kick(0.6f, 6);
        }
    }

    /** Back behind your eyes. */
    private static void land(MinecraftClient client) {
        phase = Phase.NONE;
        flash = 1f;
        landKick = 1f;
        ScreenShake.kick(jolted ? 1.0f : 0.45f, jolted ? 12 : 9);
        client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_PLAYER_BREATH, jolted ? 1.25f : 0.95f, 0.9f));
        restoreView(client);
    }

    /** Drop everything at once (disconnects, dying, changing worlds). */
    private static void reset(MinecraftClient client) {
        if (phase == Phase.NONE && ambience == null) return;
        phase = Phase.NONE;
        flash = 0f;
        restoreView(client);
    }

    private static void restoreView(MinecraftClient client) {
        if (ambience != null) {
            ambience.fadeOut();
            ambience = null;
        }
        PostEffectProcessor post = client.gameRenderer.getPostProcessor();
        if (post != null && post.getName().contains("astral")) client.gameRenderer.disablePostProcessor();
        if (perspectiveBefore != null) {
            if (client.options.getPerspective() == Perspective.FIRST_PERSON) client.options.setPerspective(perspectiveBefore);
            perspectiveBefore = null;
        }
    }

    private static float returnEase(float tickDelta) {
        if (phase != Phase.RETURN) return 0f;
        float t = MathHelper.clamp((returnTicks + tickDelta) / returnTotal, 0f, 1f);
        return (float) Math.pow(t, 2.4);
    }

    private static void tick(MinecraftClient client) {
        if (client.world == null || client.player == null) {
            if (phase != Phase.NONE) reset(client);
            return;
        }
        if (client.isPaused()) return;
        flash *= 0.8f;
        beat *= 0.8f;
        leaveKick *= 0.86f;
        landKick *= 0.82f;
        if (phase == Phase.NONE) return;

        ClientPlayerEntity p = client.player;
        if (!p.isAlive()) {
            reset(client);
            return;
        }
        age++;
        prevPos = pos;
        p.setSprinting(false);
        Vec3d body = p.getEyePos();
        Random r = client.world.random;

        if (phase == Phase.RETURN) {
            returnTicks++;
            float e = returnEase(0f);
            pos = returnFrom.lerp(body, e);
            client.world.addParticle(ParticleTypes.REVERSE_PORTAL, pos.x + r.nextGaussian() * 0.3, pos.y - 0.4 + r.nextGaussian() * 0.3,
                    pos.z + r.nextGaussian() * 0.3, 0, 0, 0);
            if (returnTicks >= returnTotal) {
                pos = body;
                land(client);
            }
            return;
        }

        // --- Drifting ---
        left--;
        if (left < -40) { // never heard back from the server: go home anyway
            reelBack(client, false);
            return;
        }
        Vec3d look = p.getRotationVector();
        Vec3d flat = Vec3d.fromPolar(0, p.getYaw());
        Vec3d right = new Vec3d(-flat.z, 0, flat.x);
        double f = (kForward ? 1 : 0) - (kBack ? 1 : 0);
        double s = (kRight ? 1 : 0) - (kLeft ? 1 : 0);
        double u = (kUp ? 1 : 0) - (kDown ? 1 : 0);
        Vec3d want = look.multiply(f).add(right.multiply(s)).add(0, u, 0);
        if (want.lengthSquared() > 1) want = want.normalize();
        boolean surge = f > 0 && client.options.sprintKey.isPressed();
        want = want.multiply(surge ? SURGE : SPEED);
        vel = vel.add(want.subtract(vel).multiply(want.lengthSquared() > 0 ? ACCEL : STOP));
        if (age < 10) vel = vel.multiply(0.94); // the float out of your head eases off

        // The cord: drags past three quarters of its length, holds fast at the end
        double leash = AstralProjection.LEASH;
        Vec3d off = pos.add(vel).subtract(body);
        double dist = off.length();
        taut = MathHelper.clamp((dist - leash * DRAG_FROM) / (leash * (1 - DRAG_FROM)), 0.0, 1.0);
        boolean straining = false;
        if (taut > 0 && dist > 1e-3) {
            Vec3d out = off.multiply(1 / dist);
            double outward = vel.dotProduct(out);
            if (outward > 0) {
                vel = vel.subtract(out.multiply(outward * taut * taut));
                straining = want.dotProduct(out) > 0.05;
            }
            vel = vel.subtract(out.multiply(0.012 * taut));
        }
        if (vel.lengthSquared() < 1e-6) vel = Vec3d.ZERO;
        pos = pos.add(vel);
        off = pos.subtract(body);
        if (off.length() > leash) pos = body.add(off.normalize().multiply(leash));

        // Slipping in and out of solid ground
        BlockPos bp = BlockPos.ofFloored(pos);
        boolean wall = client.world.getBlockState(bp).isSolidBlock(client.world, bp);
        if (wall != inWall) {
            inWall = wall;
            client.world.playSound(pos.x, pos.y, pos.z, SoundEvents.BLOCK_SPORE_BLOSSOM_HIT, SoundCategory.PLAYERS,
                    0.7f, wall ? 0.55f : 0.8f, false);
        }

        // The cord creaking as it pulls tight
        if (--tautTimer <= 0 && taut > 0.45 && straining) {
            client.world.playSound(pos.x, pos.y, pos.z, SoundEvents.BLOCK_LARGE_AMETHYST_BUD_PLACE, SoundCategory.PLAYERS,
                    0.5f + 0.4f * (float) taut, 0.45f + 0.25f * (float) taut, false);
            tautTimer = 11;
        }
        // Now and then, a faint chime of the plane itself
        if (--twinkleTimer <= 0) {
            client.world.playSound(pos.x + r.nextGaussian() * 3, pos.y + r.nextGaussian() * 2, pos.z + r.nextGaussian() * 3,
                    SoundEvents.BLOCK_AMETHYST_CLUSTER_STEP, SoundCategory.PLAYERS, 0.35f, 1.4f + r.nextFloat() * 0.6f, false);
            twinkleTimer = 22 + r.nextInt(22);
        }
        // Your body's heartbeat calling you back, quickening as the time runs out
        if (left <= AstralProjection.WARN_TICKS) {
            if (--beatTimer <= 0) {
                heartbeat(client, 0.75f + 0.25f * (1f - left / (float) AstralProjection.WARN_TICKS), 0.5f);
                beat = 1f;
                dubTimer = 5;
                beatTimer = left > 30 ? 18 : left > 14 ? 13 : 9;
            }
            if (dubTimer > 0 && --dubTimer == 0) {
                heartbeat(client, 0.5f, 0.43f);
                beat = Math.max(beat, 0.55f);
            }
        }

        // Motes of the plane drifting around you (never right in your face)
        for (int i = 0; i < 2; i++) {
            Vec3d at = pos.add(around(r, 2.5, 6.0));
            client.world.addParticle(ParticleTypes.REVERSE_PORTAL, at.x, at.y, at.z, 0, 0.01, 0);
        }
        if (age % 6 == 0) {
            Vec3d at = pos.add(around(r, 5.0, 9.0));
            client.world.addParticle(ParticleTypes.END_ROD, at.x, at.y, at.z, r.nextGaussian() * 0.004, 0.006, r.nextGaussian() * 0.004);
        }
    }

    /** A random offset between {@code min} and {@code max} blocks away, flattened a little. */
    private static Vec3d around(Random r, double min, double max) {
        Vec3d dir = new Vec3d(r.nextGaussian(), r.nextGaussian() * 0.6, r.nextGaussian());
        if (dir.lengthSquared() < 1e-4) dir = new Vec3d(1, 0, 0);
        return dir.normalize().multiply(min + r.nextDouble() * (max - min));
    }

    private static void heartbeat(MinecraftClient client, float volume, float pitch) {
        client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.BLOCK_NOTE_BLOCK_BASEDRUM.value(), pitch, volume));
    }

    // ---------------------------------------------------------------------
    // The silver cord
    // ---------------------------------------------------------------------

    private static final int SEGMENTS = 40;
    private static Vec3d cam = Vec3d.ZERO;
    private static Matrix4f pose = new Matrix4f();

    private static void renderCord(WorldRenderContext context) {
        if (phase == Phase.NONE) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) return;
        float tickDelta = context.tickCounter().getTickDelta(false);
        Camera camera = context.camera();
        cam = camera.getPos();
        MatrixStack matrices = context.matrixStack() != null ? context.matrixStack() : new MatrixStack();
        pose = matrices.peek().getPositionMatrix();

        // From your body's chest to just under your eyes
        Vec3d a = client.player.getLerpedPos(tickDelta).add(0, 1.15, 0);
        Vec3d b = cam.add(0, -0.55, 0);
        Vec3d span = b.subtract(a);
        double len = span.length();
        if (len < 0.4) return;

        float t0 = age + tickDelta;
        double tight = phase == Phase.RETURN ? 1.0 : taut;
        double sag = (1 - tight) * Math.min(3.0, len * 0.11);
        Vec3d side = span.crossProduct(new Vec3d(0, 1, 0));
        side = side.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : side.normalize();

        Vec3d[] pts = new Vec3d[SEGMENTS + 1];
        for (int i = 0; i <= SEGMENTS; i++) {
            double t = i / (double) SEGMENTS;
            double hump = Math.sin(Math.PI * t);
            Vec3d at = a.lerp(b, t).add(0, -sag * 4 * t * (1 - t), 0);
            // A slow ripple when slack, a fast thrum when pulled tight
            double ripple = Math.sin(t * Math.PI * 3 - t0 * 0.22) * 0.07 * (1 - tight) * hump;
            double thrum = Math.sin(t0 * 2.6 + t * 22) * 0.035 * tight * hump;
            at = at.add(side.multiply(ripple + thrum)).add(0, Math.cos(t * Math.PI * 2 - t0 * 0.17) * 0.04 * (1 - tight) * hump, 0);
            pts[i] = at;
        }

        float[] glow = mix(GLOW, TAUT, (float) tight);
        float fade = phase == Phase.RETURN ? 1f - 0.5f * returnEase(tickDelta) : 1f;
        VertexConsumerProvider.Immediate buffers = client.getBufferBuilders().getEntityVertexConsumers();

        // Faintly through walls first, then the real thing
        VertexConsumer vc = buffers.getBuffer(CORD_XRAY);
        strip(vc, pts, 0.07, glow, 0.12f * fade, t0, false);
        RenderSystem.disableDepthTest();
        buffers.draw(CORD_XRAY);
        RenderSystem.enableDepthTest();

        vc = buffers.getBuffer(CORD);
        strip(vc, pts, 0.13, glow, 0.3f * fade, t0, true);
        strip(vc, pts, 0.03, CORE, 0.5f * fade, t0, true);
        buffers.draw(CORD);
    }

    /** A camera-facing ribbon along {@code pts}, with pulses of light running up it from your body to you. */
    private static void strip(VertexConsumer vc, Vec3d[] pts, double width, float[] rgb, float alpha, float time, boolean pulses) {
        int n = pts.length;
        Vec3d[] across = new Vec3d[n];
        for (int i = 0; i < n; i++) {
            Vec3d tangent = pts[Math.min(n - 1, i + 1)].subtract(pts[Math.max(0, i - 1)]);
            Vec3d toCam = cam.subtract(pts[i]);
            Vec3d w = tangent.crossProduct(toCam);
            across[i] = w.lengthSquared() < 1e-9 ? new Vec3d(0, 1, 0) : w.normalize();
        }
        for (int i = 0; i < n - 1; i++) {
            float k0 = shape(i / (float) (n - 1), time, pulses);
            float k1 = shape((i + 1) / (float) (n - 1), time, pulses);
            float a0 = alpha * k0 * ends(i / (float) (n - 1)) * nearFade(pts[i]);
            float a1 = alpha * k1 * ends((i + 1) / (float) (n - 1)) * nearFade(pts[i + 1]);
            Vec3d w0 = across[i].multiply(width * (0.6 + 0.4 * k0) * nearThin(pts[i]));
            Vec3d w1 = across[i + 1].multiply(width * (0.6 + 0.4 * k1) * nearThin(pts[i + 1]));
            vertex(vc, pts[i].subtract(w0), rgb, a0);
            vertex(vc, pts[i].add(w0), rgb, a0);
            vertex(vc, pts[i + 1].add(w1), rgb, a1);
            vertex(vc, pts[i + 1].subtract(w1), rgb, a1);
        }
    }

    /** Brightness along the cord: 1, plus beads of light travelling from the body (t = 0) to you (t = 1). */
    private static float shape(float t, float time, boolean pulses) {
        if (!pulses) return 1f;
        float k = 1f;
        for (int j = 0; j < 3; j++) {
            float u = (time * 0.028f + j / 3f) % 1f;
            float d = (t - u) / 0.035f;
            k += 1.6f * (float) Math.exp(-d * d);
        }
        return k;
    }

    /** Softens the two ends a little. */
    private static float ends(float t) {
        return MathHelper.clamp((1f - t) / 0.05f, 0f, 1f) * MathHelper.clamp(t / 0.03f, 0f, 1f);
    }

    /** The last few blocks before your eyes fade away, so the cord trails off below you instead of filling the view. */
    private static float nearFade(Vec3d p) {
        return MathHelper.clamp((float) ((p.distanceTo(cam) - 1.0) / 3.5), 0f, 1f);
    }

    /** ...and get thinner, so perspective doesn't fan it out into a beam. */
    private static double nearThin(Vec3d p) {
        return MathHelper.clamp(p.distanceTo(cam) / 6.0, 0.15, 1.0);
    }

    private static void vertex(VertexConsumer vc, Vec3d p, float[] rgb, float a) {
        vc.vertex(pose, (float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z)).color(rgb[0], rgb[1], rgb[2], MathHelper.clamp(a, 0f, 1f));
    }

    private static float[] mix(float[] x, float[] y, float k) {
        return new float[]{MathHelper.lerp(k, x[0], y[0]), MathHelper.lerp(k, x[1], y[1]), MathHelper.lerp(k, x[2], y[2])};
    }

    // ---------------------------------------------------------------------
    // On screen
    // ---------------------------------------------------------------------

    private static void renderHud(DrawContext context, RenderTickCounter counter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.options.hudHidden) return;
        if (phase == Phase.NONE && flash < 0.02f) return;
        float tickDelta = counter.getTickDelta(false);
        int w = context.getScaledWindowWidth();
        int h = context.getScaledWindowHeight();

        if (phase != Phase.NONE) {
            // A violet haze round the edges; it thickens with the cord's pull, throbs with the heartbeat, and closes in as
            // you're reeled back
            float t = age + tickDelta;
            float k = 0.6f + 0.08f * MathHelper.sin(t * 0.12f) + 0.5f * (float) taut + 0.6f * beat + 0.9f * returnEase(tickDelta);
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.enableBlend();
            RenderSystem.blendFunc(com.mojang.blaze3d.platform.GlStateManager.SrcFactor.ONE,
                    com.mojang.blaze3d.platform.GlStateManager.DstFactor.ONE);
            context.setShaderColor(0.32f * k, 0.14f * k, 0.5f * k, 1f);
            context.drawTexture(VIGNETTE, 0, 0, -90, 0f, 0f, w, h, w, h);
            context.setShaderColor(1f, 1f, 1f, 1f);
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
        }

        if (phase == Phase.OUT) {
            // How long the cord will hold, under the crosshair
            boolean ending = left <= AstralProjection.WARN_TICKS;
            int color = ending ? 0xFF7FB0 : 0xD2B0FF;
            float pulse = ending ? 0.6f + 0.4f * beat : 1f;
            int a = (int) (pulse * 255) << 24;
            int cx = w / 2;
            int y = h / 2 + 10;
            int half = 16;
            float rest = Math.max(0f, left - tickDelta);
            context.fill(cx - half - 1, y - 1, cx + half + 1, y + 2, 0x88000000);
            context.fill(cx - half, y, cx - half + Math.round(2 * half * rest / total), y + 1, a | color);
            String secs = Math.max(0, (left + 19) / 20) + "s";
            context.getMatrices().push();
            context.getMatrices().translate(cx, y + 4, 0);
            context.getMatrices().scale(0.75f, 0.75f, 1f);
            context.drawTextWithShadow(client.textRenderer, secs, -client.textRenderer.getWidth(secs) / 2, 0, a | color);
            context.getMatrices().pop();

            if (age < 60) {
                float fadeIn = MathHelper.clamp((age - 8 + tickDelta) / 10f, 0f, 1f);
                float fadeOut = MathHelper.clamp((60 - age - tickDelta) / 15f, 0f, 1f);
                int ha = (int) (fadeIn * fadeOut * 200);
                if (ha > 8) {
                    String hint = "Cast again to return";
                    context.drawTextWithShadow(client.textRenderer, hint, cx - client.textRenderer.getWidth(hint) / 2, y + 14, (ha << 24) | 0xBFA6E6);
                }
            }
        }

        // The flash of leaving and of landing back (red if your body was hurt)
        if (flash > 0.02f) {
            int rgb = jolted && flashRed > 0 ? 0xFF8A9A : 0xD8C2FF;
            int fa = (int) (Math.min(1f, flash) * (jolted ? 150 : 110));
            context.fill(0, 0, w, h, (fa << 24) | rgb);
        }
    }
}
