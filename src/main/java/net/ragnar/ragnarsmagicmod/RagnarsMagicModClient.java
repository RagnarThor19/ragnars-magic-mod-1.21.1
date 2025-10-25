package net.ragnar.ragnarsmagicmod;

import net.fabricmc.api.ClientModInitializer;

public class RagnarsMagicModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        net.ragnar.ragnarsmagicmod.entity.client.ModEntityRenderers.register();
    }
}
