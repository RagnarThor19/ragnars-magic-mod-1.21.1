package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.entity.projectile.WindChargeEntity;

public final class WindChargeSpell implements Spell {
    private static final double SPEED = 1.0;

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return true;

        Vec3d look = player.getRotationVec(1.0f).normalize();
        Vec3d start = player.getEyePos().add(look.multiply(0.6));

        WindChargeEntity proj = new WindChargeEntity(world, start.x, start.y, start.z, look.multiply(SPEED));
        proj.setOwner(player);
        world.spawnEntity(proj);

        if (world instanceof ServerWorld sw) {
            // A small gust bursting off the staff
            Vec3d m = net.ragnar.ragnarsmagicmod.util.CastFx.muzzle(player);
            sw.spawnParticles(ParticleTypes.SMALL_GUST, m.x, m.y, m.z, 1, 0, 0, 0, 0);
            sw.spawnParticles(ParticleTypes.CLOUD, m.x, m.y, m.z, 0, look.x, look.y, look.z, 0.3);
        }

        world.playSound(null, player.getBlockPos(),
                SoundEvents.ENTITY_WIND_CHARGE_THROW, SoundCategory.PLAYERS, 1.0f, 0.9f + world.random.nextFloat() * 0.2f);
        world.playSound(null, player.getBlockPos(),
                SoundEvents.ENTITY_BREEZE_SHOOT, SoundCategory.PLAYERS, 0.4f, 1.3f);

        proj.setVelocity(look.multiply(SPEED * 1.3)); // manually override internal cap
        proj.velocityModified = true; // ensures it updates server-side

        return true;
    }
}
