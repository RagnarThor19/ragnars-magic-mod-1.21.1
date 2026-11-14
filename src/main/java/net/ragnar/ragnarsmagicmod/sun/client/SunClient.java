package net.ragnar.ragnarsmagicmod.sun.client;

import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.ragnar.ragnarsmagicmod.sun.Sun;

/** Client side of the Tome of the Sun: just its renderer (its particles are made by the sun entity itself). */
public final class SunClient {
    private SunClient() {}

    public static void init() {
        EntityRendererRegistry.register(Sun.SUN, SunRenderer::new);
    }
}
