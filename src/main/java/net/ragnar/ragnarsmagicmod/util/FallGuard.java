package net.ragnar.ragnarsmagicmod.util;

import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;

/**
 * Takes the fall out of whatever a player does between {@link #hold} and {@link #release} (server side), for the
 * passive movement tomes. It raises their safe fall distance while held rather than zeroing their fall each tick, so it
 * holds even when a lagging connection delivers a burst of movement all in one server tick. Each tome uses its own
 * {@code key}, so they stack with each other and with Leaping's own protection without stepping on it.
 */
public final class FallGuard {
    private FallGuard() {}

    private static final double SAFE = 1024.0;

    /** From now on, nothing {@code player} falls hurts, until {@link #release}. */
    public static void hold(PlayerEntity player, Identifier key) {
        player.fallDistance = 0f;
        EntityAttributeInstance safe = player.getAttributeInstance(EntityAttributes.GENERIC_SAFE_FALL_DISTANCE);
        if (safe != null && !safe.hasModifier(key)) {
            safe.addTemporaryModifier(new EntityAttributeModifier(key, SAFE, EntityAttributeModifier.Operation.ADD_VALUE));
        }
    }

    /** Ends the protection. What they fell while it held is forgiven; a fall from here on counts as usual. */
    public static void release(PlayerEntity player, Identifier key) {
        EntityAttributeInstance safe = player.getAttributeInstance(EntityAttributes.GENERIC_SAFE_FALL_DISTANCE);
        if (safe != null && safe.hasModifier(key)) {
            safe.removeModifier(key);
            player.fallDistance = 0f;
        }
    }

    /** Whether {@code key}'s protection is on {@code player}. */
    public static boolean held(PlayerEntity player, Identifier key) {
        EntityAttributeInstance safe = player.getAttributeInstance(EntityAttributes.GENERIC_SAFE_FALL_DISTANCE);
        return safe != null && safe.hasModifier(key);
    }
}
