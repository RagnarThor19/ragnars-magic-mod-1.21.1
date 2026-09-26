package net.ragnar.ragnarsmagicmod.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ragnar.ragnarsmagicmod.network.ShakePayload;

/** A camera shake the server can trigger for any spell (see ShakePayload); read by CameraMixin. */
public final class ScreenShake {
    private ScreenShake() {}

    private static float strength;
    private static int total, left;

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(ShakePayload.ID, (payload, context) -> kick(payload.strength(), payload.ticks()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> left = 0);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (left > 0 && !client.isPaused()) left--;
        });
    }

    /** Starts a shake from client code. A bigger shake takes over; a smaller one doesn't cut a big one short. */
    public static void kick(float newStrength, int ticks) {
        if (newStrength >= current(0f)) {
            strength = newStrength;
            total = left = Math.max(1, ticks);
        }
    }

    /** How hard to shake right now, fading out as it runs. */
    public static float current(float tickDelta) {
        if (left <= 0) return 0f;
        float f = (left - tickDelta) / total;
        return strength * Math.max(0f, f) * Math.max(0f, f);
    }
}
