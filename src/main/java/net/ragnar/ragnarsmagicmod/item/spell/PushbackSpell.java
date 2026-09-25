package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public class PushbackSpell implements Spell {

    private static final double STRENGTH = 0.6; // launch power (≈ 6–7 blocks)
    private static final int PARTICLES = 16;

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

        // A blast of air out the front, the recoil that throws you back. Spawned from the server
        // (world.addParticle only works on the client, so the old particles never showed)
        if (world instanceof ServerWorld sw) {
            Vec3d front = player.getEyePos().add(look.multiply(0.8)).add(0, -0.3, 0);
            for (int i = 0; i < PARTICLES; i++) {
                double ox = (sw.random.nextDouble() - 0.5) * 0.5;
                double oy = (sw.random.nextDouble() - 0.5) * 0.5;
                double oz = (sw.random.nextDouble() - 0.5) * 0.5;
                // Count 0 makes the offsets a velocity, so every puff shoots out along the look direction
                Vec3d v = look.add(ox, oy, oz).normalize();
                sw.spawnParticles(ParticleTypes.CLOUD, front.x, front.y, front.z, 0, v.x, v.y, v.z, 0.5 + sw.random.nextDouble() * 0.4);
            }
            sw.spawnParticles(ParticleTypes.SMALL_GUST, front.x, front.y, front.z, 1, 0, 0, 0, 0);
        }

        return true;
    }
}
