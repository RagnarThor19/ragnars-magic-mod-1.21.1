package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.entity.projectile.WitherSkullEntity;

public final class WitherSkullSpell implements Spell {
    private static final double SPEED = 1.2;

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return true;

        Vec3d look = player.getRotationVec(1.0f).normalize();
        Vec3d start = player.getEyePos().add(look.multiply(0.6));

        // Clean constructor for 1.21.x
        WitherSkullEntity skull = new WitherSkullEntity(world, player, look.multiply(SPEED));
        skull.setCharged(false);
        skull.setPosition(start.x, start.y, start.z);

        world.spawnEntity(skull);

        // FX
        if (world instanceof ServerWorld sw) {
            // Dark smoke and a few souls at the staff
            Vec3d m = net.ragnar.ragnarsmagicmod.util.CastFx.muzzle(player);
            sw.spawnParticles(ParticleTypes.SMOKE, m.x, m.y, m.z, 8, 0.15, 0.15, 0.15, 0.03);
            sw.spawnParticles(ParticleTypes.SOUL, m.x, m.y, m.z, 3, 0.15, 0.15, 0.15, 0.03);
        }

        world.playSound(null, player.getBlockPos(),
                SoundEvents.ENTITY_WITHER_SHOOT, SoundCategory.PLAYERS, 0.8f, 1.1f + world.random.nextFloat() * 0.15f);
        world.playSound(null, player.getBlockPos(),
                SoundEvents.PARTICLE_SOUL_ESCAPE.value(), SoundCategory.PLAYERS, 1.2f, 0.8f);

        return true;
    }
}
