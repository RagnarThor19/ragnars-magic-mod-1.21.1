package net.ragnar.ragnarsmagicmod.client;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.network.TestEntityPayload;

import java.util.UUID;

/**
 * Tome of Paranoia, client side: Test::Entity. A faceless Steve with a player's name tag that only exists on the haunted
 * player's screen - it's added straight into their client world, so the server and everyone else never see it. The
 * server only says where it shows up and how it behaves (see TestEntityPayload); what it does from then on depends on
 * where you look.
 */
public final class TestEntityClient {
    private TestEntityClient() {}

    private static final Identifier SKIN = Identifier.of(RagnarsMagicMod.MOD_ID, "textures/entity/test_entity.png");
    private static final SkinTextures SKIN_TEXTURES = new SkinTextures(SKIN, null, null, null, SkinTextures.Model.WIDE, false);
    private static final GameProfile PROFILE = new GameProfile(UUID.fromString("7e57e717-0000-4000-8000-00000000dead"), "Test::Entity");
    /** Server entity ids are always positive, so this can never clash with a real one. */
    private static final int ENTITY_ID = -7357;

    private static final double LOOKING_AT = 0.985;  // looking right at it
    private static final double IN_VIEW = 0.35;      // roughly where it would come onto the screen when turning around
    private static final int SCARE_TICKS = 14;
    private static final int FLASH_TICKS = 25;

    private static final class TestEntity extends OtherClientPlayerEntity {
        TestEntity(ClientWorld world) {
            super(world, PROFILE);
        }

        @Override
        public SkinTextures getSkinTextures() {
            return SKIN_TEXTURES;
        }

        @Override
        public boolean isPushable() {
            return false;
        }

        /** Hit it and it's simply not there any more. */
        @Override
        public boolean handleAttack(Entity attacker) {
            vanish();
            return true;
        }
    }

