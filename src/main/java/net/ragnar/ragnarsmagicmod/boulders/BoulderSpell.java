package net.ragnar.ragnarsmagicmod.boulders;

import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;

/** Tome of Boulders: raises the boulder over your head; it flies a moment later (see BoulderEntity). */
public class BoulderSpell implements Spell {
    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw)) return false;
        BoulderEntity boulder = new BoulderEntity(sw, player);
        sw.spawnEntity(boulder);

        Vec3d c = boulder.center();
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_DEEPSLATE_BREAK, SoundCategory.PLAYERS, 1.5f, 0.5f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_ROOTED_DIRT_BREAK, SoundCategory.PLAYERS, 1.2f, 0.5f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_EVOKER_CAST_SPELL, SoundCategory.PLAYERS, 0.6f, 0.6f);
        sw.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.STONE.getDefaultState()),
                c.x, c.y, c.z, 30, 0.4, 0.4, 0.4, 0.2);
        return true;
    }
}
