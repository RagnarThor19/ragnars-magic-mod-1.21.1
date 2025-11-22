package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.block.BlockState;
import net.minecraft.block.Fertilizable;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

public class GrowthSpell implements Spell {

    private static final double RANGE = 6.0;
    // No burst radius needed for single block
    private static final int GROWTH_ATTEMPTS = 3; // Tries to grow 3 times per click (Instant farm!)

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return true;

        HitResult hit = player.raycast(RANGE, 0f, false);
        if (hit.getType() != HitResult.Type.BLOCK) return false;

        BlockPos center = ((BlockHitResult) hit).getBlockPos();
        ServerWorld sw = (ServerWorld) world;
        boolean grewAnything = false;

        // Play "Nature" sound
        world.playSound(null, center, SoundEvents.BLOCK_COMPOSTER_FILL_SUCCESS, SoundCategory.PLAYERS, 1.0f, 1.2f);
        world.playSound(null, center, SoundEvents.ITEM_BONE_MEAL_USE, SoundCategory.PLAYERS, 1.0f, 1.0f);

        // Target ONLY the block you are looking at
        BlockPos pos = center;
        BlockState state = world.getBlockState(pos);

        if (state.getBlock() instanceof Fertilizable fertilizable) {
            // Check validity
            if (fertilizable.isFertilizable(world, pos, state)) {
                // Try to grow multiple times to max it out
                for(int i=0; i<GROWTH_ATTEMPTS; i++) {
                    if (fertilizable.canGrow(world, world.random, pos, state)) {
                        fertilizable.grow(sw, world.random, pos, state);
                        grewAnything = true;
                        state = world.getBlockState(pos); // update state for next loop
                    } else {
                        break; // Maxed out
                    }
                }

                // Spawn "Happy Villager" (Green Star) particles
                sw.spawnParticles(ParticleTypes.HAPPY_VILLAGER,
                        pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                        5, 0.3, 0.3, 0.3, 0.05);
            }
        }

        return grewAnything;
    }
}