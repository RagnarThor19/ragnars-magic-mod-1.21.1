package net.ragnar.ragnarsmagicmod.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ragnar.ragnarsmagicmod.network.SlipperyPayload;
import net.ragnar.ragnarsmagicmod.util.Slippery;

/** Tome of Slipperiness, client side: counts down how long your own feet stay slippery (the physics are in LivingEntityMixin). */
public final class SlipperyClient {
    private SlipperyClient() {}

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(SlipperyPayload.ID, (payload, context) -> Slippery.clientTicks = payload.ticks());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> Slippery.clientTicks = 0);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (Slippery.clientTicks > 0) Slippery.clientTicks--;
        });
    }
}
