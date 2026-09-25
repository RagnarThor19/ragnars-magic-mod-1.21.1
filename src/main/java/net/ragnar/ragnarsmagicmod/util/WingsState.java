package net.ragnar.ragnarsmagicmod.util;

import net.minecraft.entity.LivingEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntPredicate;

/**
 * Who is flying on Tome of Wings wings right now, on each side. While true, the player glides like
 * they're wearing an elytra even though they aren't (see LivingEntityMixin).
 */
public final class WingsState {
    private WingsState() {}

    /** Server side: caster -> ticks of flight left. */
    public static final Map<UUID, Integer> SERVER = new HashMap<>();

    /** Client side, filled in by WingsClient: is the entity with this id on wings? */
    public static IntPredicate clientGliding = id -> false;

    public static boolean hasWings(LivingEntity entity) {
        if (entity.getWorld().isClient) return clientGliding.test(entity.getId());
        return !SERVER.isEmpty() && SERVER.containsKey(entity.getUuid());
    }
}
