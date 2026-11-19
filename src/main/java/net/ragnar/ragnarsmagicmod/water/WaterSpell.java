package net.ragnar.ragnarsmagicmod.water;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;

/** Tome of Water: lobs a water ball from just in front of the staff, along your aim. */
public class WaterSpell implements Spell {
    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw)) return false;
        WaterOrbEntity orb = new WaterOrbEntity(sw, player, player.getRotationVec(1f));
        sw.spawnEntity(orb);

        Vec3d p = orb.getPos();
        float pitch = 0.95f + sw.random.nextFloat() * 0.15f;
        sw.playSound(null, p.x, p.y, p.z, SoundEvents.ITEM_BUCKET_EMPTY_AXOLOTL, SoundCategory.PLAYERS, 1.0f, pitch);
        sw.playSound(null, p.x, p.y, p.z, SoundEvents.AMBIENT_UNDERWATER_EXIT, SoundCategory.PLAYERS, 0.7f, 1.5f);
        sw.spawnParticles(ParticleTypes.SPLASH, p.x, p.y, p.z, 14, 0.2, 0.2, 0.2, 0.15);
        return true;
    }
}
