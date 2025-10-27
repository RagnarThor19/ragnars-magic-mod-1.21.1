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
            sw.spawnParticles(ParticleTypes.CLOUD, player.getX(), player.getY() + 1.0, player.getZ(),
                    10, 0.25, 0.15, 0.25, 0.04);
        }

        world.playSound(null, player.getBlockPos(),
                SoundEvents.ENTITY_ENDER_DRAGON_FLAP, SoundCategory.PLAYERS, 0.9f, 1.25f);

        proj.setVelocity(look.multiply(SPEED * 1.3)); // manually override internal cap
        proj.velocityModified = true; // ensures it updates server-side

        return true;
    }
}
