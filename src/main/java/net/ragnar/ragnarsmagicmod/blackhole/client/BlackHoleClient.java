package net.ragnar.ragnarsmagicmod.blackhole.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundEvents;
import net.ragnar.ragnarsmagicmod.blackhole.BlackHoleEntity;
import net.ragnar.ragnarsmagicmod.blackhole.BlackHoles;

/**
 * Client half of the Tome of Black Holes (see BlackHoles): the renderer, and the drone that follows each hole about,
 * a warbling portal hum over a deep beacon throb. Screen shakes come through ShakePayload.
 */
public final class BlackHoleClient {
    private BlackHoleClient() {}

    public static void init() {
        EntityRendererRegistry.register(BlackHoles.BLACK_HOLE, BlackHoleRenderer::new);
        ClientEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof BlackHoleEntity hole && hole.stage() != BlackHoleEntity.BURST) {
                var sounds = MinecraftClient.getInstance().getSoundManager();
                sounds.play(new BlackHoleSound(hole, SoundEvents.BLOCK_PORTAL_AMBIENT, 1.4f, 0.5f));
                sounds.play(new BlackHoleSound(hole, SoundEvents.BLOCK_BEACON_AMBIENT, 2.0f, 0.5f));
            }
        });
    }
}
