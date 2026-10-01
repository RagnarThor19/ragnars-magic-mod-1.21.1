package net.ragnar.ragnarsmagicmod.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.ragnar.ragnarsmagicmod.network.InvisibilityPayload;
import net.ragnar.ragnarsmagicmod.util.AbsoluteInvisibility;

/** Tome of Invisibility on the client: remembers who is completely invisible so their whole body is skipped. */
public final class InvisibilityClient {
    private InvisibilityClient() {}

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(InvisibilityPayload.ID, (payload, context) -> {
            if (payload.hidden()) AbsoluteInvisibility.CLIENT.add(payload.entityId());
            else AbsoluteInvisibility.CLIENT.remove(payload.entityId());
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> AbsoluteInvisibility.CLIENT.clear());
    }

    /** Hidden from everyone else. You still see yourself in third person the vanilla way, so you know where you are. */
    public static boolean isHidden(Entity entity) {
        return entity != MinecraftClient.getInstance().player && AbsoluteInvisibility.CLIENT.contains(entity.getId());
    }
}
