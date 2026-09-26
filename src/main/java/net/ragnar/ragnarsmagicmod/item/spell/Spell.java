package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraft.entity.player.PlayerEntity;

public interface Spell {
    /**
     * Perform the spell effect. Return true if something happened (consume XP, etc.).
     */
    boolean cast(World world, PlayerEntity player, ItemStack staff);

    /** XP this cast will cost, before enchantments. Called before {@link #cast}. */
    default int xpCost(PlayerEntity player, int tomeCost) {
        return tomeCost;
    }

    /** True if this cast may go off even while the tome is cooling down. Called before {@link #xpCost}. */
    default boolean ignoresCooldown(PlayerEntity player) {
        return false;
    }

    /** Cooldown to apply after a successful cast, before enchantments. Called after {@link #cast}. */
    default int cooldownAfterCast(PlayerEntity player, int tomeCooldown) {
        return tomeCooldown;
    }
}
