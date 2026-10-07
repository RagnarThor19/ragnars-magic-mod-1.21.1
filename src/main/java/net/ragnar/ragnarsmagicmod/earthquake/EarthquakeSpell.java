package net.ragnar.ragnarsmagicmod.earthquake;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;

/** Tome of Earthquake: digs in and starts the charge, from wherever you stand (surface or cave). */
public class EarthquakeSpell implements Spell {
    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient || !(player instanceof ServerPlayerEntity sp)) return false;
        if (EarthquakeCast.isCasting(sp)) return false;
        EarthquakeCast.begin(sp);
        return true;
    }
}
