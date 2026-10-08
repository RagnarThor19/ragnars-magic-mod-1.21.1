package net.ragnar.ragnarsmagicmod.phantom.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.BlockState;
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
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.ragnar.ragnarsmagicmod.phantom.Phantom;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Map;

/**
 * Tome of the Phantom, client side.
 *
 * Flight (your own spectre): exact and fast. You go where you steer - W flies where you look, S backs off, A/D slide
 * sideways, Space rises, Shift sinks - and stop when you let go, with no drift and no gravity. Hold sprint to surge
 * faster still. Your view widens with speed and follows from just behind, through walls and all.
 *
 * Looks: the player is hidden and drawn as a hooded wraith of pale soul-blue light - a dark void under the hood with
 * two burning eyes, a soul flickering in its chest, arms trailing back and a tattered robe streaming out behind like a
 * comet's tail. Fast flight leaves a wake of fading afterimages, souls and soul fire. Passing through blocks it sheds
 * crumbs of them. In the last three seconds it flickers as it's pulled back, and if you're inside a wall then, your
 * screen warns you.
 */
public final class PhantomClient {
    private PhantomClient() {}

    // Flight tuning (per tick)
    private static final double SPEED = 0.7;
    private static final double SURGE = 1.1;
    private static final double ACCEL = 0.3;   // how quickly you reach the speed you're steering for
    private static final double STOP = 0.35;   // how quickly you stop when you let go

    private static final int TRAIL = 8;
    private static final Identifier VIGNETTE = Identifier.ofVanilla("textures/misc/vignette.png");

    // Colours: the pale body, the soul-fire glow, the burning eyes and the void under the hood
    private static final float[] BODY = {0.72f, 0.94f, 1.0f};
    private static final float[] GLOW = {0.3f, 0.82f, 1.0f};
    private static final float[] EYES = {0.8f, 1.0f, 1.0f};
    private static final float[] VOID = {0.02f, 0.05f, 0.09f};

    private static RenderLayer layer(String name, RenderPhase.Transparency transparency, boolean xray) {
        return RenderLayer.of("ragnarsmagicmod_phantom_" + name,
                VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 16384, false, true,
                RenderLayer.MultiPhaseParameters.builder()
                        .program(RenderPhase.COLOR_PROGRAM)
                        .transparency(transparency)
                        .writeMaskState(RenderPhase.COLOR_MASK)
                        .cull(RenderPhase.DISABLE_CULLING)
                        .depthTest(xray ? RenderPhase.ALWAYS_DEPTH_TEST : RenderPhase.LEQUAL_DEPTH_TEST)
                        .build(false));
    }

    /** See-through body, and additive glow; the "xray" ones are for your own spectre, which shows through walls. */
    private static final RenderLayer GHOST = layer("ghost", RenderPhase.TRANSLUCENT_TRANSPARENCY, false);
    private static final RenderLayer SHINE = layer("shine", RenderPhase.LIGHTNING_TRANSPARENCY, false);
    private static final RenderLayer GHOST_XRAY = layer("ghost_xray", RenderPhase.TRANSLUCENT_TRANSPARENCY, true);
    private static final RenderLayer SHINE_XRAY = layer("shine_xray", RenderPhase.LIGHTNING_TRANSPARENCY, true);

    private static final class Spectre {
        int left, total, age;
        final Vec3d[] trail = new Vec3d[TRAIL];
        int trailHead;

        Spectre(int ticks) {
            this.left = this.total = ticks;
        }
    }

    private static final Map<Integer, Spectre> SPECTRES = new HashMap<>();
    private static Perspective perspectiveBefore;
    private static int whoosh;
    private static boolean surging;

