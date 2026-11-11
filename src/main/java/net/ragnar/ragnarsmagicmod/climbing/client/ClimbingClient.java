package net.ragnar.ragnarsmagicmod.climbing.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.climbing.Climbing;
import net.ragnar.ragnarsmagicmod.item.custom.PassiveTomes;
import net.ragnar.ragnarsmagicmod.util.FairyForm;

/**
 * Client half of the Tome of Climbing (see Climbing). Each tick, after the player has moved: if they're in the air,
 * pushing forward into a wall, and either holding jump or crouching, they grab on. From then on, as long as they keep
 * pushing into the wall, jump climbs, crouch hangs and neither slides. It only ever pushes the player up (it slows a
 * fall, never a rise), so jumps, air jumps and leaps against a wall go exactly as high as they would anyway.
 * <p>
 * Sounds are the wall's own: a soft hit when you grab on, a quiet step every {@link Climbing#STEP} blocks up, slower
 * lower steps and a few crumbs of the block as you slide, and a step as you pull yourself over the top.
 */
public final class ClimbingClient {
    private ClimbingClient() {}

    /** How hard you're pulled over the top of a wall. */
    private static final double MANTLE_FORWARD = 0.2, MANTLE_UP = 0.25;

    private static boolean onWall, climbing;
    private static double soundY;
    private static int slideTicks;

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(ClimbingClient::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onWall = false);
    }

    /** True while the player is holding on to a wall (climbing, hanging or sliding). Other tomes step aside for it. */
    public static boolean onWall() {
        return onWall;
    }

    private static void tick(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p == null) {
            onWall = false;
            return;
        }
        if (client.isPaused() || p.input == null) return;
        boolean able = able(p);
        boolean forward = p.input.movementForward > 0;
        boolean jump = p.input.jumping, sneak = p.input.sneaking;
        BlockPos wall = able && !p.isOnGround() && p.horizontalCollision && forward ? Climbing.wallAt(p) : null;

        if (wall == null) {
            if (onWall) {
                if (climbing && able && forward && jump && toppedOut(p)) mantle(p);
                letGo();
            }
            return;
        }
        if (!onWall) {
            if (!jump && !sneak) return; // pushing into a wall alone does nothing
            grab(p, wall);
        }

        // Only ever push up: catch a fall, never slow a rise
        Vec3d v = p.getVelocity();
        double target = jump ? Climbing.CLIMB_SPEED : sneak ? 0.0 : -Climbing.SLIDE_SPEED;
        double vy = v.y < target ? Math.min(target, v.y + Climbing.GRIP) : v.y;
        p.setVelocity(v.x, vy, v.z);
        p.fallDistance = 0f;
        climbing = jump;

        sounds(p, wall, vy);
    }

    /** Places and states where climbing doesn't make sense. */
    private static boolean able(ClientPlayerEntity p) {
        return !p.isSpectator() && !p.getAbilities().flying && !p.hasVehicle() && !p.isTouchingWater() && !p.isInLava()
                && !p.isClimbing() && !p.isFallFlying() && !FairyForm.isFairy(p) && !net.ragnar.ragnarsmagicmod.groundpound.client.GroundPoundClient.active()
                && PassiveTomes.has(p, Climbing.TOME_OF_CLIMBING);
    }

    private static void grab(ClientPlayerEntity p, BlockPos wall) {
        onWall = true;
        soundY = p.getY();
        slideTicks = 0;
        ClientPlayNetworking.send(new Climbing.ClimbPayload(true));
        BlockSoundGroup g = p.getWorld().getBlockState(wall).getSoundGroup();
        boolean hard = p.getVelocity().y < -0.6;
        // A hand slapping onto the wall; catching a real fall scrapes too
        p.playSound(g.getHitSound(), g.getVolume() * (hard ? 0.6f : 0.35f), g.getPitch() * 0.9f);
        if (hard) {
            p.playSound(g.getBreakSound(), g.getVolume() * 0.25f, g.getPitch() * 0.7f);
            crumbs(p, wall, 6);
        }
    }

    private static void letGo() {
        onWall = false;
        climbing = false;
        ClientPlayNetworking.send(new Climbing.ClimbPayload(false));
    }

    /** True if the wall has just run out under you: still solid at your feet, but clear ahead at head height. */
    private static boolean toppedOut(ClientPlayerEntity p) {
        Vec3d ahead = Vec3d.fromPolar(0f, p.getYaw()).multiply(p.getWidth() / 2 + 0.3);
        World w = p.getWorld();
        BlockPos below = BlockPos.ofFloored(p.getX() + ahead.x, p.getY() - 0.4, p.getZ() + ahead.z);
        BlockPos head = BlockPos.ofFloored(p.getX() + ahead.x, p.getY() + p.getHeight() - 0.2, p.getZ() + ahead.z);
        return !w.getBlockState(below).getCollisionShape(w, below).isEmpty()
                && w.getBlockState(head).getCollisionShape(w, head).isEmpty();
    }

    /** Pulls you up over the top of the wall, with one firmer step on the block you climb onto. */
    private static void mantle(ClientPlayerEntity p) {
        Vec3d ahead = Vec3d.fromPolar(0f, p.getYaw()).multiply(MANTLE_FORWARD);
        Vec3d v = p.getVelocity();
        p.setVelocity(ahead.x, Math.max(v.y, MANTLE_UP), ahead.z);
        Vec3d at = Vec3d.fromPolar(0f, p.getYaw()).multiply(p.getWidth() / 2 + 0.3);
        BlockPos top = BlockPos.ofFloored(p.getX() + at.x, p.getY() - 0.4, p.getZ() + at.z);
        BlockSoundGroup g = p.getWorld().getBlockState(top).getSoundGroup();
        p.playSound(g.getStepSound(), g.getVolume() * 0.45f, g.getPitch() * 1.05f);
    }

    /** Quiet steps up the wall; slower, lower ones and a few crumbs as you slide down it. Hanging still is silent. */
    private static void sounds(ClientPlayerEntity p, BlockPos wall, double vy) {
        BlockSoundGroup g = p.getWorld().getBlockState(wall).getSoundGroup();
        if (vy > 0.05) {
            slideTicks = 0;
            if (p.getY() - soundY >= Climbing.STEP) {
                soundY = p.getY();
                p.playSound(g.getStepSound(), g.getVolume() * 0.3f, g.getPitch());
            }
        } else if (vy < -0.05) {
            if (soundY - p.getY() >= Climbing.STEP * 1.5) {
                soundY = p.getY();
                p.playSound(g.getStepSound(), g.getVolume() * 0.2f, g.getPitch() * 0.75f);
            }
            if (++slideTicks % 4 == 0) crumbs(p, wall, 1);
        } else {
            soundY = p.getY();
        }
    }

    /** A few bits of the wall's block at your hands. */
    private static void crumbs(ClientPlayerEntity p, BlockPos wall, int n) {
        World w = p.getWorld();
        BlockState s = w.getBlockState(wall);
        Vec3d hands = Vec3d.fromPolar(0f, p.getYaw()).multiply(p.getWidth() / 2 + 0.05);
        for (int i = 0; i < n; i++) {
            w.addParticle(new BlockStateParticleEffect(ParticleTypes.BLOCK, s),
                    p.getX() + hands.x + (w.random.nextDouble() - 0.5) * 0.4, p.getY() + p.getHeight() * 0.7,
                    p.getZ() + hands.z + (w.random.nextDouble() - 0.5) * 0.4, 0, 0, 0);
        }
    }
}
