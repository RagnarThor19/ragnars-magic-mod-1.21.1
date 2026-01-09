package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.WallTorchBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public class TorchesSpell implements Spell {

    private static final double RANGE = 48.0;

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        // Raycast to find target block
        HitResult hit = player.raycast(RANGE, 0.0f, false);

        // Visuals: Launch particle trail (Client & Server)
        Vec3d start = player.getEyePos().add(player.getRotationVector().multiply(0.5));
        Vec3d end = hit.getPos();
        double dist = start.distanceTo(end);
        Vec3d dir = end.subtract(start).normalize();

        // Spawn a quick line of yellow particles to look like a projectile
        if (world instanceof ServerWorld sw) {
            for (double d = 0; d < dist; d += 1.5) {
                Vec3d p = start.add(dir.multiply(d));
                sw.spawnParticles(ParticleTypes.SMALL_FLAME, p.x, p.y, p.z, 1, 0, 0, 0, 0);
            }
        }

        if (world.isClient) return true; // Client only needs visuals

        if (hit.getType() == HitResult.Type.BLOCK) {
            BlockHitResult bhr = (BlockHitResult) hit;
            BlockPos hitPos = bhr.getBlockPos();
            Direction side = bhr.getSide();
            BlockPos placePos = hitPos.offset(side);

            // Don't replace existing blocks unless they are replaceable (like grass/water)
            if (!world.getBlockState(placePos).isReplaceable()) return false;

            boolean placed = false;

            // Logic: Wall Torch vs Floor Torch
            if (side == Direction.UP) {
                // Floor placement
                BlockState floorTorch = Blocks.TORCH.getDefaultState();
                if (floorTorch.canPlaceAt(world, placePos)) {
                    world.setBlockState(placePos, floorTorch);
                    placed = true;
                }
            } else if (side.getAxis().isHorizontal()) {
                // Wall placement
                BlockState wallTorch = Blocks.WALL_TORCH.getDefaultState()
                        .with(WallTorchBlock.FACING, side);
                if (wallTorch.canPlaceAt(world, placePos)) {
                    world.setBlockState(placePos, wallTorch);
                    placed = true;
                }
            }

            if (placed) {
                // Satisfying 'Pop' sounds
                world.playSound(null, placePos, SoundEvents.BLOCK_WOOD_PLACE, SoundCategory.BLOCKS, 1.0f, 1.2f);
                world.playSound(null, placePos, SoundEvents.ITEM_FIRECHARGE_USE, SoundCategory.BLOCKS, 0.3f, 1.0f);

                // Pop particles at target
                ((ServerWorld) world).spawnParticles(ParticleTypes.FLAME, placePos.getX() + 0.5, placePos.getY() + 0.5, placePos.getZ() + 0.5, 5, 0.1, 0.1, 0.1, 0.05);
                return true;
            }
        }

        return false;
    }
}