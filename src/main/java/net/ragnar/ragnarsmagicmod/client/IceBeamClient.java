package net.ragnar.ragnarsmagicmod.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.block.entity.BeaconBlockEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.RaycastContext;
import net.ragnar.ragnarsmagicmod.item.spell.IceBeamSpell;
import net.ragnar.ragnarsmagicmod.network.IceBeamPayload;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Draws the Tome of Ice Beam out of Minecraft's own pieces. While charging: little blocks of ice, packed ice and
 * blue ice circle the staff tip and spiral in, a blue ice crystal grows there a pixel at a time, and a dotted line of
 * pixels marks where the beam will land. Then the beam: a thin, icy beacon beam from the staff to wherever the
 * caster is looking, with tiny ice blocks tumbling along it and square frost rings spreading where it hits, knocking
 * loose chunks of ice. The caster's screen frosts over like standing in powder snow.
 */
public final class IceBeamClient {
    private IceBeamClient() {}

    private static final class Beam {
        final int charge, fire;
        int age;

        Beam(int charge, int fire) {
            this.charge = charge;
            this.fire = fire;
        }
    }

    /** A chunk of ice knocked off where the beam hits, tumbling away under gravity. */
    private static final class Chunk {
        Vec3d pos, prevPos, vel;
        final BlockState state;
        final float size, spinSpeed;
        int age;

        Chunk(Vec3d pos, Vec3d vel, BlockState state, float size, float spinSpeed) {
            this.pos = this.prevPos = pos;
            this.vel = vel;
            this.state = state;
            this.size = size;
            this.spinSpeed = spinSpeed;
        }
    }

    private static final Map<Integer, Beam> BEAMS = new HashMap<>();
    private static final List<Chunk> CHUNKS = new ArrayList<>();
    private static final int FADE_TICKS = 5;
    private static final int CHUNK_LIFE = 16;
    private static final int MAX_CHUNKS = 60;
    // Your own staff tip is under a block from your eyes in first person, so what's drawn there is kept small
    private static final float NEAR_SCALE = 0.45f;
    private static final float PIXEL = 1f / 16f;

    private static final BlockState ICE = Blocks.ICE.getDefaultState();
    private static final BlockState PACKED_ICE = Blocks.PACKED_ICE.getDefaultState();
    private static final BlockState BLUE_ICE = Blocks.BLUE_ICE.getDefaultState();
    private static final BlockState[] ICES = {ICE, PACKED_ICE, BLUE_ICE};
    private static final BlockStateParticleEffect ICE_CHIPS = new BlockStateParticleEffect(ParticleTypes.BLOCK, ICE);
    private static final Identifier FROST_OVERLAY = Identifier.ofVanilla("textures/misc/powder_snow_outline.png");
    private static final int BEAM_COLOR = 0xFFA8E4FF;

    /** Flat pixels of light for the sight dots and frost rings, added on top of the world. */
    private static final RenderLayer PIXELS = RenderLayer.of("ragnarsmagicmod_ice_beam_pixels",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 16384, false, true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.LIGHTNING_TRANSPARENCY)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    // This frame's camera: everything is placed relative to it
    private static Vec3d cam = Vec3d.ZERO;
    private static Vec3d camRight = new Vec3d(1, 0, 0);
    private static Vec3d camUp = new Vec3d(0, 1, 0);
    private static Matrix4f pose = new Matrix4f();

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(IceBeamPayload.ID, (payload, context) -> {
            if (payload.chargeTicks() <= 0 && payload.fireTicks() <= 0) {
                // Cut short: fade out from wherever it was
                Beam b = BEAMS.get(payload.entityId());
                if (b != null) b.age = Math.max(b.age, b.charge + b.fire);
            } else {
                BEAMS.put(payload.entityId(), new Beam(payload.chargeTicks(), payload.fireTicks()));
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            BEAMS.clear();
            CHUNKS.clear();
        });
        ClientTickEvents.END_CLIENT_TICK.register(IceBeamClient::tick);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(IceBeamClient::renderWorld);
        HudRenderCallback.EVENT.register(IceBeamClient::renderHud);
    }

    // ---------------------------------------------------------------------
    // Where the beam runs
    // ---------------------------------------------------------------------

    private record Line(Vec3d origin, Vec3d end, Vec3d normal, boolean hitBlock) {}

