package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public class PushbackSpell implements Spell {

    private static final double STRENGTH = 0.6; // launch power (≈ 6–7 blocks)
    private static final int PARTICLES = 50;

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;

        // Look direction
        Vec3d look = player.getRotationVector().normalize();

        // Reverse it for pushback (opposite direction)
        Vec3d push = look.multiply(-STRENGTH, -STRENGTH, -STRENGTH);

        // Add slight upward lift for smoother flight
        player.addVelocity(push.x, push.y + 0.5, push.z);
        player.velocityModified = true;

        // Simple push sound
        world.playSound(null, player.getBlockPos(),
                SoundEvents.ENTITY_FIREWORK_ROCKET_LAUNCH,
                SoundCategory.PLAYERS, 0.7f, 1.6f);

        // Small wind-like particles
        for (int i = 0; i < PARTICLES; i++) {
            double ox = (world.random.nextDouble() - 0.5) * 0.6;
            double oy = (world.random.nextDouble() - 0.5) * 0.6;
            double oz = (world.random.nextDouble() - 0.5) * 0.6;
            world.addParticle(ParticleTypes.CLOUD,
                    player.getX() + ox, player.getY() + 1.0 + oy, player.getZ() + oz,
                    -look.x * 0.4, 0.02, -look.z * 0.4);
        }

        return true;
    }
}
