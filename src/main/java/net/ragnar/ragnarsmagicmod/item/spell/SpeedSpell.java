package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.world.World;

/** Tome of Speed: Speed I for a minute. */
public class SpeedSpell implements Spell {
    private static final int DURATION = 20 * 60;

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, DURATION, 0));
        world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENTITY_BREEZE_WIND_BURST,
                SoundCategory.PLAYERS, 0.5f, 1.6f);
        world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME,
                SoundCategory.PLAYERS, 0.8f, 1.5f);
        if (world instanceof ServerWorld sw) {
            sw.spawnParticles(ParticleTypes.CLOUD, player.getX(), player.getY() + 0.1, player.getZ(), 12, 0.4, 0.05, 0.4, 0.05);
            sw.spawnParticles(ParticleTypes.END_ROD, player.getX(), player.getY() + 1.0, player.getZ(), 6, 0.3, 0.5, 0.3, 0.02);
        }
        return true;
    }
}