    private static boolean isLocalFirstPerson(Entity caster) {
        MinecraftClient client = MinecraftClient.getInstance();
        return caster == client.player && client.options.getPerspective().isFirstPerson() && client.getCameraEntity() == caster;
    }

    /** Which side the staff is held on: +1 right, -1 left. */
    private static int staffSide(LivingEntity caster) {
        Hand hand = caster instanceof net.minecraft.client.network.ClientPlayerEntity p ? SpellSwitcher.findStaffHand(p) : null;
        Arm arm = caster.getMainArm();
        if (hand == Hand.OFF_HAND) arm = arm.getOpposite();
        return arm == Arm.RIGHT ? 1 : -1;
    }

    private static Line line(LivingEntity caster, float tickDelta) {
        Vec3d eye = caster.getCameraPosVec(tickDelta);
        Vec3d look = caster.getRotationVec(tickDelta);
        int side = staffSide(caster);

        Vec3d origin;
        if (isLocalFirstPerson(caster)) {
            // Just off the staff tip in your hand, low and to the side of the crosshair
            Vec3d right = look.crossProduct(new Vec3d(0, 1, 0));
            right = right.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : right.normalize();
            Vec3d up = right.crossProduct(look).normalize();
            origin = eye.add(look.multiply(0.8)).add(right.multiply(0.3 * side)).add(up.multiply(-0.17));
        } else {
            float bodyYaw = MathHelper.lerpAngleDegrees(tickDelta, caster.prevBodyYaw, caster.bodyYaw);
            Vec3d fwd = Vec3d.fromPolar(0, bodyYaw);
            Vec3d right = new Vec3d(-fwd.z, 0, fwd.x);
            Vec3d feet = caster.getLerpedPos(tickDelta);
            origin = feet.add(0, caster.isInSneakingPose() ? 1.1 : 1.35, 0)
                    .add(fwd.multiply(0.5)).add(right.multiply(0.36 * side))
                    .add(look.multiply(0.35));
        }

        HitResult hit = caster.getWorld().raycast(new RaycastContext(eye, eye.add(look.multiply(IceBeamSpell.RANGE)),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.SOURCE_ONLY, caster));
        if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
            return new Line(origin, bhr.getPos(), Vec3d.of(bhr.getSide().getVector()), true);
        }
        return new Line(origin, eye.add(look.multiply(IceBeamSpell.RANGE)), look.multiply(-1), false);
    }

    // ---------------------------------------------------------------------
    // Ticking: lifetimes, particles, chunks of ice, shake
    // ---------------------------------------------------------------------

    private static void tick(MinecraftClient client) {
        if (client.world == null) {
            BEAMS.clear();
            CHUNKS.clear();
            return;
        }
        if (client.isPaused()) return;

        Iterator<Chunk> ci = CHUNKS.iterator();
        while (ci.hasNext()) {
            Chunk c = ci.next();
            if (++c.age > CHUNK_LIFE) {
                ci.remove();
                continue;
            }
            c.prevPos = c.pos;
            c.pos = c.pos.add(c.vel);
            c.vel = c.vel.multiply(0.92).add(0, -0.035, 0);
        }

        Iterator<Map.Entry<Integer, Beam>> it = BEAMS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Beam> entry = it.next();
            Beam b = entry.getValue();
            b.age++;
            if (b.age > b.charge + b.fire + FADE_TICKS) {
                it.remove();
                continue;
            }
            if (!(client.world.getEntityById(entry.getKey()) instanceof LivingEntity caster)) continue;
            Line l = line(caster, 1f);
            boolean firstPerson = isLocalFirstPerson(caster);
            if (b.age < b.charge) chargeParticles(client.world, l, b.age / (float) b.charge, firstPerson ? 0.45 : 1.0);
            else if (b.age < b.charge + b.fire) fireParticles(client.world, l);

            if (caster == client.player) {
                int f = b.age - b.charge;
                if (f == 0) ScreenShake.kick(0.7f, 8);
                else if (f > 0 && f < b.fire && f % 3 == 0) ScreenShake.kick(0.12f, 4);
                else if (f < 0 && f > -6) ScreenShake.kick(0.04f * (6 + f), 3);
            }
        }
    }

    /** Snowflakes and ice chips drawn in toward the staff tip. */
    private static void chargeParticles(ClientWorld world, Line l, float p, double spread) {
        Random r = world.random;
        Vec3d o = l.origin();
        int count = 1 + (int) (p * 3);
        for (int i = 0; i < count; i++) {
            Vec3d dir = new Vec3d(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).normalize();
            Vec3d at = o.add(dir.multiply((1.0 + r.nextDouble() * 0.8) * spread));
            Vec3d v = o.subtract(at).multiply(0.12);
            world.addParticle(ParticleTypes.SNOWFLAKE, at.x, at.y, at.z, v.x, v.y, v.z);
        }
    }

    /** Ice chunks and snow thrown off where it hits, and snow falling off the beam. */
    private static void fireParticles(ClientWorld world, Line l) {
        Random r = world.random;
        Vec3d e = l.end();
        Vec3d n = l.normal();
        if (l.hitBlock()) {
            for (int i = 0; i < 3; i++) {
                Vec3d v = n.multiply(0.12 + r.nextDouble() * 0.12).add(r.nextGaussian() * 0.07, r.nextGaussian() * 0.07, r.nextGaussian() * 0.07);
                world.addParticle(ParticleTypes.SNOWFLAKE, e.x, e.y, e.z, v.x, v.y, v.z);
            }
            for (int i = 0; i < 3; i++) {
                Vec3d v = n.multiply(0.2).add(r.nextGaussian() * 0.12, r.nextGaussian() * 0.12, r.nextGaussian() * 0.12);
                world.addParticle(ICE_CHIPS, e.x, e.y, e.z, v.x, v.y, v.z);
            }
            // A little block of ice knocked loose now and then
            if (CHUNKS.size() < MAX_CHUNKS && r.nextInt(2) == 0) {
                Vec3d v = n.multiply(0.22 + r.nextDouble() * 0.15).add(r.nextGaussian() * 0.1, 0.1 + r.nextDouble() * 0.1, r.nextGaussian() * 0.1);
                float size = (2 + r.nextInt(3)) * PIXEL;
                CHUNKS.add(new Chunk(e.add(n.multiply(0.1)), v, ICES[r.nextInt(ICES.length)], size, 10f + r.nextFloat() * 25f));
            }
        }
        Vec3d span = e.subtract(l.origin());
        Vec3d at = l.origin().add(span.multiply(0.1 + r.nextDouble() * 0.9));
        world.addParticle(ParticleTypes.SNOWFLAKE, at.x, at.y, at.z, r.nextGaussian() * 0.01, -0.02, r.nextGaussian() * 0.01);
    }

    // ---------------------------------------------------------------------
    // In the world
    // ---------------------------------------------------------------------

    private static void renderWorld(WorldRenderContext context) {
        if (BEAMS.isEmpty() && CHUNKS.isEmpty()) return;
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
        long worldTime = client.world.getTime();

        for (Map.Entry<Integer, Beam> entry : BEAMS.entrySet()) {
            if (!(client.world.getEntityById(entry.getKey()) instanceof LivingEntity caster)) continue;
            Beam b = entry.getValue();
            float age = b.age + tickDelta;
            Line l = line(caster, tickDelta);
            boolean firstPerson = isLocalFirstPerson(caster);
            if (age < b.charge) drawCharge(matrices, buffers, l, age, age / b.charge, firstPerson);
            else drawBeam(matrices, buffers, l, age, age - b.charge, b.fire, firstPerson, tickDelta, worldTime);
        }
        for (Chunk c : CHUNKS) {
            float t = (c.age + tickDelta) / CHUNK_LIFE;
            float size = t < 0.7f ? c.size : c.size * (1f - (t - 0.7f) / 0.3f);
            iceBlock(matrices, buffers, c.prevPos.lerp(c.pos, tickDelta), size, (c.age + tickDelta) * c.spinSpeed, c.state);
        }
        buffers.draw();
    }

    private static void drawCharge(MatrixStack ms, VertexConsumerProvider buffers, Line l, float age, float p, boolean firstPerson) {
        Vec3d o = l.origin();
        float near = firstPerson ? NEAR_SCALE : 1f;
        VertexConsumer px = buffers.getBuffer(PIXELS);

        // Sight line: a dotted row of pixels to where it'll land, a bright dot marching outward along it
        double len = o.distanceTo(l.end());
        Vec3d dir = l.end().subtract(o).normalize();
        int dots = (int) (len / 0.5);
        float march = age * 0.9f;
        for (int i = 1; i <= dots; i++) {
            float wave = Math.max(0f, 1f - Math.abs(((i - march) % 12 + 12) % 12 - 6f) / 1.5f);
            float a = (0.2f + 0.45f * p) * (0.6f + 0.4f * wave);
            pixel(px, o.add(dir.multiply(i * 0.5)), PIXEL * 0.35f * (1f + wave * 0.5f), 0.6f, 0.9f, 1f, a);
        }
        pixel(px, l.end(), PIXEL * (0.8f + 0.4f * p), 0.8f, 0.95f, 1f, 0.3f + 0.6f * p);

        // A blue ice crystal growing at the tip, one pixel at a time, spinning faster and faster
        float crystal = (1 + (int) (p * 5.99f)) * PIXEL * near;
        iceBlock(ms, buffers, o, crystal, age * (6f + 30f * p), BLUE_ICE);

        // Little blocks of ice circling it and spiralling in
        Vec3d look = dir;
        Vec3d n1 = look.crossProduct(new Vec3d(0, 1, 0));
        n1 = n1.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : n1.normalize();
        Vec3d n2 = look.crossProduct(n1).normalize();
        double radius = (0.15 + 0.85 * (1 - p) * (1 - p)) * near;
        for (int k = 0; k < 6; k++) {
            double a = age * (0.15 + 0.35 * p) + k * Math.PI / 3;
            Vec3d at = o.add(n1.multiply(Math.cos(a) * radius)).add(n2.multiply(Math.sin(a) * radius));
            iceBlock(ms, buffers, at, 2 * PIXEL * near, age * 20f + k * 40f, ICES[k % ICES.length]);
        }
    }

    private static void drawBeam(MatrixStack ms, VertexConsumerProvider buffers, Line l, float age, float f, int fireTicks,
                                 boolean firstPerson, float tickDelta, long worldTime) {
        float strength = f < fireTicks ? 1f : MathHelper.clamp(1f - (f - fireTicks) / FADE_TICKS, 0f, 1f);
        if (strength <= 0f) return;
        Vec3d o = l.origin();
        // It shoots out over the first two ticks
        float reach = MathHelper.clamp(f / 2f, 0f, 1f);
        Vec3d end = o.lerp(l.end(), reach);
        Vec3d span = end.subtract(o);
        double len = span.length();
        if (len < 1e-3) return;
        Vec3d dir = span.multiply(1.0 / len);
        float near = firstPerson ? NEAR_SCALE : 1f;

        // A thump of thickness as it fires, and it narrows to a thread as it dies
        float w = (1f + 0.9f * (float) Math.exp(-f * 0.7f)) * strength;
        beaconBeam(ms, buffers, o, dir, len, 0.035f * w, 0.07f * w, tickDelta, worldTime);

        // Tiny blocks of ice tumbling down it
        for (int k = 0; k < 5; k++) {
            double u = (age * 0.07 + k / 5.0) % 1.0;
            iceBlock(ms, buffers, o.add(dir.multiply(u * len)), 2 * PIXEL * strength, age * 25f + k * 70f, ICES[k % ICES.length]);
        }

        // The focusing crystal at the staff
        iceBlock(ms, buffers, o, 4 * PIXEL * near * (0.6f + 0.4f * strength), age * 40f, BLUE_ICE);

        // Square frost rings spreading over whatever it hits, growing a pixel at a time
        if (reach >= 1f && l.hitBlock()) {
            VertexConsumer px = buffers.getBuffer(PIXELS);
            Vec3d nrm = l.normal();
            Vec3d e = l.end().add(nrm.multiply(0.01));
            Vec3d t1 = Math.abs(nrm.y) > 0.9 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0).crossProduct(nrm).normalize();
            Vec3d t2 = nrm.crossProduct(t1).normalize();
            for (int k = 0; k < 2; k++) {
                float phase = (age * 0.1f + k * 0.5f) % 1f;
                float half = (1 + (int) (phase * 12)) * PIXEL;
                squareRing(px, e, t1, t2, half, PIXEL * 0.5f, 0.6f, 0.9f, 1f, 0.8f * (1f - phase) * strength);
            }
            pixel(px, e, PIXEL * 1.5f * w, 0.9f, 1f, 1f, strength);
        }
    }

    // ---------------------------------------------------------------------
    // Pieces
    // ---------------------------------------------------------------------

    /** A thin beacon beam (the real texture, scrolling and turning) from {@code from} along {@code dir}. */
    private static void beaconBeam(MatrixStack ms, VertexConsumerProvider buffers, Vec3d from, Vec3d dir, double len,
                                   float inner, float outer, float tickDelta, long worldTime) {
        int blocks = Math.max(1, MathHelper.ceil(len));
        ms.push();
        ms.translate(from.x - cam.x, from.y - cam.y, from.z - cam.z);
        ms.multiply(new Quaternionf().rotationTo(0f, 1f, 0f, (float) dir.x, (float) dir.y, (float) dir.z));
        ms.scale(1f, (float) (len / blocks), 1f);
        ms.translate(-0.5, 0, -0.5); // renderBeam centres itself on a block
        BeaconBlockEntityRenderer.renderBeam(ms, buffers, BeaconBlockEntityRenderer.BEAM_TEXTURE, tickDelta, 2.5f,
                worldTime, 0, blocks, BEAM_COLOR, inner, outer);
        ms.pop();
    }

    /** A tiny, full-bright, tumbling block. */
    private static void iceBlock(MatrixStack ms, VertexConsumerProvider buffers, Vec3d at, float size, float spin, BlockState state) {
        if (size <= 0.001f) return;
        ms.push();
        ms.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
        ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(spin));
        ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(spin * 0.7f));
        ms.scale(size, size, size);
        ms.translate(-0.5, -0.5, -0.5);
        MinecraftClient.getInstance().getBlockRenderManager().renderBlockAsEntity(state, ms, buffers,
                LightmapTextureManager.MAX_LIGHT_COORDINATE, OverlayTexture.DEFAULT_UV);
        ms.pop();
    }

    private static void vertex(VertexConsumer vc, Vec3d p, float r, float g, float b, float a) {
        vc.vertex(pose, (float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z)).color(r, g, b, a);
    }

    /** A square pixel of light facing the camera. */
    private static void pixel(VertexConsumer vc, Vec3d c, float half, float r, float g, float b, float a) {
        if (a <= 0.003f) return;
        Vec3d x = camRight.multiply(half), y = camUp.multiply(half);
        vertex(vc, c.subtract(x).subtract(y), r, g, b, a);
        vertex(vc, c.add(x).subtract(y), r, g, b, a);
        vertex(vc, c.add(x).add(y), r, g, b, a);
        vertex(vc, c.subtract(x).add(y), r, g, b, a);
    }

    /** A square outline {@code half} out from {@code c}, lying in the plane of {@code u} and {@code v}. */
    private static void squareRing(VertexConsumer vc, Vec3d c, Vec3d u, Vec3d v, float half, float width,
                                   float r, float g, float b, float a) {
        if (a <= 0.003f) return;
        for (int side = 0; side < 4; side++) {
            // Each side as a strip: along 'along', sitting 'half' out along 'out'
            Vec3d out = switch (side) { case 0 -> u; case 1 -> v; case 2 -> u.multiply(-1); default -> v.multiply(-1); };
            Vec3d along = switch (side) { case 0, 2 -> v; default -> u; };
            Vec3d mid = c.add(out.multiply(half));
            Vec3d a0 = mid.subtract(along.multiply(half + width)), a1 = mid.add(along.multiply(half + width));
            vertex(vc, a0.subtract(out.multiply(width)), r, g, b, a);
            vertex(vc, a1.subtract(out.multiply(width)), r, g, b, a);
            vertex(vc, a1.add(out.multiply(width)), r, g, b, a);
            vertex(vc, a0.add(out.multiply(width)), r, g, b, a);
        }
    }

    // ---------------------------------------------------------------------
    // On screen (caster only)
    // ---------------------------------------------------------------------

    /** The caster's screen frosts over from the edges, like standing in powder snow. */
    private static void renderHud(DrawContext context, RenderTickCounter counter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.options.hudHidden) return;
        Beam b = BEAMS.get(client.player.getId());
        if (b == null) return;
        float age = b.age + counter.getTickDelta(false);
        float f = age - b.charge;

        float frost;
        if (f < 0) frost = 0.3f * (age / b.charge);
        else if (f < b.fire) frost = Math.min(0.75f, 0.3f + f * 0.1f);
        else frost = 0.75f * MathHelper.clamp(1f - (f - b.fire) / (FADE_TICKS + 5f), 0f, 1f);
        if (frost <= 0.01f) return;

        int w = context.getScaledWindowWidth();
        int h = context.getScaledWindowHeight();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        context.setShaderColor(1f, 1f, 1f, frost);
        context.drawTexture(FROST_OVERLAY, 0, 0, -90, 0f, 0f, w, h, w, h);
        context.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }
}
