package net.ragnar.ragnarsmagicmod.leaping.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.custom.PassiveTomes;
import net.ragnar.ragnarsmagicmod.leaping.Leaping;
import net.ragnar.ragnarsmagicmod.util.FairyForm;

/**
 * Client half of the Tome of Leaping (see Leaping). Winds the leap up while you crouch on the ground (standing or
 * crouch-walking), and lets it go when you jump while still crouched. Kept quiet on purpose, since everyone crouches
 * all the time:
 * <ul>
 *   <li>nothing at all for the first {@link Leaping#MIN_CHARGE_TICKS}, so a quick crouch shows nothing;</li>
 *   <li>then the horse jump bar fills in the XP bar's place (it's back the moment you stop), smoothly;</li>
 *   <li>one soft, quiet ding when it's full, and nothing else until you leap;</li>
 *   <li>crouching on its own never launches you: only jump does.</li>
 * </ul>
 */
public final class LeapingClient {
    private LeapingClient() {}

    private static final Identifier BAR_BACKGROUND = Identifier.ofVanilla("hud/jump_bar_background");
    private static final Identifier BAR_PROGRESS = Identifier.ofVanilla("hud/jump_bar_progress");
    /** Forward speed of a full leap with forward held. */
    private static final double MAX_FORWARD = 0.75;

    private static int charge;
    private static boolean charging, wasOnGround, pinged;
    private static double groundY;

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(LeapingClient::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        // In creative there's no XP bar to stand in for, so it draws in that same (empty) spot itself
        HudRenderCallback.EVENT.register((ctx, counter) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.interactionManager != null && !mc.interactionManager.hasExperienceBar() && !mc.options.hudHidden) {
                drawBar(ctx, ctx.getScaledWindowWidth() / 2 - 91);
            }
        });
    }

    private static void reset() {
        charge = 0;
        charging = false;
        pinged = false;
    }

    private static void tick(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p == null) {
            reset();
            return;
        }
        if (client.isPaused()) return;
        boolean able = able(p);

        // Let it go: we were on the ground last tick, wound up, and jump has just lifted us off it
        if (able && charge >= Leaping.MIN_CHARGE_TICKS && wasOnGround && !p.isOnGround() && p.getVelocity().y > 0
                && client.options.jumpKey.isPressed()) {
            leap(p);
        }

        // Wind up: crouched on the ground, standing or crouch-walking
        charging = able && p.isSneaking() && p.isOnGround() && client.currentScreen == null;
        if (charging) {
            if (charge < Leaping.FULL_CHARGE_TICKS) {
                charge++;
                if (charge == Leaping.FULL_CHARGE_TICKS && !pinged) {
                    pinged = true;
                    // One soft wooden ding, only the leaper hears it
                    p.playSound(SoundEvents.BLOCK_NOTE_BLOCK_XYLOPHONE.value(), 0.15f, 1.6f);
                }
            }
        } else {
            charge = 0;
            pinged = false;
        }

        wasOnGround = p.isOnGround();
        if (wasOnGround) groundY = p.getY();
    }

    /** Places and states where leaping doesn't make sense. */
    private static boolean able(ClientPlayerEntity p) {
        return !p.isSpectator() && !p.getAbilities().flying && !p.hasVehicle() && !p.isTouchingWater() && !p.isInLava()
                && !p.isClimbing() && !p.isFallFlying() && !FairyForm.isFairy(p) && PassiveTomes.has(p, Leaping.TOME_OF_LEAPING);
    }

    private static void leap(ClientPlayerEntity p) {
        float height = Leaping.heightFor(charge);
        float k = MathHelper.clamp(charge / (float) Leaping.FULL_CHARGE_TICKS, 0f, 1f);
        // The jump has already lifted us a little this tick; carry on from here to the full height
        double risen = Math.max(0.0, p.getY() - groundY);
        double vy = Leaping.velocityFor(height - risen);
        Vec3d v = p.getVelocity();
        if (p.input != null && p.input.movementForward > 0) {
            Vec3d ahead = Vec3d.fromPolar(0f, p.getYaw()).multiply(MAX_FORWARD * k);
            v = new Vec3d(ahead.x, 0, ahead.z);
        }
        p.setVelocity(v.x, Math.max(vy, v.y), v.z);
        p.fallDistance = 0f;
        ClientPlayNetworking.send(new Leaping.LeapPayload(height));

        // A soft spring and a puff of dust from the ground pushed off
        p.playSound(SoundEvents.ENTITY_GOAT_LONG_JUMP, 0.5f + 0.3f * k, 1.2f - 0.25f * k);
        World w = p.getWorld();
        BlockPos below = BlockPos.ofFloored(p.getX(), groundY - 0.2, p.getZ());
        BlockState ground = w.getBlockState(below);
        if (charge >= Leaping.FULL_CHARGE_TICKS) {
            // A full leap leaves a little ring of cloud behind
            for (int i = 0; i < 10; i++) {
                double a = i * Math.PI * 2 / 10;
                w.addParticle(ParticleTypes.CLOUD, p.getX() + Math.cos(a) * 0.4, groundY + 0.1, p.getZ() + Math.sin(a) * 0.4,
                        Math.cos(a) * 0.05, 0.02, Math.sin(a) * 0.05);
            }
        }
        int puffs = (int) (3 + 9 * k);
        for (int i = 0; i < puffs; i++) {
            double a = i * Math.PI * 2 / puffs;
            double cos = Math.cos(a), sin = Math.sin(a);
            w.addParticle(ParticleTypes.POOF, p.getX() + cos * 0.3, groundY + 0.05, p.getZ() + sin * 0.3, cos * 0.06 * k, 0.01, sin * 0.06 * k);
            if (!ground.isAir()) {
                w.addParticle(new BlockStateParticleEffect(ParticleTypes.BLOCK, ground), p.getX() + cos * 0.35, groundY + 0.05, p.getZ() + sin * 0.35, 0, 0, 0);
            }
        }
        reset();
    }

    // ---------------------------------------------------------------------
    // The bar
    // ---------------------------------------------------------------------

    /** True while the wind-up bar is showing (it then takes the XP bar's place). */
    public static boolean showing() {
        return charge >= Leaping.MIN_CHARGE_TICKS;
    }

    /** Draws the wind-up as the horse jump bar, at {@code x} where the XP bar goes. Does nothing when it's not showing. */
    public static void drawBar(DrawContext ctx, int x) {
        if (!showing()) return;
        float delta = MinecraftClient.getInstance().getRenderTickCounter().getTickDelta(false);
        float filled = charging && charge < Leaping.FULL_CHARGE_TICKS ? charge + delta : charge;
        float k = MathHelper.clamp(filled / Leaping.FULL_CHARGE_TICKS, 0f, 1f);
        int y = ctx.getScaledWindowHeight() - 32 + 3;
        ctx.drawGuiTexture(BAR_BACKGROUND, x, y, 182, 5);
        int w = (int) (k * 183.0f);
        if (w > 0) ctx.drawGuiTexture(BAR_PROGRESS, 182, 5, 0, 0, x, y, w, 5);
    }
}
