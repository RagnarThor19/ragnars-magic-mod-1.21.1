package net.ragnar.ragnarsmagicmod.upsidedown;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;

/** Tome of Upside Down: flips the caster's gravity, or flips it back. */
public class UpsideDownSpell implements Spell {
    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw)) return false;
        boolean flip = !UpsideDown.isFlipped(player);
        UpsideDown.setFlipped(player, flip);
        player.fallDistance = 0;

        double x = player.getX(), y = player.getY() + player.getHeight() / 2, z = player.getZ();
        sw.playSound(null, x, y, z, SoundEvents.ENTITY_SHULKER_TELEPORT, SoundCategory.PLAYERS, 1.0f, flip ? 0.6f : 1.4f);
        sw.playSound(null, x, y, z, SoundEvents.ENTITY_PHANTOM_SWOOP, SoundCategory.PLAYERS, 0.6f, flip ? 0.8f : 1.2f);
        sw.spawnParticles(ParticleTypes.REVERSE_PORTAL, x, y, z, 30, 0.3, 0.6, 0.3, 0.05);
        return true;
    }
}
