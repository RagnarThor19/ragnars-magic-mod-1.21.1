package net.ragnar.ragnarsmagicmod.ricochet;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;

/**
 * Tome of Ricochet. The arrow appears over your head straight away and keeps aiming where you look while it winds
 * up; after {@link RicochetArrowEntity#CHARGE_TICKS} it lets fly. XP and the cooldown go on the cast.
 */
public class RicochetSpell implements Spell {
    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw)) return false;
        RicochetArrowEntity arrow = new RicochetArrowEntity(sw, player);
        sw.spawnEntity(arrow);
        arrow.chargeStart(sw);
        return true;
    }
}
