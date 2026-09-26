package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.PickaxeItem;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.network.ShakePayload;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Tome of Mining: a pickaxe you don't have to swing. Cast at a block and a 3x3 square facing you is mined out - the
 * block you hit first, then the edges, then the corners, a tick apart, so it crunches outward like a ripple. The
 * drops fly straight to you.
 *
 * It mines what a diamond pickaxe or shovel would: stone, ores, dirt, gravel and the like - never containers on the
 * edges, or anything as hard as obsidian. Hold a pickaxe in your other hand and its Fortune or Silk Touch applies
 * (it doesn't lose durability).
 */
public class MiningSpell implements Spell {
    private static final double REACH = 6.0;
    private static final float MAX_HARDNESS = 30f;  // ancient debris yes, obsidian no

    private record Break(ServerWorld world, UUID player, BlockPos pos, ItemStack tool, int delay, boolean center) {}

    private static final List<Break> PENDING = new ArrayList<>();
    private static boolean registered = false;

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(MiningSpell::tick);
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw) || !player.canModifyBlocks()) return false;
        HitResult hit = player.raycast(REACH, 0f, false);
        if (hit.getType() != HitResult.Type.BLOCK || !(hit instanceof BlockHitResult bhr)) return false;

        ItemStack tool = toolFor(player, staff);
        BlockPos center = bhr.getBlockPos();
        if (!canMine(sw, player, center, tool, true)) return false;
        ensureRegistered();

        // The square facing you: the two axes across the face you hit
        Direction face = bhr.getSide();
        Direction a = face.getAxis() == Direction.Axis.Y ? Direction.EAST : Direction.UP;
        Direction b = face.getAxis() == Direction.Axis.X ? Direction.SOUTH
                : face.getAxis() == Direction.Axis.Z ? Direction.EAST : Direction.SOUTH;
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                int ring = Math.abs(i) + Math.abs(j); // 0 centre, 1 edges, 2 corners
                BlockPos p = center.offset(a, i).offset(b, j);
                // Two ticks per ring: casting happens early in the tick, so one would land edges with the centre
                PENDING.add(new Break(sw, player.getUuid(), p, tool, ring * 2, ring == 0));
            }
        }
        tick(sw); // the centre goes this very tick

        sw.playSound(null, center, SoundEvents.ITEM_TRIDENT_HIT_GROUND, SoundCategory.PLAYERS, 0.6f, 1.6f);
        ShakePayload.around(sw, player.getPos(), 1.5, 0.12f, 4);
        return true;
    }

    /** A diamond pickaxe, or the pickaxe in your other hand so its Fortune/Silk Touch count. */
    private static ItemStack toolFor(PlayerEntity player, ItemStack staff) {
        ItemStack other = player.getMainHandStack() == staff ? player.getOffHandStack() : player.getMainHandStack();
        return other.getItem() instanceof PickaxeItem ? other.copy() : new ItemStack(Items.DIAMOND_PICKAXE);
    }

    private static boolean canMine(ServerWorld world, PlayerEntity player, BlockPos pos, ItemStack tool, boolean center) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || !world.canPlayerModifyAt(player, pos)) return false;
        if (!state.isIn(BlockTags.PICKAXE_MINEABLE) && !state.isIn(BlockTags.SHOVEL_MINEABLE)) return false;
        float hardness = state.getHardness(world, pos);
        if (hardness < 0 || hardness > MAX_HARDNESS) return false;
        if (state.isToolRequired() && !tool.isSuitableFor(state)) return false;
        // Only the block you aim at can be a chest, furnace and so on - never one caught at the edge
        return center || !state.hasBlockEntity();
    }

    private static void tick(ServerWorld world) {
        if (PENDING.isEmpty()) return;
        List<Break> now = new ArrayList<>();
        Iterator<Break> it = PENDING.iterator();
        while (it.hasNext()) {
            Break b = it.next();
            if (b.world() != world) continue;
            if (b.delay() <= 0) {
                now.add(b);
                it.remove();
            }
        }
        // Count the rest down (replace, since records are immutable)
        PENDING.replaceAll(b -> b.world() == world ? new Break(b.world(), b.player(), b.pos(), b.tool(), b.delay() - 1, b.center()) : b);
        for (Break b : now) mine(world, b);
    }

    private static void mine(ServerWorld world, Break b) {
        PlayerEntity player = world.getPlayerByUuid(b.player());
        if (player == null || !canMine(world, player, b.pos(), b.tool(), b.center())) return;

        BlockPos pos = b.pos();
        BlockState state = world.getBlockState(pos);
        BlockEntity be = world.getBlockEntity(pos);
        List<ItemStack> drops = Block.getDroppedStacks(state, world, pos, be, player, b.tool());
        // Breaks it with the normal crumble particles and sound; containers still spill their contents
        if (!world.breakBlock(pos, false, player)) return;
        state.onStacksDropped(world, pos, b.tool(), true); // ore XP

        // The loot flies to you
        Vec3d from = Vec3d.ofCenter(pos);
        Vec3d to = player.getEyePos().add(0, -0.6, 0);
        Vec3d v = to.subtract(from).normalize().multiply(0.45).add(0, 0.12, 0);
        for (ItemStack stack : drops) {
            if (stack.isEmpty()) continue;
            ItemEntity item = new ItemEntity(world, from.x, from.y, from.z, stack, v.x, v.y, v.z);
            item.setPickupDelay(2);
            world.spawnEntity(item);
        }
        if (b.center()) {
            world.spawnParticles(ParticleTypes.CRIT, from.x, from.y, from.z, 10, 0.3, 0.3, 0.3, 0.3);
        }
    }
}
