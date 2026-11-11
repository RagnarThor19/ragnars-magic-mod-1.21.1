package net.ragnar.ragnarsmagicmod.geysir.client;

import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.ragnar.ragnarsmagicmod.geysir.Geysir;

/** Client half of the Tome of Geysir (see Geysir): just the renderer; each geysir throws its own spray. */
public final class GeysirClient {
    private GeysirClient() {}

    public static void init() {
        EntityRendererRegistry.register(Geysir.GEYSIR, GeysirRenderer::new);
    }
}
