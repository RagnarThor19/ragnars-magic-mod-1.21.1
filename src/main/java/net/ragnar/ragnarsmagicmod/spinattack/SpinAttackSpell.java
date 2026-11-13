package net.ragnar.ragnarsmagicmod.spinattack;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;

/** Tome of Spin Attack (see SpinAttack): three spins with the sword in your other hand. No sword, no spin (and nothing spent). */
public class SpinAttackSpell implements Spell {
    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient || !(player instanceof ServerPlayerEntity sp)) return false;
        Hand hand = SpinAttack.swordHand(player);
        if (hand == null) {
            player.sendMessage(Text.literal("You need a sword in your other hand to spin."), true);
            return false;
        }
        if (SpinAttack.isSpinning(sp)) return false;
        SpinAttack.start(sp, hand);
        return true;
    }
}
