package net.ragnar.ragnarsmagicmod.util;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.entity.Entity;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Spell visuals made of real entities (block/item displays). If the server stops while one is
 * alive it gets saved with the chunk; this removes any such leftover the next time it loads.
 */
public final class TempEntities {
    private static final String TAG = "ragnarsmagicmod_temp";
    private static final Set<UUID> LIVE = new HashSet<>();

    private TempEntities() {}

    public static void register() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity.getCommandTags().contains(TAG) && !LIVE.contains(entity.getUuid())) entity.discard();
        });
    }

    /** Marks an entity as temporary. Call before spawning it. */
    public static void track(Entity entity) {
        entity.addCommandTag(TAG);
        LIVE.add(entity.getUuid());
    }

    public static void discard(Entity entity) {
        LIVE.remove(entity.getUuid());
        entity.discard();
    }
}
