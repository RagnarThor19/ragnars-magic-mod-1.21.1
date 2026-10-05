package net.ragnar.ragnarsmagicmod.beaming.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.ragnar.ragnarsmagicmod.beaming.Beaming;
import net.ragnar.ragnarsmagicmod.beaming.BeamingSpell;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Client half of the Tome of Beaming (see Beaming). The beam is a solid square bar of light, spinning about its own
 * length inside a counter-turning glow, with square rings streaming down it to the target. It spools up from a
 * thread when you start, runs from teal to angry orange as the charge burns down, and hammers a spinning, sparking
 * cube of light into whatever it's hitting. A low buzzing hum follows each caster while they fire, rising in pitch
 * with the heat.
 */
public final class BeamingClient {
    private BeamingClient() {}

    /** Glowing, additive: the core, the haze, the rings and the sparks. */
    static final RenderLayer GLOW = RenderLayer.of("ragnarsmagicmod_beaming_glow",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 16384, false, true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.LIGHTNING_TRANSPARENCY)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    /** The solid coloured bar at the heart of the beam. */
    static final RenderLayer SOLID = RenderLayer.of("ragnarsmagicmod_beaming_solid",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 4096, false, true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.TRANSLUCENT_TRANSPARENCY)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    private static final int SPOOL_TICKS = 4, FADE_TICKS = 4;

    private static final class View {
        boolean firing;
        float heat;
        int age, fade;
        BeamSound hum, buzz;
    }

