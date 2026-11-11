package net.ragnar.ragnarsmagicmod.tornado;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;

/** Tome of Tornados (see Tornado): a tornado spins up on the ground a few steps ahead and wanders off. */
public class TornadoSpell implements Spell {
    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw)) return false;
        TornadoEntity tornado = TornadoEntity.summon(sw, player);
        if (tornado == null) return false;

        Vec3d at = tornado.getPos();
        sw.playSound(null, player.getX(), player.getEyeY(), player.getZ(), SoundEvents.ENTITY_BREEZE_SHOOT, SoundCategory.PLAYERS, 0.9f, 0.7f);
        sw.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_WIND_CHARGE_WIND_BURST.value(), SoundCategory.PLAYERS, 1.2f, 0.5f);
        sw.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_BREEZE_WHIRL, SoundCategory.PLAYERS, 1.4f, 0.6f);
        sw.spawnParticles(ParticleTypes.GUST_EMITTER_SMALL, at.x, at.y + 0.5, at.z, 1, 0, 0, 0, 0);
        sw.spawnParticles(ParticleTypes.CLOUD, at.x, at.y + 0.2, at.z, 10, 0.6, 0.1, 0.6, 0.05);
        return true;
    }
}
