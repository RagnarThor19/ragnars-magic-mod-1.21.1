package net.ragnar.ragnarsmagicmod.util;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityDimensions;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Who is currently a Tome of the Fairy fairy, on each side. Shared by the server spell, the client, and the mixin that
 * shrinks a fairy's hitbox - both sides must agree on the size or the player would rubber-band.
 */
public final class FairyForm {
    private FairyForm() {}

    /** A fairy is half a block across: small enough to slip through one-block gaps. */
    public static final EntityDimensions DIMENSIONS = EntityDimensions.fixed(0.5f, 0.5f).withEyeHeight(0.3f);

    /** Server side, by player UUID. */
    public static final Set<UUID> SERVER = new HashSet<>();
    /** Client side, by entity id. */
    public static final Set<Integer> CLIENT = new HashSet<>();

    public static boolean isFairy(Entity entity) {
        return entity.getWorld().isClient ? CLIENT.contains(entity.getId()) : SERVER.contains(entity.getUuid());
    }
}
