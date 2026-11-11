package net.ragnar.ragnarsmagicmod.tornado.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.MinecraftClient;
import net.ragnar.ragnarsmagicmod.tornado.Tornado;
import net.ragnar.ragnarsmagicmod.tornado.TornadoEntity;

/**
 * Client half of the Tome of Tornados (see Tornado): the renderer, and the roar that follows each tornado about (the
 * tornado does its own dust and debris).
 */
public final class TornadoClient {
    private TornadoClient() {}

    public static void init() {
        EntityRendererRegistry.register(Tornado.TORNADO, TornadoRenderer::new);
        ClientEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof TornadoEntity tornado) {
                MinecraftClient.getInstance().getSoundManager().play(new TornadoSound(tornado));
            }
        });
    }
}
