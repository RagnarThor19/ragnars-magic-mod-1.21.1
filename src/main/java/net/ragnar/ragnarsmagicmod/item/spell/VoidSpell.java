// File: src/main/java/net/ragnar/ragnarsmagicmod/item/spell/VoidSpell.java
package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.util.VoidZone;

public class VoidSpell implements Spell {
    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return true;

        // Long range raycast (40 blocks) for a Master Spell
        Vec3d start = player.getEyePos();
        Vec3d end = start.add(player.getRotationVec(1.0f).multiply(40.0));

        BlockHitResult hit = world.raycast(new RaycastContext(
                start,
                end,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                player
        ));

        if (hit.getType() == HitResult.Type.BLOCK) {
            if (world instanceof ServerWorld sw) {
                // A thread of void shoots from the staff to the target
                Vec3d from = start.add(player.getRotationVec(1.0f).multiply(1.0)).subtract(0, 0.3, 0);
                Vec3d path = hit.getPos().subtract(from);
                int steps = (int) (path.length() * 3);
                for (int i = 0; i <= steps; i++) {
                    Vec3d p = from.add(path.multiply(i / (double) Math.max(1, steps)));
                    sw.spawnParticles(i % 3 == 0 ? ParticleTypes.SCULK_SOUL : ParticleTypes.REVERSE_PORTAL, p.x, p.y, p.z, 1, 0.02, 0.02, 0.02, 0.0);
                }
                world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_WITHER_SHOOT, SoundCategory.PLAYERS, 0.8f, 0.5f);

                // Create the void zone at the impact point
                VoidZone.create(sw, hit.getPos(), player.getUuid());
            }
            return true;
        }

        return false;
    }
}