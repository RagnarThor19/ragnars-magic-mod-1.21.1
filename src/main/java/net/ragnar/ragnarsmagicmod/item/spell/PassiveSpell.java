package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.World;

/** The "spell" of a passive tome: there's nothing to cast, so it only says how the tome works. Never costs anything. */
public class PassiveSpell implements Spell {
    private final String howItWorks;

    public PassiveSpell(String howItWorks) {
        this.howItWorks = howItWorks;
    }

    @Override
    public int xpCost(PlayerEntity player, int tomeCost) {
        return 0;
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!world.isClient) player.sendMessage(Text.literal("Passive: " + howItWorks).formatted(Formatting.GOLD), true);
        return false;
    }
}
