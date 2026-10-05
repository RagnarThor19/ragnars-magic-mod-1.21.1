package net.ragnar.ragnarsmagicmod.jumping;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;

/** Tome of Jumping is passive (see Jumping), so casting it just says how it works. Never costs anything. */
public class JumpingSpell implements Spell {
    @Override
    public int xpCost(PlayerEntity player, int tomeCost) {
        return 0;
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!world.isClient) {
            player.sendMessage(Text.literal("Passive: jump again in mid-air, up to twice, within a second of each jump.")
                    .formatted(Formatting.AQUA), true);
        }
        return false;
    }
}
