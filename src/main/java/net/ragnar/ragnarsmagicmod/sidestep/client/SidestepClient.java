package net.ragnar.ragnarsmagicmod.sidestep.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.climbing.client.ClimbingClient;
import net.ragnar.ragnarsmagicmod.groundpound.client.GroundPoundClient;
import net.ragnar.ragnarsmagicmod.item.custom.PassiveTomes;
import net.ragnar.ragnarsmagicmod.sidestep.Sidestep;
import net.ragnar.ragnarsmagicmod.util.FairyForm;

/**
 * Client half of the Tome of Sidestep (see Sidestep). Watches left and right for a double tap; a sidestep then holds
 * you at dash speed sideways for {@link Sidestep#DASH_TICKS} ticks - on top of whatever you were doing forward or back -
 * and stops you dead at the end, so it's the same crisp {@link Sidestep#DISTANCE} blocks on the ground or in the air.
 * <p>
 * The feel: a short airy whoosh, a column of puffs left standing where you were (your afterimage), and a little dust
 * kicked up along the ground.
 */
public final class SidestepClient {
    private SidestepClient() {}

    private static boolean leftWasDown, rightWasDown;
    /** {@code player.age} of the last tap of each key, and of the last sidestep. */
    private static int leftTap = -1000, rightTap = -1000, lastStep = -1000;
    /** Ticks of dash left; and the dash itself, sideways, and the forward/back motion it rides on. */
    private static int dashing;
    private static Vec3d side = Vec3d.ZERO, base = Vec3d.ZERO;

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(SidestepClient::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> dashing = 0);
    }

    private static void tick(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p == null || client.isPaused()) return;
        boolean left = client.options.leftKey.isPressed(), right = client.options.rightKey.isPressed();
        boolean leftTapped = left && !leftWasDown, rightTapped = right && !rightWasDown;
        leftWasDown = left;
        rightWasDown = right;

        if (dashing > 0) {
            dash(p);
            return;
        }
        if (leftTapped) {
            if (p.age - leftTap <= Sidestep.DOUBLE_TAP && ready(p, client)) start(p, false);
            leftTap = dashing > 0 ? -1000 : p.age;
        } else if (rightTapped) {
            if (p.age - rightTap <= Sidestep.DOUBLE_TAP && ready(p, client)) start(p, true);
            rightTap = dashing > 0 ? -1000 : p.age;
        }
    }

    /** Off cooldown, not crouching (bridging is safe), and somewhere a dodge makes sense. */
    private static boolean ready(ClientPlayerEntity p, MinecraftClient client) {
        return client.currentScreen == null && p.age - lastStep >= Sidestep.COOLDOWN && !p.isSneaking() && !p.input.sneaking
                && !p.isSpectator() && !p.getAbilities().flying && !p.hasVehicle() && !p.isTouchingWater() && !p.isInLava()
                && !p.isClimbing() && !p.isFallFlying() && !FairyForm.isFairy(p) && !ClimbingClient.onWall()
                && !GroundPoundClient.active() && PassiveTomes.has(p, Sidestep.TOME_OF_SIDESTEP);
    }

    private static void start(ClientPlayerEntity p, boolean right) {
        lastStep = p.age;
        // One more than the ticks of movement: each one is set at the end of a tick and moves you on the next
        dashing = Sidestep.DASH_TICKS + 1;
        float yaw = p.getYaw() * MathHelper.RADIANS_PER_DEGREE;
        // The player's left, flat (the same way vanilla turns strafing into movement)
        Vec3d leftDir = new Vec3d(MathHelper.cos(yaw), 0, MathHelper.sin(yaw));
        side = (right ? leftDir.negate() : leftDir).multiply(Sidestep.DISTANCE / Sidestep.DASH_TICKS);
        // Keep whatever you were doing forward or back; drop what you had sideways
        Vec3d fwd = new Vec3d(-MathHelper.sin(yaw), 0, MathHelper.cos(yaw));
        Vec3d v = p.getVelocity();
        base = fwd.multiply(v.x * fwd.x + v.z * fwd.z);
        ClientPlayNetworking.send(new Sidestep.SidestepPayload(right));

        p.playSound(SoundEvents.ENTITY_BREEZE_SLIDE, 0.7f, 1.35f);
        // Your afterimage: a column of puffs where you stood
        World w = p.getWorld();
        for (int i = 0; i < 6; i++) {
            w.addParticle(ParticleTypes.POOF, p.getX() + (w.random.nextDouble() - 0.5) * 0.3, p.getY() + 0.2 + i * 0.3,
                    p.getZ() + (w.random.nextDouble() - 0.5) * 0.3, -side.x * 0.05, 0.005, -side.z * 0.05);
        }
        dash(p);
    }

    private static void dash(ClientPlayerEntity p) {
        dashing--;
        Vec3d v = p.getVelocity();
        if (dashing > 0) {
            p.setVelocity(base.x + side.x, v.y, base.z + side.z);
            if (p.isOnGround()) kickDust(p);
        } else {
            // And stop dead: back to just what you were doing forward or back
            p.setVelocity(base.x, v.y, base.z);
        }
    }

    /** A puff of the ground's own dust behind your feet. */
    private static void kickDust(ClientPlayerEntity p) {
        World w = p.getWorld();
        BlockPos below = BlockPos.ofFloored(p.getX(), p.getY() - 0.2, p.getZ());
        BlockState ground = w.getBlockState(below);
        if (ground.isAir()) return;
        for (int i = 0; i < 3; i++) {
            w.addParticle(new BlockStateParticleEffect(ParticleTypes.BLOCK, ground), p.getX() - side.x * 0.5,
                    p.getY() + 0.05, p.getZ() - side.z * 0.5, -side.x * 0.3, 0.1, -side.z * 0.3);
        }
    }
}
