package net.ragnar.ragnarsmagicmod.sleep;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;

/** Tome of Sleep Potions: lobs the potion the way you're looking. The higher you aim, the further it goes (up to ~30 blocks). */
public class SleepPotionSpell implements Spell {
    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw)) return false;
        SleepPotionEntity potion = new SleepPotionEntity(sw, player);
        potion.setVelocity(player, player.getPitch(), player.getYaw(), 0f, SleepPotionEntity.SPEED, 0.5f);
        sw.spawnEntity(potion);

        Vec3d mouth = player.getEyePos().add(player.getRotationVec(1f).multiply(0.7));
        sw.playSound(null, player.getX(), player.getEyeY(), player.getZ(), SoundEvents.ENTITY_SPLASH_POTION_THROW, SoundCategory.PLAYERS, 0.8f, 0.7f);
        sw.playSound(null, player.getX(), player.getEyeY(), player.getZ(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.6f, 1.2f);
        sw.spawnParticles(Sleep.MOTE, mouth.x, mouth.y, mouth.z, 8, 0.15, 0.15, 0.15, 0.0);
        return true;
    }
}
