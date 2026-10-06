package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.entity.RockEntity;

/** Tome of Rocks: hurls a lump of stone where you look (see RockEntity). */
public class RocksSpell implements Spell {
    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw)) return false;
        RockEntity rock = new RockEntity(world, player);
        rock.setVelocity(player, player.getPitch(), player.getYaw(), 0.0F, 1.6F, 0.5F);
        sw.spawnEntity(rock);

        double x = player.getX(), y = player.getEyeY(), z = player.getZ();
        sw.playSound(null, x, y, z, SoundEvents.BLOCK_STONE_PLACE, SoundCategory.PLAYERS, 0.8f, 1.2f);
        sw.playSound(null, x, y, z, SoundEvents.ENTITY_WITCH_THROW, SoundCategory.PLAYERS, 1.0f, 0.6f);
        return true;
    }
}
