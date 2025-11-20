package net.ragnar.ragnarsmagicmod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.ragnar.ragnarsmagicmod.entity.ModEntities;
import net.ragnar.ragnarsmagicmod.entity.client.SteveRenderer;


public class RagnarsMagicModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        net.ragnar.ragnarsmagicmod.entity.client.ModEntityRenderers.register();
        EntityRendererRegistry.register(ModEntities.STEVE, SteveRenderer::new);
    }
}
