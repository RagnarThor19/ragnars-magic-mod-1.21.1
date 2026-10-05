package net.ragnar.ragnarsmagicmod.upsidedown.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.MathHelper;
import net.ragnar.ragnarsmagicmod.upsidedown.UpsideDown;

/**
 * Client half of the Tome of Upside Down: how far the local player's view has rolled over, 0 (right way up) to 1
 * (upside down), turning smoothly over a few ticks when they flip. The camera, mouse and keys read it (see the
 * client mixins in {@code mixin/upsidedown}).
 */
public final class UpsideDownClient {
    private UpsideDownClient() {}

    private static final float TURN_PER_TICK = 0.125f; // half a second to roll over

    private static float roll, prevRoll;

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            prevRoll = roll;
            float target = client.player != null && UpsideDown.isFlipped(client.player) ? 1f : 0f;
            roll = MathHelper.stepTowards(roll, target, TURN_PER_TICK);
        });
    }

    /** The roll right now, eased, 0..1. */
    public static float roll() {
        float t = MathHelper.lerp(MinecraftClient.getInstance().getRenderTickCounter().getTickDelta(true), prevRoll, roll);
        return t * t * (3f - 2f * t);
    }

    /** True once the view is more upside down than not: from then on, left is right and up is down. */
    public static boolean controlsFlipped() {
        return roll > 0.5f;
    }
}
