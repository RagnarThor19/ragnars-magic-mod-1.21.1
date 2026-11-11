package net.ragnar.ragnarsmagicmod.basalt;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;

/** Tome of Basalt (see Basalt): the gathering starts over the caster's head; the rock flies a second later. */
public class BasaltSpell implements Spell {
    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw)) return false;
        BasaltEntity rock = new BasaltEntity(sw, player);
        sw.spawnEntity(rock);

        // A deep crack in the ground and a surge of heat
        Vec3d c = rock.center();
        Vec3d f = player.getPos();
        sw.playSound(null, f.x, f.y, f.z, SoundEvents.BLOCK_BASALT_BREAK, SoundCategory.PLAYERS, 1.6f, 0.5f);
        sw.playSound(null, f.x, f.y, f.z, SoundEvents.BLOCK_DEEPSLATE_BREAK, SoundCategory.PLAYERS, 1.4f, 0.45f);
        // ...and a deep molten gulp as the heat starts drawing up out of the earth, with the crackle of a lava field
        sw.playSound(null, f.x, f.y, f.z, SoundEvents.ITEM_BUCKET_FILL_LAVA, SoundCategory.PLAYERS, 1.6f, 0.5f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.AMBIENT_BASALT_DELTAS_ADDITIONS.value(), SoundCategory.PLAYERS, 2.0f, 0.7f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_BLAZE_AMBIENT, SoundCategory.PLAYERS, 0.8f, 0.5f);
        sw.spawnParticles(ParticleTypes.LAVA, c.x, c.y, c.z, 6, 0.2, 0.2, 0.2, 0);
        sw.spawnParticles(ParticleTypes.FLAME, c.x, c.y, c.z, 12, 0.15, 0.15, 0.15, 0.03);
        return true;
    }
}
