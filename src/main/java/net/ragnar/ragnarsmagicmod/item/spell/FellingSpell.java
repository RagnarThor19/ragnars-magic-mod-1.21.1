package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Chops down the whole tree you are looking at, but only trees that grew naturally.
 * A log structure counts as a natural tree when:
 *  - it is made of unstripped logs of a single type,
 *  - it touches enough naturally grown (non-persistent) leaves; player-placed leaves are persistent,
 *  - none of its logs touch building blocks (planks, glass, stairs, doors...),
 *  - it is rooted in the ground (dirt, grass, moss, mud, mangrove roots...).
 * Logs are broken one wave per tick outward from the hit block, and all drops land at the trunk's base.
 */
public class FellingSpell implements Spell {
    private static final double RANGE = 6.0;
    private static final int MAX_LOGS = 256;          // big jungle / dark oak trees fit comfortably
    private static final int MIN_NATURAL_LEAVES = 4;

    private record Felling(ServerWorld world, Block log, BlockPos dropPos, List<List<BlockPos>> waves, PlayerEntity player) {}

    private static final List<Felling> ACTIVE = new ArrayList<>();
    private static boolean registered = false;

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            Iterator<Felling> it = ACTIVE.iterator();
            while (it.hasNext()) {
                Felling f = it.next();
                if (f.world() != world) continue;
                breakWave(f, f.waves().remove(0));
                if (f.waves().isEmpty()) it.remove();
            }
        });
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        ensureRegistered();

        HitResult hit = player.raycast(RANGE, 0.0F, false);
        if (hit.getType() != HitResult.Type.BLOCK) return false;
        BlockPos start = ((BlockHitResult) hit).getBlockPos();
        BlockState startState = world.getBlockState(start);

        if (!isFellableLog(startState)) {
            player.sendMessage(Text.literal("You must target a tree."), true);
            return false;
        }

        List<List<BlockPos>> waves = collectTree(world, start, startState.getBlock());
        if (waves == null) {
            player.sendMessage(Text.literal("That doesn't look like a natural tree."), true);
            world.playSound(null, start, SoundEvents.BLOCK_WOOD_HIT, SoundCategory.PLAYERS, 1.0f, 0.6f);
            return false;
        }

        // Drops gather at the bottom of the trunk the player hit
        BlockPos base = start;
        while (world.getBlockState(base.down()).isOf(startState.getBlock())) base = base.down();

        ServerWorld sw = (ServerWorld) world;
        world.playSound(null, start, SoundEvents.ITEM_AXE_STRIP, SoundCategory.PLAYERS, 1.2f, 0.7f);
        sw.spawnParticles(ParticleTypes.COMPOSTER, player.getX(), player.getY() + 1.2, player.getZ(), 12, 0.4, 0.4, 0.4, 0.0);

        ACTIVE.add(new Felling(sw, startState.getBlock(), base, waves, player));
        return true;
    }

    private static boolean isFellableLog(BlockState state) {
        return state.isIn(BlockTags.LOGS_THAT_BURN) && isNaturalLog(state);
    }

    /** Stripped logs and six-sided bark blocks never generate in trees; only players make them. */
    private static boolean isNaturalLog(BlockState state) {
        String path = Registries.BLOCK.getId(state.getBlock()).getPath();
        return !path.startsWith("stripped_") && !path.endsWith("_wood") && !path.endsWith("_hyphae");
    }

    /** Flood-fills the tree from {@code start}; returns logs grouped into break waves, or null if it isn't a natural tree. */
    private static List<List<BlockPos>> collectTree(World world, BlockPos start, Block log) {
        Set<BlockPos> seen = new HashSet<>();
        Set<BlockPos> naturalLeaves = new HashSet<>();
        List<List<BlockPos>> waves = new ArrayList<>();
        boolean rooted = false;
        int total = 0;

        ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
        frontier.add(start);
        seen.add(start);

        while (!frontier.isEmpty()) {
            List<BlockPos> wave = new ArrayList<>(frontier);
            frontier.clear();
            waves.add(wave);
            total += wave.size();
            if (total > MAX_LOGS) return null; // too big to be a single tree

            for (BlockPos pos : wave) {
                // Face neighbours: must all be things that occur around trees in nature
                for (Direction dir : Direction.values()) {
                    BlockPos n = pos.offset(dir);
                    BlockState ns = world.getBlockState(n);
                    if (ns.isOf(log)) continue;
                    if (!isNaturalNeighbour(ns)) return null;
                    if (ns.getBlock() instanceof LeavesBlock && !ns.get(LeavesBlock.PERSISTENT)) naturalLeaves.add(n);
                    if (dir == Direction.DOWN && isGround(ns)) rooted = true;
                }
                // Logs connect diagonally in branches (big oak, acacia, jungle)
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            BlockPos n = pos.add(dx, dy, dz);
                            if (seen.contains(n)) continue;
                            BlockState ns = world.getBlockState(n);
                            if (ns.getBlock() instanceof LeavesBlock && !ns.get(LeavesBlock.PERSISTENT)) naturalLeaves.add(n);
                            if (ns.isOf(log)) {
                                seen.add(n);
                                frontier.add(n);
                            }
                        }
                    }
                }
            }
        }

        if (!rooted || naturalLeaves.size() < MIN_NATURAL_LEAVES) return null;
        return waves;
    }

    private static boolean isGround(BlockState state) {
        return state.isIn(BlockTags.DIRT) || state.isOf(Blocks.MANGROVE_ROOTS) || state.isOf(Blocks.MUDDY_MANGROVE_ROOTS);
    }

    /** Blocks that can sit against a naturally generated tree. Anything else means someone built there. */
    private static boolean isNaturalNeighbour(BlockState state) {
        if (state.isAir() || state.isReplaceable()) return true; // grass, vines, snow layers, water...
        if (state.getFluidState().isIn(FluidTags.WATER)) return true;
        if (state.isIn(BlockTags.LEAVES)) {
            // Leaves the player placed are persistent; a tree hugging those is someone's build
            return !(state.getBlock() instanceof LeavesBlock) || !state.get(LeavesBlock.PERSISTENT);
        }
        return (state.isIn(BlockTags.LOGS) && isNaturalLog(state))
                || state.isIn(BlockTags.DIRT)
                || state.isIn(BlockTags.SAND)
                || state.isIn(BlockTags.BASE_STONE_OVERWORLD)
                || state.isIn(BlockTags.FLOWERS)
                || state.isIn(BlockTags.SAPLINGS)
                || state.isIn(BlockTags.SNOW)
                || state.isIn(BlockTags.ICE)
                || state.isIn(BlockTags.CAVE_VINES)
                || state.isOf(Blocks.GRAVEL)
                || state.isOf(Blocks.CLAY)
                || state.isOf(Blocks.COCOA)
                || state.isOf(Blocks.BEE_NEST)
                || state.isOf(Blocks.MOSS_CARPET)
                || state.isOf(Blocks.MANGROVE_ROOTS)
                || state.isOf(Blocks.MUDDY_MANGROVE_ROOTS)
                || state.isOf(Blocks.MANGROVE_PROPAGULE)
                || state.isOf(Blocks.BROWN_MUSHROOM)
                || state.isOf(Blocks.RED_MUSHROOM)
                || state.isOf(Blocks.SWEET_BERRY_BUSH)
                || state.isOf(Blocks.SUGAR_CANE)
                || state.isOf(Blocks.PUMPKIN)
                || state.isOf(Blocks.MELON);
    }

    private static void breakWave(Felling f, List<BlockPos> wave) {
        ServerWorld world = f.world();
        boolean any = false;
        for (BlockPos pos : wave) {
            BlockState state = world.getBlockState(pos);
            if (!state.isOf(f.log())) continue; // changed since the cast
            for (ItemStack drop : Block.getDroppedStacks(state, world, pos, null, f.player(), ItemStack.EMPTY)) {
                Block.dropStack(world, f.dropPos(), drop);
            }
            world.breakBlock(pos, false, f.player());
            any = true;
        }
        if (any && !wave.isEmpty()) {
            BlockPos p = wave.get(0);
            world.spawnParticles(ParticleTypes.CRIT, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, 4, 0.3, 0.3, 0.3, 0.0);
        }
    }
}
