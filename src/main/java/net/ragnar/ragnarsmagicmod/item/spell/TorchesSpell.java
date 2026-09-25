package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.block.BlockState;
import net.minecraft.block.WallTorchBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Tome of Torches: a torch appears right where you're looking - on the floor, on a wall - placed by the
 * same rules as putting one down by hand, just from further away.
 */
public class TorchesSpell implements Spell {
    private static final double RANGE = 48.0;

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        if (!(world instanceof ServerWorld sw)) return false;

        HitResult hit = player.raycast(RANGE, 0.0f, false);
        if (hit.getType() != HitResult.Type.BLOCK || !(hit instanceof BlockHitResult bhr)) return false;
        if (!player.canModifyBlocks() || !world.canPlayerModifyAt(player, bhr.getBlockPos())) return false;

        // Vanilla torch placement: floor or wall torch depending on the face, replaces grass/snow, etc.
        ItemPlacementContext ctx = new ItemPlacementContext(world, player, Hand.MAIN_HAND, new ItemStack(Items.TORCH), bhr);
        BlockPos pos = ctx.getBlockPos();
        if (!((BlockItem) Items.TORCH).place(ctx).isAccepted()) return false;

        BlockState placed = world.getBlockState(pos);
        Vec3d flame = flamePos(pos, placed);

        // Wooden click + the whoomp of it catching light
        sw.playSound(null, pos, SoundEvents.BLOCK_WOOD_PLACE, SoundCategory.BLOCKS, 1.0f, 1.1f);
        sw.playSound(null, pos, SoundEvents.ITEM_FIRECHARGE_USE, SoundCategory.BLOCKS, 0.35f, 1.6f);
        sw.playSound(null, pos, SoundEvents.ITEM_FLINTANDSTEEL_USE, SoundCategory.BLOCKS, 0.6f, 1.2f);

        sw.spawnParticles(ParticleTypes.FLAME, flame.x, flame.y, flame.z, 8, 0.06, 0.06, 0.06, 0.03);
        sw.spawnParticles(ParticleTypes.SMALL_FLAME, flame.x, flame.y, flame.z, 6, 0.15, 0.15, 0.15, 0.02);
        sw.spawnParticles(ParticleTypes.LAVA, flame.x, flame.y, flame.z, 2, 0.05, 0.05, 0.05, 0);
        sw.spawnParticles(ParticleTypes.SMOKE, flame.x, flame.y + 0.1, flame.z, 4, 0.05, 0.05, 0.05, 0.01);
        return true;
    }

    /** Where the torch's flame sits, so the burst comes from the tip rather than the block's middle. */
    private static Vec3d flamePos(BlockPos pos, BlockState state) {
        Vec3d center = Vec3d.ofCenter(pos);
        if (state.contains(WallTorchBlock.FACING)) {
            Direction facing = state.get(WallTorchBlock.FACING);
            return center.add(-facing.getOffsetX() * 0.27, 0.42, -facing.getOffsetZ() * 0.27);
        }
        return center.add(0, 0.2, 0);
    }
}
