package net.ragnar.ragnarsmagicmod.blocktome.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.ragnar.ragnarsmagicmod.blocktome.BlockTome;
import net.ragnar.ragnarsmagicmod.blocktome.BlocksSpell;

/**
 * Client half of the Tome of Blocks (see BlockTome): only keeps count of your orbiting blocks, so the staff knows a
 * press is a free shot rather than a fresh summon. Everything you see is display entities and particles from the server.
 */
public final class BlockTomeClient {
    private BlockTomeClient() {}

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(BlockTome.CountPayload.ID, (payload, context) -> BlocksSpell.clientCount = payload.count());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> BlocksSpell.clientCount = 0);
    }
}
