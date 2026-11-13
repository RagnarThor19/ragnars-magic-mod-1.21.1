package net.ragnar.ragnarsmagicmod.terraform;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.TallPlantBlock;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.state.property.Properties;

/**
 * What counts as land to the Tome of Terraforming, and what it does with the things lying on it.
 * <ul>
 *   <li><b>Terrain</b> is natural ground: dirt and grass of every kind, sand, gravel, clay, the natural stones, Nether
 *       and End ground. It's the only thing ever cut away or piled up.</li>
 *   <li><b>Decorations</b> are the little things on top of the ground - grass, flowers, saplings, mushrooms, snow -
 *       which ride up and down with it.</li>
 *   <li><b>Open</b> space is air and water: the ground can rise into it.</li>
 *   <li>Anything else - your builds, logs, ores, chests, lava - is never touched, and stops the brush where it is.</li>
 * </ul>
 */
public final class Terrain {
    private Terrain() {}

    public static boolean isTerrain(BlockState s) {
        return s.isIn(BlockTags.DIRT) || s.isIn(BlockTags.SAND) || s.isIn(BlockTags.BASE_STONE_OVERWORLD)
                || s.isIn(BlockTags.BASE_STONE_NETHER) || s.isIn(BlockTags.TERRACOTTA)
                || s.isOf(Blocks.GRAVEL) || s.isOf(Blocks.CLAY) || s.isOf(Blocks.SANDSTONE) || s.isOf(Blocks.RED_SANDSTONE)
                || s.isOf(Blocks.SNOW_BLOCK) || s.isOf(Blocks.DIRT_PATH) || s.isOf(Blocks.FARMLAND)
                || s.isOf(Blocks.CALCITE) || s.isOf(Blocks.DRIPSTONE_BLOCK) || s.isOf(Blocks.SMOOTH_BASALT)
                || s.isOf(Blocks.SOUL_SAND) || s.isOf(Blocks.SOUL_SOIL) || s.isOf(Blocks.END_STONE)
                || s.isOf(Blocks.PACKED_MUD) || s.isOf(Blocks.CRIMSON_NYLIUM) || s.isOf(Blocks.WARPED_NYLIUM);
    }

    /** Ground covers that only belong on top: buried, they turn to their dirt underneath. */
    public static boolean isCover(BlockState s) {
        return s.isOf(Blocks.GRASS_BLOCK) || s.isOf(Blocks.PODZOL) || s.isOf(Blocks.MYCELIUM)
                || s.isOf(Blocks.CRIMSON_NYLIUM) || s.isOf(Blocks.WARPED_NYLIUM) || s.isOf(Blocks.DIRT_PATH);
    }

    /** What a cover becomes when something is piled on it. */
    public static BlockState buried(BlockState cover) {
        if (cover.isOf(Blocks.CRIMSON_NYLIUM) || cover.isOf(Blocks.WARPED_NYLIUM)) return Blocks.NETHERRACK.getDefaultState();
        return Blocks.DIRT.getDefaultState();
    }

    /** Little things lying on the ground that move with it: grass, flowers, saplings, mushrooms, snow. */
    public static boolean isDecoration(BlockState s) {
        if (s.isAir() || !s.getFluidState().isEmpty() && !s.contains(Properties.WATERLOGGED)) return false;
        return s.isIn(BlockTags.REPLACEABLE) || s.isIn(BlockTags.FLOWERS) || s.isIn(BlockTags.SAPLINGS)
                || s.isOf(Blocks.BROWN_MUSHROOM) || s.isOf(Blocks.RED_MUSHROOM) || s.isOf(Blocks.MOSS_CARPET)
                || s.isOf(Blocks.SWEET_BERRY_BUSH) || s.isOf(Blocks.CRIMSON_ROOTS) || s.isOf(Blocks.WARPED_ROOTS)
                || s.isOf(Blocks.NETHER_SPROUTS) || s.isOf(Blocks.CRIMSON_FUNGUS) || s.isOf(Blocks.WARPED_FUNGUS);
    }

    /** A decoration two blocks tall (tall grass, sunflowers...): those can't be carried, so they're cleared. */
    public static boolean isTall(BlockState s) {
        return s.contains(TallPlantBlock.HALF);
    }

    /** Air or water: room for the ground to rise into. */
    public static boolean isOpen(BlockState s) {
        if (s.isAir()) return true;
        FluidState f = s.getFluidState();
        return s.isOf(Blocks.WATER) || (f.isIn(FluidTags.WATER) && s.isIn(BlockTags.REPLACEABLE));
    }

    public static boolean isWater(BlockState s) {
        return s.isOf(Blocks.WATER);
    }

    public static boolean isLava(BlockState s) {
        return s.getFluidState().isIn(FluidTags.LAVA);
    }

    /** Leaves and the like hanging over the ground: looked through when finding it, never moved. */
    public static boolean isCanopy(BlockState s) {
        return s.isIn(BlockTags.LEAVES);
    }

    /** Items from your inventory that can be turned into earth when your reserve runs out. */
    public static boolean isEarthItem(Item item) {
        if (item == Items.COBBLESTONE || item == Items.COBBLED_DEEPSLATE || item == Items.MOSSY_COBBLESTONE) return true;
        return item instanceof BlockItem bi && isTerrain(bi.getBlock().getDefaultState()) && !isCover(bi.getBlock().getDefaultState());
    }
}
