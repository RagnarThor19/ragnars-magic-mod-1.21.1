package net.ragnar.ragnarsmagicmod.jumping.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.client.ScreenShake;
import net.ragnar.ragnarsmagicmod.jumping.Jumping;
import net.ragnar.ragnarsmagicmod.util.FairyForm;

/**
 * Client half of the Tome of Jumping (see Jumping): spots a fresh press of jump while you're in the air, soon enough
 * after your last jump, and launches you - steering you toward whatever direction you're holding, so you can change
 * course mid-air - with a puff of air under your feet. The third jump is the big one.
 */
public final class JumpingClient {
    private JumpingClient() {}

    private static final double FIRST_BOOST = 0.52;
    private static final double LAST_BOOST = 0.6;
    /** Air jumps keep at least this much speed in the direction you're steering. */
    private static final double MIN_STEER_SPEED = 0.28;

    private static boolean jumpWasDown, wasOnGround;
    /** {@code player.age} of the last jump (from the ground or in the air). */
    private static int lastJump = -1000;
    private static int used;
    /** Whether the jumps since leaving the ground started with a real jump (not a walk off a ledge). */
    private static boolean jumped;

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(JumpingClient::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
    }

    private static void reset() {
        used = 0;
        jumped = false;
        lastJump = -1000;
    }

    private static void tick(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p == null || client.isPaused()) return;
        boolean jumpDown = client.options.jumpKey.isPressed();
        boolean pressed = jumpDown && !jumpWasDown && client.currentScreen == null;
        jumpWasDown = jumpDown;

        if (p.isOnGround() || grounded(p)) {
            wasOnGround = p.isOnGround();
            reset();
            return;
        }
        if (wasOnGround) {
            // Just left the ground: a jump if we're going up with jump held, otherwise we walked off something
            wasOnGround = false;
            if (jumpDown && p.getVelocity().y > 0) {
                jumped = true;
                lastJump = p.age;
            }
            return;
        }
        if (pressed && jumped && used < Jumping.AIR_JUMPS && p.age - lastJump <= Jumping.WINDOW && Jumping.canAirJump(p)) {
            airJump(p);
        }
    }

    /** Places where jumping works its own way (or not at all), which also end a chain of jumps. */
    private static boolean grounded(ClientPlayerEntity p) {
        return p.isTouchingWater() || p.isInLava() || p.isClimbing() || p.hasVehicle() || p.isFallFlying()
                || p.getAbilities().flying || FairyForm.isFairy(p);
    }

    private static void airJump(ClientPlayerEntity p) {
        used++;
        lastJump = p.age;
        boolean last = used == Jumping.AIR_JUMPS;

        Vec3d v = p.getVelocity();
        double vx = v.x, vz = v.z;
        float fwd = p.input.movementForward, side = p.input.movementSideways;
        if (fwd * fwd + side * side > 0.01f) {
            // Turn all your momentum toward where you're steering, keeping a little speed at least
            float yaw = p.getYaw() * MathHelper.RADIANS_PER_DEGREE;
            double sin = MathHelper.sin(yaw), cos = MathHelper.cos(yaw);
            double dx = side * cos - fwd * sin, dz = fwd * cos + side * sin;
            double len = Math.sqrt(dx * dx + dz * dz);
            double speed = Math.max(Math.sqrt(vx * vx + vz * vz), MIN_STEER_SPEED);
            vx = dx / len * speed;
            vz = dz / len * speed;
        }
        p.setVelocity(vx, last ? LAST_BOOST : FIRST_BOOST, vz);
        p.fallDistance = 0f;
        ClientPlayNetworking.send(new Jumping.AirJumpPayload(used));
        effects(p, last);
    }

    /** A ring of air blown out under your feet; the last jump adds sparks, a brighter chime and a little kick. */
    private static void effects(ClientPlayerEntity p, boolean last) {
        World w = p.getWorld();
        double x = p.getX(), y = p.getY(), z = p.getZ();
        int count = last ? 20 : 14;
        double speed = last ? 0.22 : 0.16;
        for (int i = 0; i < count; i++) {
            double a = i * Math.PI * 2 / count;
            double cx = Math.cos(a), cz = Math.sin(a);
            w.addParticle(ParticleTypes.CLOUD, x + cx * 0.3, y, z + cz * 0.3, cx * speed, -0.03, cz * speed);
        }
        w.addParticle(ParticleTypes.GUST_EMITTER_SMALL, x, y, z, 0, 0, 0);
        if (last) {
            for (int i = 0; i < 12; i++) {
                w.addParticle(ParticleTypes.END_ROD, x, y + 0.1, z,
                        (w.random.nextDouble() - 0.5) * 0.25, -0.05 - w.random.nextDouble() * 0.1, (w.random.nextDouble() - 0.5) * 0.25);
            }
            ScreenShake.kick(0.07f, 4);
        }
        w.playSound(x, y, z, SoundEvents.ENTITY_BREEZE_JUMP, SoundCategory.PLAYERS, 0.7f, last ? 1.6f : 1.3f, false);
        w.playSound(x, y, z, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.0f, last ? 1.9f : 1.4f, false);
        if (last) w.playSound(x, y, z, SoundEvents.ENTITY_BREEZE_WIND_BURST.value(), SoundCategory.PLAYERS, 0.5f, 1.5f, false);
    }
}
