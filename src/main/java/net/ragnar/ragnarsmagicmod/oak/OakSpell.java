package net.ragnar.ragnarsmagicmod.oak;

import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;

/**
 * Tome of Oak (see Oak): the oak takes root a few steps behind the caster and a little off to the right, so when it
 * falls the way they're facing the trunk sweeps past their shoulder instead of through their head. It needs ground
 * there and open air above it.
 */
public class OakSpell implements Spell {
    /** How far behind the caster the trunk stands, and how far to their right. */
    public static final double BEHIND = 6.0, ASIDE = 4.2;
    /** How much open air the trunk needs over its foot. */
    static final int ROOM = 12;

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw)) return false;

        float yaw = player.getYaw() * MathHelper.RADIANS_PER_DEGREE;
        Vec3d forward = new Vec3d(-MathHelper.sin(yaw), 0, MathHelper.cos(yaw));
        Vec3d right = new Vec3d(-forward.z, 0, forward.x);
        Vec3d spot = player.getPos().subtract(forward.multiply(BEHIND)).add(right.multiply(ASIDE));

        Vec3d foot = ground(sw, spot);
        if (foot == null) {
            player.sendMessage(Text.literal("No ground behind you for the oak to take root."), true);
            return false;
        }
        if (!roomAbove(sw, BlockPos.ofFloored(foot))) {
            player.sendMessage(Text.literal("No room behind you for the oak to grow."), true);
            return false;
        }

        Oak.TREES.add(new OakTree(sw, player, foot, forward));
        return true;
    }

    /** The top of the ground at {@code spot}: searched a few blocks up (a step) and a good way down (a drop). */
    static Vec3d ground(ServerWorld world, Vec3d spot) {
        BlockPos.Mutable pos = BlockPos.ofFloored(spot.x, spot.y + 3, spot.z).mutableCopy();
        for (int i = 0; i < 16 && pos.getY() > world.getBottomY(); i++, pos.move(Direction.DOWN)) {
            BlockState state = world.getBlockState(pos);
            if (state.getCollisionShape(world, pos).isEmpty()) continue;
            if (!world.getBlockState(pos.up()).getCollisionShape(world, pos.up()).isEmpty()) continue; // inside a wall
            double top = pos.getY() + state.getCollisionShape(world, pos).getMax(Direction.Axis.Y);
            return new Vec3d(spot.x, top, spot.z);
        }
        return null;
    }

    /** Open air for the trunk: its column and the four beside it, {@link #ROOM} blocks up. */
    static boolean roomAbove(ServerWorld world, BlockPos foot) {
        for (int y = 0; y <= ROOM; y++) {
            BlockPos c = foot.up(y);
            for (BlockPos p : new BlockPos[]{c, c.north(), c.south(), c.east(), c.west()}) {
                if (!world.getBlockState(p).getCollisionShape(world, p).isEmpty()) return false;
            }
        }
        return true;
    }
}
