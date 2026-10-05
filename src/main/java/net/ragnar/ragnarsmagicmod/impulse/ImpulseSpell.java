package net.ragnar.ragnarsmagicmod.impulse;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;

/** Tome of Impulse: lobs the cube from just in front of the staff, along your aim, like throwing an ender pearl. */
public class ImpulseSpell implements Spell {
    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw)) return false;
        ImpulseCubeEntity cube = new ImpulseCubeEntity(sw, player, player.getRotationVec(1f));
        sw.spawnEntity(cube);

        Vec3d p = cube.getPos();
        sw.playSound(null, player.getX(), player.getEyeY(), player.getZ(), SoundEvents.ENTITY_ENDER_PEARL_THROW, SoundCategory.PLAYERS, 0.8f, 0.6f);
        sw.playSound(null, player.getX(), player.getEyeY(), player.getZ(), SoundEvents.ENTITY_WIND_CHARGE_THROW, SoundCategory.PLAYERS, 0.7f, 0.8f);
        sw.playSound(null, p.x, p.y, p.z, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.0f, 0.6f);
        sw.spawnParticles(ParticleTypes.REVERSE_PORTAL, p.x, p.y, p.z, 12, 0.1, 0.1, 0.1, 0.05);
        return true;
    }
}
