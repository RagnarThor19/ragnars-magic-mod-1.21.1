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
            sw.spawnParticles(ParticleTypes.SMOKE, player.getX(), player.getY() + 1.0, player.getZ(),
                    12, 0.25, 0.15, 0.25, 0.02);
        }

        world.playSound(null, player.getBlockPos(),
                SoundEvents.ENTITY_WITHER_SHOOT, SoundCategory.PLAYERS, 1.0f, 1.0f);

        return true;
    }
}
