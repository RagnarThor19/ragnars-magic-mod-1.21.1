package net.ragnar.ragnarsmagicmod.balllightning;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;
import net.ragnar.ragnarsmagicmod.sound.ModSoundEvents;

/** Tome of Ball Lightning: launches the ball from just in front of the staff, straight along your aim. */
public class BallLightningSpell implements Spell {
    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw)) return false;
        Vec3d look = player.getRotationVec(1f);
        BallLightningEntity ball = new BallLightningEntity(sw, player, look);
        sw.spawnEntity(ball);

        Vec3d p = ball.getPos();
        sw.playSound(null, player.getX(), player.getEyeY(), player.getZ(), ModSoundEvents.ZAP_CAST, SoundCategory.PLAYERS, 1.2f, 0.7f);
        sw.playSound(null, p.x, p.y, p.z, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.PLAYERS, 0.5f, 1.8f);
        sw.playSound(null, p.x, p.y, p.z, SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 0.8f, 1.9f);
        sw.playSound(null, p.x, p.y, p.z, SoundEvents.BLOCK_COPPER_BULB_TURN_ON, SoundCategory.PLAYERS, 0.8f, 0.8f);
        sw.spawnParticles(ParticleTypes.ELECTRIC_SPARK, p.x, p.y, p.z, 25, 0.2, 0.2, 0.2, 0.35);
        sw.spawnParticles(ParticleTypes.FLASH, p.x, p.y, p.z, 1, 0, 0, 0, 0);
        return true;
    }
}