    private static final Map<Integer, View> VIEWS = new HashMap<>();

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(Beaming.BeamPayload.ID, (payload, context) -> {
            View v = VIEWS.computeIfAbsent(payload.entityId(), k -> new View());
            v.heat = payload.heat();
            if (payload.firing() && !v.firing) {
                v.age = 0;
                v.fade = FADE_TICKS;
                Entity e = context.client().world != null ? context.client().world.getEntityById(payload.entityId()) : null;
                if (e instanceof PlayerEntity p) {
                    v.hum = BeamSound.start(context.client(), p, v, SoundEvents.BLOCK_CONDUIT_AMBIENT, 1.0f, 0.85f);
                    v.buzz = BeamSound.start(context.client(), p, v, SoundEvents.ENTITY_BEE_LOOP_AGGRESSIVE, 0.6f, 0.35f);
                }
            }
            v.firing = payload.firing();
        });
        ClientPlayNetworking.registerGlobalReceiver(Beaming.FuelPayload.ID, (payload, context) -> BeamingSpell.clientFuel = payload.fuel());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            VIEWS.clear();
            BeamingSpell.clientFuel = Beaming.FUEL_TICKS;
        });
        ClientTickEvents.END_CLIENT_TICK.register(BeamingClient::tick);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(BeamingClient::render);
    }

    private static void tick(MinecraftClient client) {
        if (client.isPaused() || VIEWS.isEmpty()) return;
        Iterator<Map.Entry<Integer, View>> it = VIEWS.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            View v = entry.getValue();
            boolean self = client.player != null && entry.getKey() == client.player.getId();
            if (v.firing) {
                v.age++;
                v.heat = self ? 1f - (float) BeamingSpell.clientFuel / Beaming.FUEL_TICKS : Math.min(1f, v.heat + 1f / Beaming.FUEL_TICKS);
            } else if (--v.fade <= 0) {
                it.remove();
            }
        }
    }

    /** The beam's colour at this heat: teal when fresh, orange as the charge runs out. */
    private static float[] color(float heat) {
        return new float[]{MathHelper.lerp(heat, 0.2f, 1f), MathHelper.lerp(heat, 1f, 0.42f), MathHelper.lerp(heat, 0.85f, 0.08f)};
    }

    // ---------------------------------------------------------------------
    // The beam
    // ---------------------------------------------------------------------

    private static void render(WorldRenderContext context) {
        if (VIEWS.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;
        float td = context.tickCounter().getTickDelta(false);
        Vec3d cam = context.camera().getPos();
        MatrixStack ms = context.matrixStack() != null ? context.matrixStack() : new MatrixStack();
        Matrix4f pose = ms.peek().getPositionMatrix();
        VertexConsumerProvider.Immediate buffers = client.getBufferBuilders().getEntityVertexConsumers();
        float t = client.world.getTime() + td;

        for (int pass = 0; pass < 2; pass++) {
            boolean solid = pass == 0;
            VertexConsumer vc = buffers.getBuffer(solid ? SOLID : GLOW);
            for (var entry : VIEWS.entrySet()) {
                View v = entry.getValue();
                if (!(client.world.getEntityById(entry.getKey()) instanceof PlayerEntity p)) continue;
                if (v.firing && !p.isUsingItem()) continue;
                float spool = MathHelper.clamp((v.age + td) / SPOOL_TICKS, 0f, 1f);
                float fade = v.firing ? 1f : MathHelper.clamp((v.fade - td) / FADE_TICKS, 0f, 1f);
                beam(vc, pose, cam, client, p, v, td, t, spool * (v.firing ? 1f : fade), fade, solid);
            }
            buffers.draw(solid ? SOLID : GLOW);
        }
    }

    private static void beam(VertexConsumer vc, Matrix4f pose, Vec3d cam, MinecraftClient client, PlayerEntity p, View v,
                             float td, float t, float width, float alpha, boolean solid) {
        boolean own = p == client.player && client.options.getPerspective().isFirstPerson();
        Vec3d start = own ? staffTip(p, td) : Beaming.origin(p, td);
        HitResult hit = Beaming.trace(client.world, p, td);
        Vec3d end = hit.getPos();
        Vec3d span = end.subtract(start);
        double len = span.length();
        if (len < 0.05 || width <= 0.01f) return;
        Vec3d dir = span.multiply(1 / len);
        float[] c = color(v.heat);
        Vec3d s = start.subtract(cam), e = end.subtract(cam);

        // Seen from the caster's own eyes, the beam thins toward the staff and its haze and rings only start a way
        // out, so it never fills the screen
        double near = own ? Math.min(2.5, len * 0.5) : 0.0;
        Vec3d n = s.add(dir.multiply(near));
        if (solid) {
            // The bar itself, spinning on its length
            if (own) BeamDraw.bar(vc, pose, s, n, 0.035f * width, t * 0.25f, c[0], c[1], c[2], 0.9f * alpha);
            BeamDraw.bar(vc, pose, n, e, 0.075f * width, t * 0.25f, c[0], c[1], c[2], 0.9f * alpha);
            return;
        }
        float wobble = 1f + 0.08f * MathHelper.sin(t * 1.7f);
        BeamDraw.bar(vc, pose, s, e, (own ? 0.018f : 0.03f) * width, t * 0.25f, 1f, 1f, 1f, 0.9f * alpha);
        if (own) BeamDraw.bar(vc, pose, n, e, 0.03f * width, t * 0.25f, 1f, 1f, 1f, 0.9f * alpha);
        BeamDraw.bar(vc, pose, n, e, 0.16f * width * wobble, -t * 0.15f, c[0], c[1], c[2], 0.2f * alpha);
        BeamDraw.bar(vc, pose, n, e, 0.24f * width * wobble, t * 0.08f, c[0], c[1], c[2], 0.07f * alpha);

        // Square rings streaming down the beam to the target
        double gap = 1.25;
        for (double d = (t * 0.9) % gap; d < len; d += gap) {
            if (d < near) continue;
            Vec3d at = s.add(dir.multiply(d));
            float pop = 0.17f + 0.03f * MathHelper.sin(t * 0.8f + (float) d);
            BeamDraw.frame(vc, pose, at, dir, pop * width, 0.018f * width, t * 0.3f + (float) d,
                    MathHelper.lerp(0.35f, c[0], 1f), MathHelper.lerp(0.35f, c[1], 1f), MathHelper.lerp(0.35f, c[2], 1f), 0.75f * alpha);
        }
        // The muzzle: a tight spinning ring where it leaves the staff
        float muzzle = own ? 0.05f : 0.12f;
        BeamDraw.frame(vc, pose, s.add(dir.multiply(0.04)), dir, (muzzle + 0.02f * MathHelper.sin(t * 2.1f)) * width, (own ? 0.008f : 0.02f) * width,
                -t * 0.5f, c[0], c[1], c[2], 0.9f * alpha);

        if (hit.getType() == HitResult.Type.MISS) return;
        // The impact: a spinning cube of light hammering the target, spitting sparks back off it
        float throb = 0.12f + 0.05f * Math.abs(MathHelper.sin(t * 1.3f));
        BeamDraw.bar(vc, pose, e.subtract(dir.multiply(throb)), e.add(dir.multiply(throb)), throb * width, t * 0.4f, c[0], c[1], c[2], 0.7f * alpha);
        BeamDraw.bar(vc, pose, e.subtract(dir.multiply(0.06)), e.add(dir.multiply(0.06)), 0.06f * width, -t * 0.6f, 1f, 1f, 1f, 0.9f * alpha);
        Vec3d normal = hit instanceof BlockHitResult bh ? Vec3d.of(bh.getSide().getVector()) : dir.negate();
        Random r = Random.create((long) (t * 2) * 31L + p.getId());
        for (int i = 0; i < 6; i++) {
            Vec3d spray = normal.multiply(0.8).add(dir.multiply(-0.6))
                    .add(new Vec3d(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).multiply(0.55)).normalize();
            double l = 0.25 + r.nextDouble() * 0.5;
            BeamDraw.bar(vc, pose, e, e.add(spray.multiply(l * width)), 0.012f, 0f,
                    MathHelper.lerp(0.5f, c[0], 1f), MathHelper.lerp(0.5f, c[1], 1f), MathHelper.lerp(0.5f, c[2], 1f), 0.9f * alpha);
        }
    }

    /** Where the staff's tip sits on screen in first person: a little ahead, low and to the right of the eyes. */
    private static Vec3d staffTip(PlayerEntity p, float td) {
        Vec3d eye = p.getCameraPosVec(td);
        Vec3d look = p.getRotationVec(td);
        Vec3d right = look.crossProduct(new Vec3d(0, 1, 0));
        right = right.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : right.normalize();
        Vec3d up = right.crossProduct(look).normalize();
        double side = p.getMainArm() == net.minecraft.util.Arm.RIGHT ? 1 : -1;
        return eye.add(look.multiply(1.0)).add(right.multiply(0.3 * side)).add(up.multiply(-0.3));
    }

    // ---------------------------------------------------------------------
    // The hum
    // ---------------------------------------------------------------------

    /** A loop that follows a caster while they fire, rising in pitch as the beam heats up. */
    private static final class BeamSound extends MovingSoundInstance {
        private final PlayerEntity player;
        private final View view;
        private final float baseVolume, basePitch;

        private BeamSound(PlayerEntity player, View view, SoundEvent sound, float volume, float pitch) {
            super(sound, SoundCategory.PLAYERS, SoundInstance.createRandom());
            this.player = player;
            this.view = view;
            this.baseVolume = volume;
            this.basePitch = pitch;
            this.repeat = true;
            this.repeatDelay = 0;
            this.volume = volume;
            this.pitch = pitch;
            this.x = player.getX();
            this.y = player.getEyeY();
            this.z = player.getZ();
        }

        static BeamSound start(MinecraftClient client, PlayerEntity player, View view, SoundEvent sound, float volume, float pitch) {
            BeamSound s = new BeamSound(player, view, sound, volume, pitch);
            client.getSoundManager().play(s);
            return s;
        }

        @Override
        public boolean shouldAlwaysPlay() {
            return true;
        }

        @Override
        public void tick() {
            if (player.isRemoved() || !view.firing || !player.isUsingItem() && view.age > 2) {
                setDone();
                return;
            }
            x = player.getX();
            y = player.getEyeY();
            z = player.getZ();
            volume = baseVolume;
            pitch = basePitch * (1f + 0.3f * view.heat);
        }
    }
}
