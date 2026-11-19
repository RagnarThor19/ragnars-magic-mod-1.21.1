package net.ragnar.ragnarsmagicmod.mimicry.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.entity.Entity;
import net.ragnar.ragnarsmagicmod.mimicry.Mimicry;

import java.util.HashSet;
import java.util.Set;

/**
 * Client half of the Tome of Mimicry (see Mimicry): remembers who is disguised so their body isn't drawn. The block
 * they've become is a display entity from the server.
 */
public final class MimicryClient {
    private MimicryClient() {}

    private static final Set<Integer> DISGUISED = new HashSet<>();

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(Mimicry.DisguisePayload.ID, (payload, context) -> {
            if (payload.disguised()) DISGUISED.add(payload.entityId());
            else DISGUISED.remove(payload.entityId());
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> DISGUISED.clear());
    }

    /** Hidden from everyone, yourself included: in third person you see only the block you've become. */
    public static boolean isHidden(Entity entity) {
        return DISGUISED.contains(entity.getId());
    }
}
