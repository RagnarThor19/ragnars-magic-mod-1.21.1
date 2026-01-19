package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.util.CloudRunner;

public class CloudSpell implements Spell {
    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return true;

        // Sound effect on cast
        world.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BLOCK_DECORATED_POT_INSERT_FAIL, SoundCategory.PLAYERS, 1.6F, 0.6F); // Deep pitch

        // 20 seconds = 400 ticks
        if (world instanceof ServerWorld sw) {
            CloudRunner.start(sw, player, 400);
        }

        return true;
    }
}