    private static TestEntity entity;
    private static int mode, age, ticksLeft;
    private static int walking = -1;     // STARE: ticks spent walking away after you looked at it
    private static int scaring = -1;     // JUMPSCARE: ticks spent in your face
    private static int lastStep = 0;     // APPROACH: when it last crept closer
    private static int glitch = 0;       // ticks left of being knocked out of place
    private static Vec3d glitchOffset = Vec3d.ZERO;
    private static Vec3d home = Vec3d.ZERO;
    private static int flash = 0;

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(TestEntityPayload.ID, (payload, context) -> spawn(context.client(), payload));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { entity = null; flash = 0; });
        ClientTickEvents.END_CLIENT_TICK.register(TestEntityClient::tick);
        HudRenderCallback.EVENT.register(TestEntityClient::renderFlash);
    }

    private static void spawn(MinecraftClient client, TestEntityPayload payload) {
        if (client.world == null || client.player == null) return;
        vanish();
        entity = new TestEntity(client.world);
        entity.setId(ENTITY_ID);
        mode = payload.mode();
        age = 0;
        ticksLeft = payload.ticks();
        walking = scaring = -1;
        lastStep = 0;
        glitch = 0;
        home = new Vec3d(payload.x(), payload.y(), payload.z());
        place(home, client.player);
        client.world.addEntity(entity);
    }

    private static void vanish() {
        if (entity == null) return;
        if (entity.getWorld() instanceof ClientWorld world) world.removeEntity(entity.getId(), Entity.RemovalReason.DISCARDED);
        entity = null;
    }

    private static void tick(MinecraftClient client) {
        if (flash > 0 && !client.isPaused()) flash--;
        if (entity == null) return;
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null || entity.getWorld() != client.world || !player.isAlive() || entity.isRemoved()) {
            entity = null;
            return;
        }
        if (client.isPaused()) return;
        age++;
        if (--ticksLeft <= 0) { vanish(); return; }

        double look = lookDot(player);
        switch (mode) {
            case TestEntityPayload.GLIMPSE -> stare(player);
            case TestEntityPayload.STARE -> {
                if (walking >= 0) { walkAway(client, player); return; }
                stare(player);
                if (age > 10 && look > LOOKING_AT) {
                    // Caught it: it either blinks out, or turns and calmly walks off
                    if (player.getRandom().nextInt(5) < 2) walking = 0;
                    else vanish();
                    return;
                }
            }
            case TestEntityPayload.BEHIND -> {
                stare(player);
                if (look > IN_VIEW) { vanish(); return; }
            }
            case TestEntityPayload.JUMPSCARE -> {
                if (scaring >= 0) { scare(client, player); return; }
                stare(player);
                if (look > IN_VIEW) { scaring = 0; scare(client, player); return; }
            }
            case TestEntityPayload.APPROACH -> {
                stare(player);
                double dist = entity.getPos().distanceTo(player.getPos());
                if (dist < 4.0 && look > IN_VIEW) {
                    // Too close. Either it's gone... or it isn't
                    if (player.getRandom().nextInt(3) == 0) { mode = TestEntityPayload.JUMPSCARE; scaring = 0; scare(client, player); }
                    else vanish();
                    return;
                }
                // It only ever moves while you aren't looking
                if (look < 0.5 && age - lastStep >= 25) {
                    lastStep = age;
                    creepCloser(client, player, dist);
                }
            }
            default -> vanish();
        }
        if (entity != null) glitchy(player);
    }

    // Behaviours

    /** Stands still and keeps its blank face turned to you. */
    private static void stare(ClientPlayerEntity player) {
        Vec3d to = player.getEyePos().subtract(entity.getEyePos());
        float yaw = yawTowards(to);
        float pitch = (float) -Math.toDegrees(Math.atan2(to.y, to.horizontalLength()));
        entity.setHeadYaw(yaw);
        entity.setBodyYaw(yaw);
        entity.setYaw(yaw);
        entity.setPitch(MathHelper.clamp(pitch, -40f, 40f));
    }

    /** Every so often it jerks out of place for a tick, like it's not rendering right. */
    private static void glitchy(ClientPlayerEntity player) {
        if (mode == TestEntityPayload.JUMPSCARE && scaring >= 0) return;
        Random r = player.getRandom();
        if (glitch > 0) {
            if (--glitch == 0) snap(entity.getPos().subtract(glitchOffset));
            return;
        }
        if (walking < 0 && r.nextInt(30) == 0) {
            glitchOffset = new Vec3d((r.nextDouble() - 0.5) * 0.6, 0, (r.nextDouble() - 0.5) * 0.6);
            snap(entity.getPos().add(glitchOffset));
            glitch = 1 + r.nextInt(2);
        }
    }

    private static void walkAway(MinecraftClient client, ClientPlayerEntity player) {
        walking++;
        Vec3d away = entity.getPos().subtract(player.getPos()).multiply(1, 0, 1).normalize();
        Vec3d next = entity.getPos().add(away.multiply(0.18));
        Double ground = groundY(client.world, next.x, entity.getY(), next.z);
        if (walking > 40 || ground == null) { vanish(); return; }
        float yaw = yawTowards(away);
        entity.setHeadYaw(yaw);
        entity.setPitch(0);
        entity.updateTrackedPositionAndAngles(next.x, ground, next.z, yaw, 0, 2);
    }

    /** Pops up a few blocks nearer along the ground, still facing you. */
    private static void creepCloser(MinecraftClient client, ClientPlayerEntity player, double dist) {
        Vec3d toward = player.getPos().subtract(entity.getPos()).multiply(1, 0, 1).normalize();
        double step = Math.min(4.0, dist - 2.5);
        for (double s = step; s >= 1.0; s -= 1.0) {
            Vec3d next = entity.getPos().add(toward.multiply(s));
            Double ground = groundY(client.world, next.x, entity.getY(), next.z);
            if (ground != null) {
                snap(new Vec3d(next.x, ground, next.z));
                stare(player);
                return;
            }
        }
    }

    /** Right in your face, screaming, and then nothing. */
    private static void scare(MinecraftClient client, ClientPlayerEntity player) {
        Vec3d look = player.getRotationVec(1.0f);
        Vec3d face = player.getEyePos().add(look.multiply(0.9));
        snap(face.subtract(0, entity.getStandingEyeHeight(), 0));
        stare(player);
        if (scaring == 0) {
            entity.swingHand(Hand.MAIN_HAND);
            client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_ENDERMAN_SCREAM, 0.5f, 1.0f));
            client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_GHAST_SCREAM, 0.6f, 0.8f));
            client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_WARDEN_ROAR, 1.4f, 0.5f));
            ScreenShake.kick(1.2f, SCARE_TICKS + 6);
        }
        if (++scaring >= SCARE_TICKS) {
            flash = FLASH_TICKS;
            vanish();
        }
    }

    /** After the scare, the screen goes black for a moment and fades back. */
    private static void renderFlash(DrawContext context, RenderTickCounter counter) {
        if (flash <= 0) return;
        float f = Math.min(1f, (flash - counter.getTickDelta(false)) / (FLASH_TICKS * 0.6f));
        int alpha = (int) (MathHelper.clamp(f, 0f, 1f) * 255);
        context.fill(0, 0, context.getScaledWindowWidth(), context.getScaledWindowHeight(), alpha << 24);
    }

    // Helpers

    private static void place(Vec3d pos, ClientPlayerEntity player) {
        snap(pos);
        stare(player);
        entity.prevYaw = entity.getYaw();
        entity.prevHeadYaw = entity.getHeadYaw();
        entity.prevBodyYaw = entity.getBodyYaw();
        entity.prevPitch = entity.getPitch();
    }

    /** Moves it instantly, with no sliding in between. */
    private static void snap(Vec3d pos) {
        entity.refreshPositionAndAngles(pos.x, pos.y, pos.z, entity.getYaw(), entity.getPitch());
        entity.updateTrackedPosition(pos.x, pos.y, pos.z);
        entity.resetPosition();
        entity.updateTrackedPositionAndAngles(pos.x, pos.y, pos.z, entity.getYaw(), entity.getPitch(), 0);
    }

    /** How directly the player is looking at its face, 1 = dead on. */
    private static double lookDot(ClientPlayerEntity player) {
        Vec3d to = entity.getEyePos().subtract(player.getEyePos());
        double len = to.length();
        if (len < 1.0e-3) return 1.0;
        return to.multiply(1.0 / len).dotProduct(player.getRotationVec(1.0f));
    }

    private static float yawTowards(Vec3d dir) {
        return (float) (MathHelper.atan2(dir.z, dir.x) * (180.0 / Math.PI)) - 90.0f;
    }

    /** Feet height of somewhere it could stand near {@code y}, or null. */
    private static Double groundY(ClientWorld world, double x, double y, double z) {
        for (int dy = 2; dy >= -3; dy--) {
            BlockPos feet = BlockPos.ofFloored(x, y + dy, z);
            if (world.getBlockState(feet.down()).getCollisionShape(world, feet.down()).isEmpty()) continue;
            if (!world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()) continue;
            if (!world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty()) continue;
            return (double) feet.getY();
        }
        return null;
    }
}
