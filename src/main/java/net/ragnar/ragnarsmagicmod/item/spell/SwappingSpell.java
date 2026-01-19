package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public class SwappingSpell implements Spell {

    private static final double RANGE = 100.0;

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        // REMOVED: if (world.isClient) return true;
        // We want the client to run the raycast below so it knows if it missed!

        // 1. Raycast for Entity (Runs on Client & Server)
        Vec3d start = player.getCameraPosVec(1.0F);
        Vec3d dir = player.getRotationVec(1.0F);
        Vec3d end = start.add(dir.multiply(RANGE));
        Box box = player.getBoundingBox().stretch(dir.multiply(RANGE)).expand(1.0, 1.0, 1.0);

        EntityHitResult hit = ProjectileUtil.raycast(
                player,
                start,
                end,
                box,
                (entity) -> !entity.isSpectator() && entity instanceof LivingEntity,
                RANGE * RANGE
        );

        if (hit != null && hit.getEntity() instanceof LivingEntity target) {

            // 2. Logic that modifies the world (Server Only)
            if (!world.isClient) {
                Vec3d playerPos = player.getPos();
                Vec3d targetPos = target.getPos();

                // Perform Swap
                player.requestTeleport(targetPos.x, targetPos.y, targetPos.z);
                target.requestTeleport(playerPos.x, playerPos.y, playerPos.z);

                // Sounds
                float pitch = 1.0f + (world.random.nextFloat() * 0.2f);
                world.playSound(null, playerPos.x, playerPos.y, playerPos.z,
                        SoundEvents.BLOCK_DECORATED_POT_INSERT, SoundCategory.PLAYERS, 1.3f, pitch);
                world.playSound(null, targetPos.x, targetPos.y, targetPos.z,
                        SoundEvents.BLOCK_DECORATED_POT_INSERT, SoundCategory.PLAYERS, 1.3f, pitch);

                // Particles
                if (world instanceof ServerWorld sw) {
                    sw.spawnParticles(ParticleTypes.POOF, playerPos.x, playerPos.y + 1.0, playerPos.z, 10, 0.3, 0.5, 0.3, 0.05);
                    sw.spawnParticles(ParticleTypes.POOF, targetPos.x, targetPos.y + 1.0, targetPos.z, 10, 0.3, 0.5, 0.3, 0.05);
                    sw.spawnParticles(ParticleTypes.TRIAL_SPAWNER_DETECTION, playerPos.x, playerPos.y + 1.0, playerPos.z, 5, 0.3, 0.5, 0.3, 0.05);
                }
            }

            // Return TRUE because we hit something.
            // StaffItem sees "true" -> Applies Cooldown & XP.
            return true;
        }

        // Return FALSE because we missed.
        // StaffItem sees "false" -> NO Cooldown, NO XP.
        return false;
    }
}