package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public class GhoststepSpell implements Spell {

    private static final double MAX_DISTANCE = 6.0D;

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) {
            return false;
        }

        if (!(world instanceof ServerWorld sw)) {
            return false;
        }

        Vec3d start = player.getPos();

        // Only use horizontal direction, ignore vertical
        Vec3d look = player.getRotationVec(1.0F);
        Vec3d horiz = new Vec3d(look.x, 0.0D, look.z);

        if (horiz.lengthSquared() < 1.0E-4) {
            return false; // player is looking straight up/down, no horizontal dir
        }

        horiz = horiz.normalize();

        double distance = MAX_DISTANCE;

        // Raycast so you don't blink inside walls
        HitResult hit = player.raycast(MAX_DISTANCE, 1.0F, false);
        if (hit.getType() == HitResult.Type.BLOCK) {
            Vec3d hitPos = hit.getPos();
            double hitDist = hitPos.distanceTo(start);
            distance = Math.max(0.0D, hitDist - 0.3D);
        }

        if (distance <= 0.05D) {
            // Nowhere to go
            return false;
        }

        Vec3d target = start.add(horiz.multiply(distance)).add(0.0D, 0.15D, 0.0D);

        // Particles at start
        sw.spawnParticles(
                ParticleTypes.SMOKE,
                start.x, start.y + 1.0, start.z,
                8,
                0.2, 0.2, 0.2,
                0.01
        );

        // Sound at start
        world.playSound(
                null,
                start.x, start.y, start.z,
                SoundEvents.BLOCK_BAMBOO_WOOD_HIT,
                SoundCategory.PLAYERS,
                0.6F,
                1.4F
        );

        // Teleport
        if (player instanceof ServerPlayerEntity serverPlayer) {
            serverPlayer.teleport(
                    sw,
                    target.x,
                    target.y,
                    target.z,
                    player.getYaw(),
                    player.getPitch()
            );
        } else {
            // Client-side fallback
            player.requestTeleport(target.x, target.y, target.z);
        }

        // Particles at end
        Vec3d end = player.getPos();
        sw.spawnParticles(
                ParticleTypes.SMOKE,
                end.x, end.y + 1.0, end.z,
                8,
                0.2, 0.2, 0.2,
                0.01
        );

        // Sound at end
        world.playSound(
                null,
                end.x, end.y, end.z,
                SoundEvents.ENTITY_ILLUSIONER_MIRROR_MOVE,
                SoundCategory.PLAYERS,
                0.6F,
                1.0F
        );

        return true;
    }
}
