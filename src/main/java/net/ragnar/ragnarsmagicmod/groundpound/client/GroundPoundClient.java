package net.ragnar.ragnarsmagicmod.groundpound.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.client.ScreenShake;
import net.ragnar.ragnarsmagicmod.climbing.Climbing;
import net.ragnar.ragnarsmagicmod.climbing.client.ClimbingClient;
import net.ragnar.ragnarsmagicmod.groundpound.GroundPound;
import net.ragnar.ragnarsmagicmod.item.custom.PassiveTomes;
import net.ragnar.ragnarsmagicmod.util.FairyForm;

/**
 * Client half of the Tome of Ground Pound (see GroundPound). A fresh tap of crouch in the air, high enough up: you hang
 * for {@link #HANG_TICKS} ticks (tucked, with a little puff), then drop straight down at slam speed, the air streaming
 * past. On the ground: a jolt of the camera and the server's burst. Landing in water just ends it.
 */
public final class GroundPoundClient {
    private GroundPoundClient() {}

    private static final int HANG_TICKS = 4;
    /** How fast the slam drops you, at least (a normal fall tops out near 3.9). */
    private static final double SLAM_SPEED = 1.6;

    private static boolean sneakWasDown;
    /** -1 when not pounding; otherwise ticks since the pound began. */
    private static int pound = -1;
    private static double startY;

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(GroundPoundClient::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> pound = -1);
    }

    /** True from the tap until the landing. Other movement tomes step aside for it. */
    public static boolean active() {
        return pound >= 0;
    }

    private static void tick(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p == null) {
            pound = -1;
            return;
        }
        if (client.isPaused() || p.input == null) return;
        boolean sneak = p.input.sneaking;
        boolean tapped = sneak && !sneakWasDown;
        sneakWasDown = sneak;

        if (pound < 0) {
            if (tapped && canStart(p)) begin(p);
            return;
        }
        if (!able(p) || p.isTouchingWater() || p.isInLava()) {
            end(GroundPound.CANCELLED);
            return;
        }
        if (p.isOnGround()) {
            land(p);
            return;
        }
        pound++;
        Vec3d v = p.getVelocity();
        if (pound <= HANG_TICKS) {
            // The hang: stopped dead in the air for a moment
            p.setVelocity(0, 0.02, 0);
        } else {
            if (pound == HANG_TICKS + 1) p.playSound(SoundEvents.ITEM_MACE_SMASH_AIR, 0.8f, 0.8f);
            p.setVelocity(0, Math.min(v.y, -SLAM_SPEED), 0);
            // The air streaming up past you
            World w = p.getWorld();
            w.addParticle(ParticleTypes.CLOUD, p.getX() + (w.random.nextDouble() - 0.5) * 0.6, p.getY() + p.getHeight(),
                    p.getZ() + (w.random.nextDouble() - 0.5) * 0.6, 0, 0.3, 0);
        }
    }

    /** Places and states where pounding doesn't make sense. */
    private static boolean able(ClientPlayerEntity p) {
        return !p.isSpectator() && !p.getAbilities().flying && !p.hasVehicle() && !p.isClimbing() && !p.isFallFlying()
                && !FairyForm.isFairy(p) && PassiveTomes.has(p, GroundPound.TOME_OF_GROUND_POUND);
    }

    /** In the air, high enough up, and not about to grab a wall instead (Climbing's crouch-to-hang). */
    private static boolean canStart(ClientPlayerEntity p) {
        if (p.isOnGround() || p.isTouchingWater() || p.isInLava() || !able(p) || ClimbingClient.onWall()) return false;
        if (p.horizontalCollision && p.input.movementForward > 0 && Climbing.wallAt(p) != null
                && PassiveTomes.has(p, Climbing.TOME_OF_CLIMBING)) return false;
        Vec3d feet = p.getPos();
        HitResult hit = p.getWorld().raycast(new RaycastContext(feet, feet.add(0, -GroundPound.MIN_HEIGHT, 0),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.ANY, p));
        return hit.getType() == HitResult.Type.MISS;
    }

    private static void begin(ClientPlayerEntity p) {
        pound = 0;
        startY = p.getY();
        p.setVelocity(0, 0.02, 0);
        ClientPlayNetworking.send(new GroundPound.PoundPayload(GroundPound.START));
        // Tucking in: a soft rustle and a small puff around you
        p.playSound(SoundEvents.ITEM_ARMOR_EQUIP_GENERIC.value(), 0.6f, 1.4f);
        World w = p.getWorld();
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI * 2 / 8;
            w.addParticle(ParticleTypes.POOF, p.getX() + Math.cos(a) * 0.5, p.getY() + 0.9, p.getZ() + Math.sin(a) * 0.5,
                    Math.cos(a) * 0.05, 0, Math.sin(a) * 0.05);
        }
    }

    private static void land(ClientPlayerEntity p) {
        float k = GroundPound.strength(startY - p.getY());
        ScreenShake.kick(0.12f + 0.25f * k, 6 + (int) (6 * k));
        end(GroundPound.LANDED);
    }

    private static void end(int phase) {
        pound = -1;
        ClientPlayNetworking.send(new GroundPound.PoundPayload(phase));
    }
}
