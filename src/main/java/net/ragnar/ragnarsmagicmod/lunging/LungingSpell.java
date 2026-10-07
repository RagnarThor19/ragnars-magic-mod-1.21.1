package net.ragnar.ragnarsmagicmod.lunging;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;

/** Tome of Lunging: passive (see Lunging). Casting it from the staff just says how it works. */
public class LungingSpell implements Spell {
    @Override
    public int xpCost(PlayerEntity player, int tomeCost) {
        return 0;
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!world.isClient) {
            player.sendMessage(Text.literal("Passive: right-click with a sword to lunge and stab (sprint + right-click if your off hand holds a shield).")
                    .formatted(Formatting.AQUA), true);
        }
        return false;
    }
}
