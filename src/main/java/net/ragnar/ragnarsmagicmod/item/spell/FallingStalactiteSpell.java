package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.PointedDripstoneBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.block.PointedDripstoneBlock;
import net.minecraft.block.enums.Thickness;
import net.minecraft.util.math.Direction;


public final class FallingStalactiteSpell implements Spell {
    private static final double RANGE = 48.0;   // aim distance
    private static final int DROP_HEIGHT = 25;  // blocks above target to spawn the tip

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return true;

        // Cast cue (stone-ish click)
        world.playSound(
                null,
                player.getBlockPos(),
                SoundEvents.BLOCK_STONE_PLACE,
                SoundCategory.PLAYERS,
                0.7f,
                0.9f
        );

        // Find target from look
        HitResult hr = player.raycast(RANGE, 0f, false);
        Vec3d hitPos;
        if (hr.getType() == HitResult.Type.BLOCK) {
            hitPos = ((BlockHitResult) hr).getPos();
        } else {
            Vec3d eye = player.getEyePos();
            Vec3d dir = player.getRotationVec(1.0f).normalize().multiply(RANGE);
            hitPos = eye.add(dir);
        }

        int cx = MathHelper.floor(hitPos.x);
        int cz = MathHelper.floor(hitPos.z);
        int targetY = MathHelper.floor(hitPos.y);
        int spawnY = MathHelper.clamp(targetY + DROP_HEIGHT, world.getBottomY() + 6, world.getTopY() - 6);

        // Positions: stalactite tip at pos; support block directly above
        BlockPos tipPos = BlockPos.ofFloored(cx + 0.5, spawnY + 0.01, cz + 0.5);
        BlockPos supportPos = tipPos.up();

        // 1) Place a temporary solid block above to allow valid placement
        // (Pointed dripstone requires support to place when facing DOWN)
        world.setBlockState(supportPos, Blocks.STONE.getDefaultState(), 3);

        // 2) Place a downward-facing pointed dripstone tip (single piece)
        BlockState tip = Blocks.POINTED_DRIPSTONE.getDefaultState()
                .with(PointedDripstoneBlock.VERTICAL_DIRECTION, Direction.DOWN)
                .with(PointedDripstoneBlock.THICKNESS, Thickness.TIP)
                .with(PointedDripstoneBlock.WATERLOGGED, false);
        world.setBlockState(tipPos, tip, 3);


        // 3) Break the support immediately so it falls *naturally*
        // (no drops from the support)
        world.breakBlock(supportPos, false);

        // Optional small cue at the target
        world.playSound(
                null,
                BlockPos.ofFloored(cx, targetY, cz),
                SoundEvents.BLOCK_DRIPSTONE_BLOCK_BREAK, // rocky crumble vibe
                SoundCategory.PLAYERS,
                0.6f,
                0.9f
        );

        return true;
    }
}
