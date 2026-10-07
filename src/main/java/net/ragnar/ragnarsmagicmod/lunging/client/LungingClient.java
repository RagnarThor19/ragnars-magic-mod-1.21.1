package net.ragnar.ragnarsmagicmod.lunging.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.lunging.Lunging;

import java.util.Comparator;

/**
 * Client half of the Tome of Lunging (see Lunging). The lunge is a few ticks of fixed speed straight ahead along the
 * ground; after each one it looks for something in reach of the blade. The first thing it finds gets stabbed (the
 * server lands the blow) and you're launched back up and away, flying in an arc that comes down about
 * {@link Lunging#BACK_DISTANCE} blocks back; if nothing's found by the end, or a wall stops you, you just stop.
 * <p>
 * Only a fresh press lunges: holding right-click down does nothing more. With a shield (or anything else you use) in
 * your off hand, pressing right-click raises it as usual, and the lunge waits to see if you let go again within
 * {@link #TAP_TICKS}: a tap lunges, a hold is just blocking. No particles: just the swing, a swish and the hit.
 */
public final class LungingClient {
    private LungingClient() {}

    /** How far past the front of you the blade reaches while lunging. */
    private static final double BLADE = 1.3;
    private static final double LUNGE_SPEED = Lunging.LUNGE_DISTANCE / Lunging.LUNGE_TICKS;
    /**
     * The launch back: one push, then ordinary gravity and air drag do the rest. Up at 0.4 keeps you in the air for
     * about 10 ticks (peaking a block up), over which drag lets 0.7 carry you about 5 blocks.
     */
    private static final double LAUNCH_BACK = 0.7, LAUNCH_UP = 0.4;
    /** Gives up waiting to land after this long (and stops keeping fall damage away). */
    private static final int MAX_FLIGHT = 40;

    private enum Phase { NONE, LUNGE, BACK }

    /** The longest a right-click can be held and still count as a tap. */
    private static final int TAP_TICKS = 5;

    private static Phase phase = Phase.NONE;
    /** True once right-click has been let go since the last press we acted on. */
    private static boolean armed = true;
    /** With something in the off hand: a press waiting to turn out a tap or a hold, and when it began. */
    private static boolean pending;
    private static int pressedAt;
    private static int ticks;
    private static Vec3d dir = Vec3d.ZERO;

    public static void init() {
        Lunging.clientUse = LungingClient::use;
        ClientTickEvents.END_CLIENT_TICK.register(LungingClient::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            phase = Phase.NONE;
            pending = false;
        });
    }

    public static boolean isLunging() {
        return phase != Phase.NONE;
    }

    /** A right-click with a sword (see Lunging.clientUse). True if it lunged. */
    private static boolean use(PlayerEntity player) {
        if (!(player instanceof ClientPlayerEntity p) || !armed) return false;
        armed = false;
        if (Lunging.tapOnly(p)) {
            // Let the shield come up; whether this lunges is decided when you let go
            pending = true;
            pressedAt = p.age;
            return false;
        }
        if (phase != Phase.NONE || !Lunging.canLunge(p)) return false;
        lunge(p);
        return true;
    }

    private static void lunge(ClientPlayerEntity p) {
        float yaw = p.getYaw() * MathHelper.RADIANS_PER_DEGREE;
        dir = new Vec3d(-MathHelper.sin(yaw), 0, MathHelper.cos(yaw));
        phase = Phase.LUNGE;
        ticks = 0;
        push(p, dir.multiply(LUNGE_SPEED), p.isOnGround() ? 0 : Math.max(p.getVelocity().y, 0));
        ClientPlayNetworking.send(new Lunging.StartPayload());
        p.getWorld().playSound(p.getX(), p.getY(), p.getZ(), SoundEvents.ITEM_TRIDENT_THROW.value(), SoundCategory.PLAYERS, 0.7f, 1.5f, false);
    }

    private static void tick(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p == null || client.isPaused()) return;
        boolean down = client.options.useKey.isPressed();
        if (pending) {
            if (down) {
                if (p.age - pressedAt > TAP_TICKS) pending = false; // held: it's a block, not a lunge
            } else {
                pending = false;
                if (phase == Phase.NONE && Lunging.triggers(p, Hand.MAIN_HAND) && Lunging.canLunge(p)) {
                    if (p.isUsingItem()) client.interactionManager.stopUsingItem(p);
                    armed = true;
                    lunge(p);
                    return; // its first tick of movement is the next one
                }
            }
        }
        if (!down) armed = true;
        if (phase == Phase.NONE) return;
        if (p == null || !p.isAlive()) {
            phase = Phase.NONE;
            return;
        }
        ticks++;
        p.fallDistance = 0;
        if (phase == Phase.LUNGE) {
            Entity target = inReach(p);
            if (target != null) {
                stab(p, target);
                return;
            }
            if (ticks >= Lunging.LUNGE_TICKS || p.horizontalCollision) {
                stop(p, 0.1);
                return;
            }
            push(p, dir.multiply(LUNGE_SPEED), p.isOnGround() ? 0 : Math.min(p.getVelocity().y, 0.05));
        } else if ((p.isOnGround() && ticks > 2) || p.isTouchingWater() || ticks > MAX_FLIGHT) {
            // Flying back under its own momentum: done once you're down
            phase = Phase.NONE;
        }
    }

    /** The nearest thing the blade reaches from where you are now. */
    private static Entity inReach(ClientPlayerEntity p) {
        Box reach = p.getBoundingBox().stretch(dir.multiply(BLADE)).expand(0.3, 0.1, 0.3);
        return p.getWorld().getOtherEntities(p, reach, e -> Lunging.canStab(p, e)).stream()
                .min(Comparator.comparingDouble(e -> e.squaredDistanceTo(p)))
                .orElse(null);
    }

    private static void stab(ClientPlayerEntity p, Entity target) {
        ClientPlayNetworking.send(new Lunging.StabPayload(target.getId()));
        p.swingHand(Hand.MAIN_HAND);
        p.getWorld().playSound(p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_BREEZE_JUMP, SoundCategory.PLAYERS, 0.4f, 1.7f, false);
        phase = Phase.BACK;
        ticks = 0;
        push(p, dir.multiply(-LAUNCH_BACK), LAUNCH_UP);
    }

    /** Ends the lunge, keeping {@code keep} of your speed along the ground. */
    private static void stop(ClientPlayerEntity p, double keep) {
        phase = Phase.NONE;
        Vec3d v = p.getVelocity();
        p.setVelocity(v.x * keep, v.y, v.z * keep);
    }

    private static void push(ClientPlayerEntity p, Vec3d horizontal, double vy) {
        p.setVelocity(horizontal.x, vy, horizontal.z);
    }
}
