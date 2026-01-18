package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.entity.BoulderProjectileEntity;

public class BoulderSpell implements Spell {
    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!world.isClient) {
            BoulderProjectileEntity boulder = new BoulderProjectileEntity(world, player);

            // Set velocity: slower speed (1.0f) but high divergence (1.0F) isn't needed here, so 0 div.
            // Parameters: player, pitch, yaw, roll, speed, divergence
            boulder.setVelocity(player, player.getPitch(), player.getYaw(), 0.0F, 1.0F, 1.0F);

            world.spawnEntity(boulder);
        }

        // Throw sound
        world.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ENTITY_EGG_THROW, SoundCategory.PLAYERS, 0.5F, 0.4F); // Deep pitch

        return true;
    }
}