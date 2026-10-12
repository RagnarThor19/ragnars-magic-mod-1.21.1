package net.ragnar.ragnarsmagicmod.crows;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;

/** Tome of Crows (see Crows): melt into a flock of crows and come together again where you were looking. */
public class CrowsSpell implements Spell {
    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw) || !(player instanceof ServerPlayerEntity sp)) return false;
        return Crows.cast(sw, sp);
    }
}
