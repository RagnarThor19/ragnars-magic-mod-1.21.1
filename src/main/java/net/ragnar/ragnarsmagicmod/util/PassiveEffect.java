package net.ragnar.ragnarsmagicmod.util;

import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.entry.RegistryEntry;

/**
 * A status effect a passive tome hands out while it's carried, the way Night Vision does: ambient and particle-free
 * (just the icon), topped up well before it runs low so it never flickers, and taken away again once the tome goes.
 * A potion or beacon giving as much or more is never touched.
 */
public final class PassiveEffect {
    private PassiveEffect() {}

    /** Each top-up lasts this long; it's renewed once below {@link #RENEW_BELOW}. Check at least every 10 ticks. */
    public static final int TOP_UP = 400, RENEW_BELOW = 300;

    /** Gives {@code player} the effect (level I) if {@code carried}, or takes the tome's away if not. */
    public static void keep(PlayerEntity player, RegistryEntry<StatusEffect> effect, boolean carried) {
        keep(player, effect, 0, carried);
    }

    /**
     * Gives {@code player} the effect at {@code amplifier} (0 is level I) if {@code carried}, or takes the tome's away if
     * not. A weaker one from a potion or beacon is lifted to the tome's; a stronger or equal one is left alone.
     */
    public static void keep(PlayerEntity player, RegistryEntry<StatusEffect> effect, int amplifier, boolean carried) {
        if (!player.isAlive() || player.isSpectator()) return;
        StatusEffectInstance current = player.getStatusEffect(effect);
        if (carried) {
            if (current == null || ours(current, amplifier) && current.getDuration() < RENEW_BELOW || current.getAmplifier() < amplifier) {
                player.addStatusEffect(new StatusEffectInstance(effect, TOP_UP, amplifier, true, false, true));
            }
        } else if (current != null && ours(current, amplifier)) {
            player.removeStatusEffect(effect);
        }
    }

    /** True for an effect a tome handed out (potions' are never ambient, and beacons' show particles). */
    private static boolean ours(StatusEffectInstance effect, int amplifier) {
        return effect.getAmplifier() == amplifier && effect.isAmbient() && !effect.shouldShowParticles() && effect.getDuration() <= TOP_UP;
    }
}
