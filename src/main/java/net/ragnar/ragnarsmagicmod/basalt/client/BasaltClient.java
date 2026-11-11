package net.ragnar.ragnarsmagicmod.basalt.client;

import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.ragnar.ragnarsmagicmod.basalt.Basalt;

/** Client half of the Tome of Basalt (see Basalt). The slam's screen shake comes through ShakePayload. */
public final class BasaltClient {
    private BasaltClient() {}

    public static void init() {
        EntityRendererRegistry.register(Basalt.BASALT_BOULDER, BasaltRenderer::new);
    }
}
