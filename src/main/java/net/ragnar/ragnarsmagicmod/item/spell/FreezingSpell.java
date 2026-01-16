// File: src/main/java/net/ragnar/ragnarsmagicmod/item/spell/FreezingSpell.java
package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.util.FreezingNova;

public class FreezingSpell implements Spell {
    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return true;

        if (world instanceof ServerWorld sw) {
            FreezingNova.create(sw, player.getPos().add(0, 1, 0), player.getUuid());
        }

        return true;
    }
}