    // This frame's camera
    private static Vec3d cam = Vec3d.ZERO;
    private static Matrix4f pose = new Matrix4f();

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(Phantom.StatePayload.ID, (payload, context) -> {
            MinecraftClient client = context.client();
            int id = payload.entityId();
            boolean mine = client.player != null && client.player.getId() == id;
            if (payload.ticks() > 0) {
                boolean fresh = !SPECTRES.containsKey(id);
                Spectre s = new Spectre(payload.ticks());
                Spectre old = SPECTRES.get(id);
                if (old != null) {
                    s.total = old.total;
                    s.age = old.age;
                }
                SPECTRES.put(id, s);
                Phantom.CLIENT.add(id);
                if (mine && fresh) {
                    perspectiveBefore = client.options.getPerspective();
                    client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
                }
            } else {
                SPECTRES.remove(id);
                Phantom.CLIENT.remove(id);
                if (mine) {
                    surging = false;
                    if (client.player != null) client.player.noClip = false;
                    if (perspectiveBefore != null) {
                        if (client.options.getPerspective() == Perspective.THIRD_PERSON_BACK) client.options.setPerspective(perspectiveBefore);
                        perspectiveBefore = null;
                    }
                }
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            SPECTRES.clear();
            Phantom.CLIENT.clear();
            if (perspectiveBefore != null) client.options.setPerspective(perspectiveBefore);
            perspectiveBefore = null;
        });
        ClientTickEvents.END_CLIENT_TICK.register(PhantomClient::tick);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(PhantomClient::renderWorld);
        HudRenderCallback.EVENT.register(PhantomClient::renderHud);
    }

    // ---------------------------------------------------------------------
    // Flight
    // ---------------------------------------------------------------------

    public static void travel(ClientPlayerEntity p) {
        p.noClip = true;
        Input in = p.input;
        Vec3d look = p.getRotationVector();
        Vec3d flat = Vec3d.fromPolar(0, p.getYaw());
        Vec3d right = new Vec3d(-flat.z, 0, flat.x);

        double f = (in.pressingForward ? 1 : 0) - (in.pressingBack ? 1 : 0);
        double s = (in.pressingRight ? 1 : 0) - (in.pressingLeft ? 1 : 0);
        double u = (in.jumping ? 1 : 0) - (in.sneaking ? 1 : 0);
        Vec3d want = look.multiply(f).add(right.multiply(s)).add(0, u, 0);
        if (want.lengthSquared() > 1) want = want.normalize();
        boolean surge = f > 0 && MinecraftClient.getInstance().options.sprintKey.isPressed();
        want = want.multiply(surge ? SURGE : SPEED);

        Vec3d v = p.getVelocity();
        v = v.add(want.subtract(v).multiply(want.lengthSquared() > 0 ? ACCEL : STOP));
        if (v.lengthSquared() < 1e-5) v = Vec3d.ZERO;
        p.setVelocity(v);
        p.move(MovementType.SELF, v);
        p.setOnGround(false);
        p.fallDistance = 0;
        p.updateLimbs(false);

        // The rush of air, louder and higher the faster you go, and a burst of souls as you surge
        double speed = v.length();
        Random r = p.getRandom();
        if (surge && !surging) {
            p.getWorld().addParticle(ParticleTypes.SCULK_SOUL, p.getX(), p.getY() + 1.0, p.getZ(), -v.x * 0.2, 0.02, -v.z * 0.2);
            p.playSound(SoundEvents.ENTITY_BREEZE_SLIDE, 0.8f, 0.6f);
        }
        surging = surge;
        if (--whoosh <= 0 && speed > 0.6) {
            p.playSound(SoundEvents.ENTITY_BREEZE_SLIDE, (float) Math.min(0.7, 0.25 + speed * 0.25), 0.7f + (float) speed * 0.3f + r.nextFloat() * 0.1f);
            whoosh = 12;
        }
    }

