package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.List;

public class PullingSpell implements Spell {
    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return true;

        double radius = 30.0; // Increased range
        double pullStrength = 1.8; // Much stronger launch

        // Sound: Aggressive wind suction
        world.playSound(null, player.getBlockPos(),
                SoundEvents.ENTITY_EVOKER_PREPARE_ATTACK, SoundCategory.PLAYERS, 0.8f, 1.5f);
        world.playSound(null, player.getBlockPos(),
                SoundEvents.ENTITY_FISHING_BOBBER_RETRIEVE, SoundCategory.PLAYERS, 1.0f, 0.8f);

        // Expand box in ALL directions (including Y axis for vertical pulls)
        Box box = player.getBoundingBox().expand(radius, radius, radius);
        List<Entity> targets = world.getOtherEntities(player, box, e -> e instanceof LivingEntity && !e.isSpectator());

        for (Entity e : targets) {
            // Calculate Vector: Entity -> Player Eye Pos
            Vec3d targetPos = player.getEyePos();
            Vec3d entityPos = e.getPos();

            // The direction vector
            Vec3d dir = targetPos.subtract(entityPos).normalize();

            // Multiply by strength
            Vec3d velocity = dir.multiply(pullStrength);

            // Apply velocity (Launch them!)
            e.addVelocity(velocity.x, velocity.y, velocity.z);
            e.velocityModified = true;

            // Particles: A trail of portal particles leading to you
            if (world instanceof ServerWorld sw) {
                sw.spawnParticles(ParticleTypes.REVERSE_PORTAL, e.getX(), e.getBodyY(0.5), e.getZ(),
                        10, 0.3, 0.3, 0.3, 0.1);
                sw.spawnParticles(ParticleTypes.CLOUD, e.getX(), e.getY(), e.getZ(),
                        3, 0.2, 0.1, 0.2, 0.05);
            }
        }

        return true;
    }
}