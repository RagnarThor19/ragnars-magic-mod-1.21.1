package net.ragnar.ragnarsmagicmod.gliding.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.climbing.client.ClimbingClient;
import net.ragnar.ragnarsmagicmod.gliding.Gliding;
import net.ragnar.ragnarsmagicmod.item.custom.PassiveTomes;
import net.ragnar.ragnarsmagicmod.util.FairyForm;

/**
 * Client half of the Tome of Gliding (see Gliding). Keeps track of the top of each time you're in the air; once you've
 * dropped {@link Gliding#START_DROP} below it with jump held, you open up: a cloth snap and a soft wing beat, a little
 * puff of air, and then a quiet rush of wind that rises and falls with your speed for as long as you glide. Letting go
 * is silent; the wind just fades.
 */
public final class GlidingClient {
    private GlidingClient() {}

    private static boolean gliding;
    private static double topY;
    private static WindSound wind;

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(GlidingClient::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> gliding = false);
    }

    /** True while the player is gliding. */
    public static boolean gliding() {
        return gliding;
    }

    private static void tick(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p == null) {
            gliding = false;
            return;
        }
        if (client.isPaused() || p.input == null) return;

        boolean airborne = !p.isOnGround() && !p.isTouchingWater() && !p.isInLava() && !p.isClimbing() && !p.hasVehicle()
                && !p.isFallFlying() && !p.getAbilities().flying && !p.isSpectator();
        if (!airborne) {
            topY = p.getY();
            if (gliding) stop();
            return;
        }
        topY = Math.max(topY, p.getY());

        boolean able = p.input.jumping && !ClimbingClient.onWall() && !net.ragnar.ragnarsmagicmod.groundpound.client.GroundPoundClient.active() && !FairyForm.isFairy(p)
                && PassiveTomes.has(p, Gliding.TOME_OF_GLIDING);
        if (!gliding) {
            if (able && p.getVelocity().y < 0 && topY - p.getY() >= Gliding.START_DROP) start(p, client);
        } else if (!able) {
            stop();
        }
        if (gliding) glide(p);
    }

    private static void glide(ClientPlayerEntity p) {
        Vec3d v = p.getVelocity();
        // Catch the fall softly, then sink slowly
        double vy = v.y < -Gliding.FALL_SPEED ? Math.min(-Gliding.FALL_SPEED, v.y + Gliding.CATCH) : v.y;
        double vx = v.x, vz = v.z;
        if (p.input.movementForward > 0) {
            // Ease toward the way you're looking: speed up to glide speed, but keep any extra you came in with
            Vec3d look = Vec3d.fromPolar(0f, p.getYaw());
            double speed = Math.sqrt(vx * vx + vz * vz);
            double target = speed < Gliding.GLIDE_SPEED ? speed + (Gliding.GLIDE_SPEED - speed) * 0.12 : speed * 0.995;
            double hx = vx * 0.85 + look.x * target * 0.15, hz = vz * 0.85 + look.z * target * 0.15;
            double len = Math.sqrt(hx * hx + hz * hz);
            if (len > 1e-6) {
                vx = hx / len * target;
                vz = hz / len * target;
            }
        }
        p.setVelocity(vx, vy, vz);
        p.fallDistance = 0f;
    }

    private static void start(ClientPlayerEntity p, MinecraftClient client) {
        gliding = true;
        ClientPlayNetworking.send(new Gliding.GlidePayload(true));

        // A cloth snap as it catches the air, a soft wing beat under it, and a small puff from your shoulders
        p.playSound(SoundEvents.ITEM_ARMOR_EQUIP_ELYTRA.value(), 0.6f, 1.25f);
        p.playSound(SoundEvents.ENTITY_PHANTOM_FLAP, 0.25f, 1.5f);
        World w = p.getWorld();
        double y = p.getY() + p.getHeight() * 0.6;
        for (int i = 0; i < 6; i++) {
            double a = i * Math.PI * 2 / 6;
            w.addParticle(ParticleTypes.CLOUD, p.getX() + Math.cos(a) * 0.4, y, p.getZ() + Math.sin(a) * 0.4,
                    Math.cos(a) * 0.05, 0.03, Math.sin(a) * 0.05);
        }
        if (wind == null || wind.isDone()) {
            wind = new WindSound(p);
            client.getSoundManager().play(wind);
        }
    }

    private static void stop() {
        gliding = false;
        ClientPlayNetworking.send(new Gliding.GlidePayload(false));
    }

    /** A soft rush of air while you glide: louder and brighter the faster you go, fading out once you stop. */
    private static final class WindSound extends MovingSoundInstance {
        private final ClientPlayerEntity player;

        WindSound(ClientPlayerEntity player) {
            super(SoundEvents.ITEM_ELYTRA_FLYING, SoundCategory.PLAYERS, Random.create());
            this.player = player;
            this.repeat = true;
            this.repeatDelay = 0;
            this.volume = 0.0f;
            this.pitch = 1.1f;
            this.relative = true; // it's around your head, wherever you look
        }

        @Override
        public boolean shouldAlwaysPlay() {
            return true;
        }

        @Override
        public void tick() {
            if (player.isRemoved()) {
                setDone();
                return;
            }
            Vec3d v = player.getVelocity();
            float speed = (float) v.length();
            float want = gliding ? MathHelper.clamp(0.05f + speed * 0.5f, 0.05f, 0.3f) : 0f;
            volume += (want - volume) * (gliding ? 0.15f : 0.25f);
            pitch = 1.0f + MathHelper.clamp(speed, 0f, 0.6f) * 0.5f;
            if (!gliding && volume < 0.01f) setDone();
        }
    }
}
