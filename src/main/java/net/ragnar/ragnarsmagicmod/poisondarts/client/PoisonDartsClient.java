package net.ragnar.ragnarsmagicmod.poisondarts.client;

import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.ragnar.ragnarsmagicmod.poisondarts.PoisonDarts;

/** Client half of the Tome of Poison Darts (see PoisonDarts): just the dart's renderer. */
public final class PoisonDartsClient {
    private PoisonDartsClient() {}

    public static void init() {
        EntityRendererRegistry.register(PoisonDarts.DART, PoisonDartRenderer::new);
    }
}
