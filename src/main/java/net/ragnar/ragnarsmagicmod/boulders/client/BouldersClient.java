package net.ragnar.ragnarsmagicmod.boulders.client;

import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.ragnar.ragnarsmagicmod.boulders.Boulders;

/** Client half of the Tome of Boulders (see Boulders). The slam's screen shake comes through ShakePayload. */
public final class BouldersClient {
    private BouldersClient() {}

    public static void init() {
        EntityRendererRegistry.register(Boulders.BOULDER, BoulderRenderer::new);
    }
}
