package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.util.SmashRunner;

public class SmashingSpell implements Spell {
    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return true;

        if (world instanceof ServerWorld sw) {
            SmashRunner.start(sw, player);
        }

        return true;
    }
}