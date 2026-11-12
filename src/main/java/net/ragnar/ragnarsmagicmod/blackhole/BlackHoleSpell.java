package net.ragnar.ragnarsmagicmod.blackhole;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;

/** Tome of Black Holes (see BlackHoles): the hole starts forming in front of the caster; it's loosed 2.5 seconds later. */
public class BlackHoleSpell implements Spell {
    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw)) return false;
        BlackHoleEntity hole = new BlackHoleEntity(sw, player);
        sw.spawnEntity(hole);

        // Space tearing: a hollow, falling whoomp, a glassy crack and a low drone that starts to rise
        Vec3d c = hole.center();
        Vec3d f = player.getPos();
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_END_PORTAL_FRAME_FILL, SoundCategory.PLAYERS, 2.0f, 0.5f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, SoundCategory.PLAYERS, 1.6f, 0.5f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 1.5f, 0.5f);
        sw.playSound(null, f.x, f.y, f.z, SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 1.0f, 0.4f);
        sw.spawnParticles(ParticleTypes.REVERSE_PORTAL, c.x, c.y, c.z, 40, 0.1, 0.1, 0.1, 0.25);
        return true;
    }
}
