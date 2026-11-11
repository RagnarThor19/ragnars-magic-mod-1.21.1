package net.ragnar.ragnarsmagicmod.punching;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;

/** Tome of Punching (see Punching): the dash and the punch. */
public class PunchingSpell implements Spell {
    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient || !(player instanceof ServerPlayerEntity sp) || Punching.dashing(sp)) return false;
        Punching.start(sp);
        return true;
    }
}
