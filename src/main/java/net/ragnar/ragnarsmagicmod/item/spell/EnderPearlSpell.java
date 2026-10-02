package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.world.World;

/**
 * Tome of Ender Pearls: throws a real ender pearl, exactly as the item does - same speed and spread, and the pearl
 * itself handles the landing (teleport, fall damage, the odd endermite).
 */
public class EnderPearlSpell implements Spell {
    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENTITY_ENDER_PEARL_THROW,
                SoundCategory.NEUTRAL, 0.5f, 0.4f / (world.getRandom().nextFloat() * 0.4f + 0.8f));
        if (world.isClient) return false;
        EnderPearlEntity pearl = new EnderPearlEntity(world, player);
        pearl.setItem(new ItemStack(Items.ENDER_PEARL));
        pearl.setVelocity(player, player.getPitch(), player.getYaw(), 0.0f, 1.5f, 1.0f);
        world.spawnEntity(pearl);
        return true;
    }
}