    /** Your view widens with your spectre's speed. */
    public static float fovMultiplier(PlayerEntity player) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (player != client.player || !SPECTRES.containsKey(player.getId())) return 1f;
        double k = Math.min(1.0, player.getVelocity().length() / SURGE);
        return 1f + 0.25f * (float) k;
    }

    // ---------------------------------------------------------------------
    // Ticking: timers, the trail of afterimages, particles
    // ---------------------------------------------------------------------

    private static void tick(MinecraftClient client) {
        if (client.world == null) {
            SPECTRES.clear();
            Phantom.CLIENT.clear();
            return;
        }
        if (client.isPaused()) return;
        Random r = client.world.random;
        for (Map.Entry<Integer, Spectre> entry : SPECTRES.entrySet()) {
            Spectre sp = entry.getValue();
            sp.age++;
            if (sp.left > 0) sp.left--;
            Entity e = client.world.getEntityById(entry.getKey());
            if (e == null) continue;

            sp.trail[sp.trailHead] = e.getPos();
            sp.trailHead = (sp.trailHead + 1) % TRAIL;

            Vec3d vel = e.getPos().subtract(e.prevX, e.prevY, e.prevZ);
            double speed = vel.length();
            Vec3d c = e.getPos().add(0, 1.0, 0);
            // Soul fire licking off the robe, glowing motes, and now and then a soul breaking free
            Vec3d tail = e.getPos().add(0, 0.35, 0).subtract(vel.multiply(1.2));
            client.world.addParticle(ParticleTypes.SOUL_FIRE_FLAME, tail.x + r.nextGaussian() * 0.12, tail.y + r.nextGaussian() * 0.12,
                    tail.z + r.nextGaussian() * 0.12, -vel.x * 0.1, 0.01, -vel.z * 0.1);
            if (sp.age % 3 == 0) {
                client.world.addParticle(ParticleTypes.GLOW, c.x + r.nextGaussian() * 0.25, c.y + r.nextGaussian() * 0.4, c.z + r.nextGaussian() * 0.25,
                        -vel.x * 0.05, 0.01, -vel.z * 0.05);
            }
            if (sp.age % 15 == 0) {
                client.world.addParticle(ParticleTypes.SCULK_SOUL, c.x, c.y + 0.3, c.z, 0, 0.04, 0);
            }
            if (speed > 0.6 && r.nextInt(2) == 0) {
                client.world.addParticle(ParticleTypes.SCULK_CHARGE_POP, tail.x + r.nextGaussian() * 0.2, tail.y + 0.4 + r.nextGaussian() * 0.3,
                        tail.z + r.nextGaussian() * 0.2, 0, 0.01, 0);
            }
            // Crumbs of whatever you're passing through
            if (sp.age % 2 == 0 && e instanceof PlayerEntity p && Phantom.insideBlocks(p)) {
                Vec3d at = e.getPos().add(r.nextGaussian() * 0.5, 0.2 + r.nextDouble() * 1.5, r.nextGaussian() * 0.5);
                BlockState state = client.world.getBlockState(BlockPos.ofFloored(at));
                if (!state.isAir()) {
                    client.world.addParticle(new BlockStateParticleEffect(ParticleTypes.BLOCK, state), at.x, at.y, at.z, 0, 0, 0);
                }
            }
        }
    }

    /** Steady while there's time; unsteadier and unsteadier in the last three seconds as you're pulled back. */
    private static float flicker(Spectre sp, float age) {
        if (sp.left > Phantom.WARN_TICKS) return 1f;
        float k = 1f - sp.left / (float) Phantom.WARN_TICKS;
        float wave = 0.5f + 0.5f * MathHelper.sin(age * (0.6f + k * 1.6f));
        int period = sp.left > 40 ? 7 : sp.left > 20 ? 4 : 2;
        boolean blink = (sp.age / period) % 3 == 0;
        return MathHelper.clamp(1f - k * 0.6f * wave - (blink ? 0.35f * k : 0f), 0.15f, 1f);
    }

    // ---------------------------------------------------------------------
    // In the world
    // ---------------------------------------------------------------------

    private static void renderWorld(WorldRenderContext context) {
        if (SPECTRES.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;
        float tickDelta = context.tickCounter().getTickDelta(false);
        Camera camera = context.camera();
        cam = camera.getPos();
        MatrixStack matrices = context.matrixStack() != null ? context.matrixStack() : new MatrixStack();
        pose = matrices.peek().getPositionMatrix();
        VertexConsumerProvider.Immediate buffers = client.getBufferBuilders().getEntityVertexConsumers();

        // Your own spectre shows through walls; other people's vanish into them. The shared buffers only keep one
        // layer open at a time, so each layer is finished before the next is started.
        RenderLayer[] layers = {GHOST, SHINE, GHOST_XRAY, SHINE_XRAY};
        for (int pass = 0; pass < 4; pass++) {
            boolean shine = pass % 2 == 1;
            boolean xray = pass >= 2;
            VertexConsumer vc = null;
            for (Map.Entry<Integer, Spectre> entry : SPECTRES.entrySet()) {
                Entity e = client.world.getEntityById(entry.getKey());
                if (e == null) continue;
                boolean own = e == client.getCameraEntity();
                if (own && !camera.isThirdPerson()) continue;
                if (own != xray) continue;
                if (vc == null) vc = buffers.getBuffer(layers[pass]);
                drawSpectre(vc, shine, e, entry.getValue(), tickDelta);
            }
            if (vc == null) continue;
            // An "always" depth test leaves the depth test as it finds it, so switch it off for the x-ray passes
            if (xray) RenderSystem.disableDepthTest();
            buffers.draw(layers[pass]);
            if (xray) RenderSystem.enableDepthTest();
        }
    }

    private static void drawSpectre(VertexConsumer vc, boolean shine, Entity e, Spectre sp, float tickDelta) {
        float age = sp.age + tickDelta;
        float alpha = flicker(sp, age);
        Vec3d pos = e.getLerpedPos(tickDelta);
        Vec3d vel = e.getPos().subtract(e.prevX, e.prevY, e.prevZ);
        double speed = vel.length();
        float speedK = (float) Math.min(1.0, speed / 1.2);

        float yaw = e.getYaw(tickDelta);
        float pitch = e.getPitch(tickDelta);
        Vec3d up = new Vec3d(0, 1, 0);
        Vec3d flat = Vec3d.fromPolar(0, yaw);
        Vec3d right = new Vec3d(-flat.z, 0, flat.x);

        // Lean into the flight: forward when flying forward, bobbing gently when still
        double forwardSpeed = vel.dotProduct(flat);
        double lean = MathHelper.clamp(forwardSpeed * 0.7, -0.25, 0.75);
        Vec3d upL = up.multiply(Math.cos(lean)).add(flat.multiply(Math.sin(lean)));
        Vec3d fwdL = flat.multiply(Math.cos(lean)).subtract(up.multiply(Math.sin(lean)));
        double bob = 0.07 * Math.sin(age * 0.13) * (1 - speedK);
        Vec3d base = pos.add(0, 0.1 + bob, 0);

        Vec3d torso = base.add(upL.multiply(1.0));
        Vec3d head = base.add(upL.multiply(1.5));

        // Afterimages along the path: only when going fast enough to leave them behind
        if (shine && speed > 0.25) {
            for (int j = 2; j < TRAIL; j += 2) {
                Vec3d at = sp.trail[Math.floorMod(sp.trailHead - 1 - j, TRAIL)];
                if (at == null || at.squaredDistanceTo(pos) < 1.0) continue;
                Vec3d b = at.add(0, 0.1, 0);
                if (b.add(0, 1.2, 0).squaredDistanceTo(cam) < 9.0) continue;
                float a = 0.13f * alpha * (1f - j / (float) TRAIL) * Math.min(1f, (float) speed * 1.5f);
                double shrink = 1.0 - 0.1 * j;
                box(vc, b.add(upL.multiply(1.5)), right, upL, fwdL, 0.22 * shrink, 0.22 * shrink, 0.22 * shrink, GLOW, a, false);
                box(vc, b.add(upL.multiply(1.0)), right, upL, fwdL, 0.22 * shrink, 0.22 * shrink, 0.12 * shrink, GLOW, a * 0.7f, false);
            }
        }

        // The robe: a tail of shrinking, fading pieces that hangs down when still and streams out behind in flight,
        // splitting into two tattered strands at the end
        Vec3d velDir = speed > 1e-3 ? vel.normalize() : Vec3d.ZERO;
        Vec3d streamDir = up.multiply(-1).add(velDir.multiply(-2.6 * speedK)).normalize();
        Vec3d at = torso.subtract(upL.multiply(0.24));
        int segments = 8;
        for (int i = 1; i <= segments; i++) {
            float t = i / (float) segments;
            Vec3d dir = upL.multiply(-1).multiply(1 - t).add(streamDir.multiply(t)).normalize();
            double sway = Math.sin(age * 0.32 - i * 0.75) * 0.03 * i;
            at = at.add(dir.multiply(0.15)).add(right.multiply(sway * 0.35));
            double w = 0.24 * (1 - t * 0.75);
            double d = 0.13 * (1 - t * 0.6);
            float a = (shine ? 0.10f : 0.42f) * (1f - t * 0.8f) * alpha;
            Vec3d side = right;
            Vec3d depth = dir.crossProduct(side).normalize();
            if (i <= 5) {
                box(vc, at, side, dir, depth, w, 0.09, d, shine ? GLOW : BODY, a, false);
            } else {
                // Two strands, swaying apart
                for (int s = -1; s <= 1; s += 2) {
                    double split = 0.08 + 0.04 * (i - 5) + 0.03 * Math.sin(age * 0.4 + s * 1.7 + i);
                    Vec3d strand = at.add(side.multiply(s * split)).add(depth.multiply(0.03 * Math.sin(age * 0.5 + s + i)));
                    box(vc, strand, side, dir, depth, w * 0.42, 0.08, d * 0.7, shine ? GLOW : BODY, a, false);
                }
            }
        }

        // Torso, arms and head
        if (!shine) {
            box(vc, torso, right, upL, fwdL, 0.25, 0.24, 0.14, BODY, 0.45f * alpha, true);
        } else {
            box(vc, torso, right, upL, fwdL, 0.32, 0.31, 0.21, GLOW, 0.1f * alpha, false);
            // The soul in its chest, turning and pulsing
            float pulse = 0.65f + 0.35f * MathHelper.sin(age * 0.45f);
            double spin = age * 0.15;
            Vec3d sx = right.multiply(Math.cos(spin)).add(fwdL.multiply(Math.sin(spin)));
            Vec3d sz = fwdL.multiply(Math.cos(spin)).subtract(right.multiply(Math.sin(spin)));
            Vec3d soul = torso.add(upL.multiply(0.04));
            box(vc, soul, sx, upL, sz, 0.055, 0.055, 0.055, EYES, 0.9f * alpha * pulse, false);
            box(vc, soul, sz, upL, sx, 0.1, 0.1, 0.1, GLOW, 0.3f * alpha * pulse, false);
        }

        for (int s = -1; s <= 1; s += 2) {
            // Arms hang and sway when still, and sweep back along the body in flight
            Vec3d shoulder = torso.add(upL.multiply(0.17)).add(right.multiply(s * 0.33));
            double swing = Math.sin(age * 0.18 + s * 1.3) * 0.25 * (1 - speedK);
            Vec3d armDir = upL.multiply(-1).add(fwdL.multiply(0.25 + swing - 1.6 * speedK)).add(right.multiply(s * (0.18 + 0.1 * speedK))).normalize();
            Vec3d armSide = right;
            Vec3d armDepth = armDir.crossProduct(armSide).normalize();
            armSide = armDepth.crossProduct(armDir).normalize();
            Vec3d mid = shoulder.add(armDir.multiply(0.3));
            if (!shine) box(vc, mid, armSide, armDir, armDepth, 0.07, 0.3, 0.07, BODY, 0.33f * alpha, true);
            else box(vc, mid.add(armDir.multiply(0.12)), armSide, armDir, armDepth, 0.1, 0.22, 0.1, GLOW, 0.06f * alpha, false);
        }

        // The head turns with your view
        Vec3d hFwd = Vec3d.fromPolar(pitch, yaw);
        Vec3d hRight = right;
        Vec3d hUp = hRight.crossProduct(hFwd).normalize();
        boolean faceSeen = cam.subtract(head).dotProduct(hFwd) > 0.25;
        if (!shine) {
            box(vc, head, hRight, hUp, hFwd, 0.25, 0.25, 0.25, BODY, 0.55f * alpha, true);
            // The hood's peak, flopping back
            Vec3d peakDir = hUp.multiply(0.5).subtract(hFwd).normalize();
            Vec3d peakSide = peakDir.crossProduct(hRight).normalize();
            box(vc, head.add(hUp.multiply(0.2)).subtract(hFwd.multiply(0.24)), hRight, peakDir, peakSide, 0.15, 0.12, 0.09, BODY, 0.45f * alpha, true);
            // The hood's open face: a dark void (only from the front - the hood is see-through)
            // The hood's brim, overhanging the face
            box(vc, head.add(hUp.multiply(0.22)).add(hFwd.multiply(0.2)), hRight, hUp, hFwd, 0.27, 0.05, 0.08, BODY, 0.5f * alpha, true);
            if (faceSeen) {
                quad(vc, head.add(hFwd.multiply(0.252)).subtract(hUp.multiply(0.02)), hRight.multiply(0.19), hUp.multiply(0.17), VOID, 0.88f * alpha);
            }
        } else {
            // A hood of light around it
            box(vc, head, hRight, hUp, hFwd, 0.31, 0.31, 0.31, GLOW, 0.14f * alpha, false);
            // Burning eyes
            float burn = (0.8f + 0.2f * MathHelper.sin(age * 0.7f)) * alpha;
            for (int s = -1; s <= 1 && faceSeen; s += 2) {
                Vec3d eye = head.add(hFwd.multiply(0.258)).add(hRight.multiply(s * 0.085)).add(hUp.multiply(0.01));
                quad(vc, eye, hRight.multiply(0.045), hUp.multiply(0.03), EYES, burn);
                quad(vc, eye.add(hFwd.multiply(0.002)), hRight.multiply(0.1), hUp.multiply(0.075), GLOW, 0.35f * burn);
            }
        }
    }

    private static void vertex(VertexConsumer vc, Vec3d p, float r, float g, float b, float a) {
        vc.vertex(pose, (float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z)).color(r, g, b, a);
    }

    private static void quad(VertexConsumer vc, Vec3d c, Vec3d u, Vec3d v, float[] rgb, float a) {
        quad(vc, c, u, v, rgb[0], rgb[1], rgb[2], a);
    }

    private static void quad(VertexConsumer vc, Vec3d c, Vec3d u, Vec3d v, float r, float g, float b, float a) {
        vertex(vc, c.subtract(u).subtract(v), r, g, b, a);
        vertex(vc, c.add(u).subtract(v), r, g, b, a);
        vertex(vc, c.add(u).add(v), r, g, b, a);
        vertex(vc, c.subtract(u).add(v), r, g, b, a);
    }

    /**
     * A box around {@code c} with half-sizes {@code hx, hy, hz} along the unit axes {@code ax, ay, az}. With
     * {@code shade}, faces are lit like a Minecraft block: bright top, dimmer sides, darkest bottom.
     */
    private static void box(VertexConsumer vc, Vec3d c, Vec3d ax, Vec3d ay, Vec3d az, double hx, double hy, double hz,
                            float[] rgb, float a, boolean shade) {
        if (a <= 0.003f) return;
        Vec3d x = ax.multiply(hx), y = ay.multiply(hy), z = az.multiply(hz);
        Vec3d[][] faces = {
                {y, x, z}, {y.multiply(-1), x, z},
                {x, y, z}, {x.multiply(-1), y, z},
                {z, x, y}, {z.multiply(-1), x, y},
        };
        float[] light = {1f, 0.55f, 0.8f, 0.8f, 0.9f, 0.9f};
        for (int i = 0; i < 6; i++) {
            float k = shade ? light[i] : 1f;
            quad(vc, c.add(faces[i][0]), faces[i][1], faces[i][2], rgb[0] * k, rgb[1] * k, rgb[2] * k, a);
        }
    }

    // ---------------------------------------------------------------------
    // On screen (your own spectre)
    // ---------------------------------------------------------------------

    private static void renderHud(DrawContext context, RenderTickCounter counter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.options.hudHidden) return;
        Spectre sp = SPECTRES.get(client.player.getId());
        if (sp == null) return;
        float tickDelta = counter.getTickDelta(false);
        boolean ending = sp.left <= Phantom.WARN_TICKS;
        boolean trapped = ending && Phantom.insideBlocks(client.player);
        int w = context.getScaledWindowWidth();
        int h = context.getScaledWindowHeight();
        float t = sp.age + tickDelta;

        // A soul-blue haze round the edges the whole time; in the last seconds it pulses, red if you're in a wall
        float k = 0.55f + 0.1f * MathHelper.sin(t * 0.1f);
        if (ending) k += (1f - sp.left / (float) Phantom.WARN_TICKS) * (0.5f + 0.5f * MathHelper.sin(t * 0.9f));
        float rr = trapped ? 0.7f : 0.05f, gg = trapped ? 0.08f : 0.35f, bb = trapped ? 0.08f : 0.45f;
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(com.mojang.blaze3d.platform.GlStateManager.SrcFactor.ONE,
                com.mojang.blaze3d.platform.GlStateManager.DstFactor.ONE);
        context.setShaderColor(rr * k, gg * k, bb * k, 1f);
        context.drawTexture(VIGNETTE, 0, 0, -90, 0f, 0f, w, h, w, h);
        context.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();

        // Timer under the crosshair, like the fairy's
        float blink = ending && (sp.left / 4) % 2 == 0 ? 0.45f : 1f;
        int a = (int) (blink * 255) << 24;
        int color = ending ? 0xFF5060 : 0x7FE8FF;
        int cx = w / 2;
        int y = h / 2 + 10;
        int half = 16;
        float left = Math.max(0f, sp.left - tickDelta);
        context.fill(cx - half - 1, y - 1, cx + half + 1, y + 2, (int) (blink * 0x88) << 24);
        context.fill(cx - half, y, cx - half + Math.round(2 * half * left / sp.total), y + 1, a | color);
        String secs = (sp.left + 19) / 20 + "s";
        context.getMatrices().push();
        context.getMatrices().translate(cx, y + 4, 0);
        context.getMatrices().scale(0.75f, 0.75f, 1f);
        context.drawTextWithShadow(client.textRenderer, secs, -client.textRenderer.getWidth(secs) / 2, 0, a | color);
        context.getMatrices().pop();

        if (trapped) {
            String warn = "Get out of the wall!";
            int wa = (sp.age / 3) % 2 == 0 ? 0xFF000000 : 0x99000000;
            context.drawTextWithShadow(client.textRenderer, warn, cx - client.textRenderer.getWidth(warn) / 2, h / 2 - 24, wa | 0xFF4040);
        }
    }
}
