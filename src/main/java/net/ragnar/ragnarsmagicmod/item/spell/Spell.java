package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraft.entity.player.PlayerEntity;

public interface Spell {
    /**
     * Perform the spell effect. Return true if something happened (consume XP, etc.).
     */
    boolean cast(World world, PlayerEntity player, ItemStack staff);
}
