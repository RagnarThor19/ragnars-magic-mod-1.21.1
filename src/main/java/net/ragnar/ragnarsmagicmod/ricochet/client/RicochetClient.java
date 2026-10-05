package net.ragnar.ragnarsmagicmod.ricochet.client;

import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.ragnar.ragnarsmagicmod.ricochet.Ricochet;

/** Client half of the Tome of Ricochet (see Ricochet for how to remove it). */
public final class RicochetClient {
    private RicochetClient() {}

    public static void init() {
        EntityRendererRegistry.register(Ricochet.ARROW, RicochetArrowRenderer::new);
    }
}
