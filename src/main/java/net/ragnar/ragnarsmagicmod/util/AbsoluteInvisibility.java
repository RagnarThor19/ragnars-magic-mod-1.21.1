package net.ragnar.ragnarsmagicmod.util;

import net.minecraft.entity.Entity;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Who is completely invisible from the Tome of Invisibility, on each side. Shared by the spell, the client and the
 * mixins that hide the rest of the body (armor, held items, sprint dust) and keep mobs from spotting armor.
 */
public final class AbsoluteInvisibility {
    private AbsoluteInvisibility() {}

    /** Server side, by player UUID. */
    public static final Set<UUID> SERVER = new HashSet<>();
    /** Client side, by entity id. */
    public static final Set<Integer> CLIENT = new HashSet<>();

    public static boolean isHidden(Entity entity) {
        return entity.getWorld().isClient ? CLIENT.contains(entity.getId()) : SERVER.contains(entity.getUuid());
    }
}
