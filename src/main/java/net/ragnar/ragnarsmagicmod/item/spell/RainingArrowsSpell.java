// File: src/main/java/net/ragnar/ragnarsmagicmod/item/spell/RainingArrowsSpell.java
package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.util.ArrowRain;

public class RainingArrowsSpell implements Spell {

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

        Vec3d targetPos = hit.getPos();

        if (world instanceof ServerWorld sw) {
            // New "Nice" Cast Sound that fits and is loud enough
            // Breeze Shoot gives a windy "whoosh" + Beacon Activate gives a magical "hum"

            sw.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.BLOCK_DECORATED_POT_INSERT, SoundCategory.PLAYERS, 1.5f, 1.2f);

            ArrowRain.create(sw, targetPos, player.getUuid());
        }

        return true;
    }
}