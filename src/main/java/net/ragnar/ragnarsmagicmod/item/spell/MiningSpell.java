package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.server.world.ServerWorld;

import java.util.HashSet;
import java.util.Set;

public class MiningSpell implements Spell {

    // Blocks that count as “underground” (safe generic tags)
    private static final Set<String> VALID_BLOCKS = new HashSet<>();

    static {
        // Substrings that define underground blocks
        String[] valid = new String[]{
                "stone", "deepslate", "granite", "diorite", "andesite",
                "tuff", "calcite", "gravel", "dirt", "sandstone",
                "ore", "basalt", "blackstone"
        };
        for (String s : valid) VALID_BLOCKS.add(s);
    }

    private static boolean isUndergroundBlock(Block block) {
        String name = block.getTranslationKey().toLowerCase();
        for (String key : VALID_BLOCKS) {
            if (name.contains(key)) return true;
        }
        return false;
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;

        // raycast to see what block the player is looking at
        HitResult hit = player.raycast(6.0D, 0.0F, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return false;
        }

        BlockHitResult bhr = (BlockHitResult) hit;
        BlockPos center = bhr.getBlockPos();
        Direction face = bhr.getSide();

        // We’ll mine a 3×3×3 cube around that block
        int radius = 1;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos target = center.add(dx, dy, dz);
                    BlockState state = world.getBlockState(target);
                    Block block = state.getBlock();

                    if (isUndergroundBlock(block)) {
                        if (!state.isAir() && state.getHardness(world, target) >= 0) {
                            // spawn block particles for satisfaction
                            ((ServerWorld) world).spawnParticles(
                                    ParticleTypes.CRIT,
                                    target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5,
                                    6, 0.3, 0.3, 0.3, 0.0
                            );
                            // drop items + remove block
                            world.breakBlock(target, true, player);
                        }
                    }
                }
            }
        }

        // Play “mining burst” sound and feedback
        world.playSound(null, player.getBlockPos(),
                SoundEvents.BLOCK_STONE_BREAK, SoundCategory.PLAYERS, 1.0f, 0.7f);
        world.playSound(null, player.getBlockPos(),
                SoundEvents.BLOCK_DEEPSLATE_BREAK, SoundCategory.PLAYERS, 0.9f, 0.8f);

        // Casting flash particles near player
        ((ServerWorld) world).spawnParticles(
                ParticleTypes.POOF,
                player.getX(), player.getY() + 1.2, player.getZ(),
                20, 0.4, 0.4, 0.4, 0.01
        );

        return true;
    }
}
