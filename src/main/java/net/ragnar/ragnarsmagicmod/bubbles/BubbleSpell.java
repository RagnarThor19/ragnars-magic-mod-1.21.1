package net.ragnar.ragnarsmagicmod.bubbles;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;

/** Tome of Bubbles: blows the bubble out in front of the staff, drifting off roughly where you look. */
public class BubbleSpell implements Spell {
    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw)) return false;
        BubbleEntity bubble = new BubbleEntity(sw, player, player.getRotationVec(1f));
        sw.spawnEntity(bubble);

        Vec3d c = bubble.center();
        sw.playSound(null, player.getX(), player.getEyeY(), player.getZ(), SoundEvents.ITEM_BUCKET_EMPTY, SoundCategory.PLAYERS, 0.8f, 1.5f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_INSIDE, SoundCategory.PLAYERS, 1.0f, 1.4f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_SLIME_SQUISH, SoundCategory.PLAYERS, 0.6f, 1.7f);
        Vec3d mouth = player.getEyePos().add(player.getRotationVec(1f).multiply(0.8));
        sw.spawnParticles(ParticleTypes.SPLASH, mouth.x, mouth.y, mouth.z, 20, 0.2, 0.2, 0.2, 0.15);
        sw.spawnParticles(ParticleTypes.BUBBLE_POP, mouth.x, mouth.y, mouth.z, 8, 0.25, 0.25, 0.25, 0.03);
        return true;
    }
}
