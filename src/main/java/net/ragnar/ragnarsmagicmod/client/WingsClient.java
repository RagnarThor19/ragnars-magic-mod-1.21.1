package net.ragnar.ragnarsmagicmod.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.ragnar.ragnarsmagicmod.network.WingsPayload;
import net.ragnar.ragnarsmagicmod.util.WingsState;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Client half of the Tome of Wings: the firework push (applied here, like a real rocket does, so flight
 * stays smooth), the light trailing off the wingtips, and the wings on every flier (WingsFeatureRenderer).
 */
public final class WingsClient {
    private WingsClient() {}

    public static final int UNFOLD_TICKS = 4;
    public static final int FOLD_TICKS = 5;

    public static final class Wings {
        int ticksLeft;
        int age;
        int folding = -1; // ticks since the wings started folding away, -1 while flying
    }

    private static final Map<Integer, Wings> WINGS = new HashMap<>();

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(WingsPayload.ID, (payload, context) -> {
            if (payload.ticks() > 0) {
                Wings w = WINGS.computeIfAbsent(payload.playerId(), id -> new Wings());
                if (w.folding >= 0) { w.age = 0; w.folding = -1; }
                w.ticksLeft = payload.ticks();
            } else {
                Wings w = WINGS.get(payload.playerId());
                if (w != null && w.folding < 0) { w.ticksLeft = 0; w.folding = 0; }
            }
        });
        WingsState.clientGliding = id -> {
            Wings w = WINGS.get(id);
            return w != null && w.folding < 0 && w.ticksLeft > 0;
        };
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> WINGS.clear());
        ClientTickEvents.END_CLIENT_TICK.register(WingsClient::tick);
        LivingEntityFeatureRendererRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
            if (renderer instanceof PlayerEntityRenderer playerRenderer) helper.register(new WingsFeatureRenderer(playerRenderer));
        });
    }

    /** The wings on this entity, if any (including while they fold away). */
    public static Wings get(Entity entity) {
        return WINGS.isEmpty() ? null : WINGS.get(entity.getId());
    }

    /** 0..1 how far the wings are spread, with a little overshoot as they snap open. */
    public static float openness(Wings w, float tickDelta) {
        if (w.folding >= 0) return MathHelper.clamp(1f - (w.folding + tickDelta) / FOLD_TICKS, 0f, 1f);
        float t = MathHelper.clamp((w.age + tickDelta) / UNFOLD_TICKS, 0f, 1f);
        float u = t - 1f;
        return 1f + u * u * (2.7f * u + 1.7f); // ease out with overshoot
    }

    public static float age(Wings w, float tickDelta) {
        return w.age + tickDelta;
    }

    private static void tick(MinecraftClient client) {
        if (client.world == null) { WINGS.clear(); return; }
        if (client.isPaused()) return;

        Iterator<Map.Entry<Integer, Wings>> it = WINGS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Wings> e = it.next();
            Wings w = e.getValue();
            w.age++;
            if (w.folding >= 0) {
                if (++w.folding > FOLD_TICKS) { it.remove(); continue; }
            } else if (--w.ticksLeft <= -20) {
                it.remove(); // the server never told us they ended
                continue;
            }

            Entity entity = client.world.getEntityById(e.getKey());
            if (!(entity instanceof PlayerEntity player)) continue;

            // The firework push, exactly as a rocket gives it to an elytra flier
            if (player == client.player && w.folding < 0 && w.ticksLeft > 0 && player.isFallFlying()) {
                Vec3d look = player.getRotationVector();
                Vec3d v = player.getVelocity();
                player.setVelocity(v.add(
                        look.x * 0.1 + (look.x * 1.5 - v.x) * 0.5,
                        look.y * 0.1 + (look.y * 1.5 - v.y) * 0.5,
                        look.z * 0.1 + (look.z * 1.5 - v.z) * 0.5));
            }

            if (w.folding < 0) trail(client, player);
        }
    }

    /** Streams of light off both wingtips, and sparks where a rocket would be. */
    private static void trail(MinecraftClient client, PlayerEntity player) {
        Random rand = player.getRandom();
        float yaw = player.getBodyYaw() * MathHelper.RADIANS_PER_DEGREE;
        Vec3d right = new Vec3d(-MathHelper.cos(yaw), 0, -MathHelper.sin(yaw));
        Vec3d back = player.getRotationVector().negate();
        double height = player.isFallFlying() ? 0.35 : 1.3;
        Vec3d center = player.getPos().add(0, height, 0);

        for (int side = -1; side <= 1; side += 2) {
            Vec3d tip = center.add(right.multiply(side * 1.35)).add(back.multiply(0.3));
            client.world.addParticle(ParticleTypes.END_ROD, tip.x, tip.y, tip.z,
                    back.x * 0.05 + (rand.nextDouble() - 0.5) * 0.02, (rand.nextDouble() - 0.5) * 0.02, back.z * 0.05);
        }
        Vec3d feet = center.add(back.multiply(0.8));
        client.world.addParticle(ParticleTypes.FIREWORK, feet.x, feet.y, feet.z,
                back.x * 0.1 + rand.nextGaussian() * 0.02, back.y * 0.1 + rand.nextGaussian() * 0.02, back.z * 0.1 + rand.nextGaussian() * 0.02);
        if (rand.nextInt(3) == 0) {
            client.world.addParticle(ParticleTypes.CLOUD, feet.x, feet.y, feet.z, back.x * 0.05, 0, back.z * 0.05);
        }
    }
}
