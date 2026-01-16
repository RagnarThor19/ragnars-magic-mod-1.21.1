// File: src/main/java/net/ragnar/ragnarsmagicmod/item/spell/ImpalingSpell.java
package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.util.SpikeWave;

public class ImpalingSpell implements Spell {

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return true;

        Vec3d start = player.getEyePos();
        Vec3d end = start.add(player.getRotationVec(1.0f).multiply(32.0));

        BlockHitResult hit = world.raycast(new RaycastContext(
                start,
                end,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                player
        ));

        if (hit.getType() != HitResult.Type.BLOCK) return false;

        BlockPos targetPos = hit.getBlockPos();

        if (world instanceof ServerWorld sw) {
            sw.playSound(null, targetPos, SoundEvents.BLOCK_DEEPSLATE_BREAK, SoundCategory.PLAYERS, 1.0f, 0.5f);
            sw.playSound(null, targetPos, SoundEvents.ENTITY_VEX_AMBIENT, SoundCategory.PLAYERS, 1.5f, 0.5f);

            sw.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE,
                    targetPos.getX() + 0.5, targetPos.getY() + 1.1, targetPos.getZ() + 0.5,
                    10, 0.2, 0.1, 0.2, 0.05);

            SpikeWave.queueSingleSpike(
                    sw,
                    targetPos.getX() + 0.5,
                    targetPos.getZ() + 0.5,
                    targetPos.getY() + 1.1,
                    0.0, 0.0,
                    15,       // Delay
                    40,       // Lifetime
                    player.getUuid(),
                    3,        // Height
                    1.5,      // Lift: (Unchanged, but now reliable because they aren't stuck!)
                    10.0f     // Damage: 10.0f once = 5 Hearts
            );
        }

        return true;
    }
}