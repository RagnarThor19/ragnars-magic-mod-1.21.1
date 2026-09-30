package net.ragnar.ragnarsmagicmod.util.building;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.util.building.BlockMixer.Layer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns {@link BuildSettings} and where the player is aiming into real block positions. Shared by the client
 * preview and the server, so what you see outlined is exactly what gets built.
 */
public final class BuildPlanner {
    private BuildPlanner() {}

    /** How far away you can build. */
    public static final double REACH = 24.0;

    // A full block, used to check that no mob or player is standing where a block would go
    private static final BlockState PROBE = Blocks.STONE.getDefaultState();

    /** Where to build: the empty block you're pointing into, the face you clicked and which way is "away". */
    public record Target(BlockPos anchor, Direction face, Direction forward) {}

    /** One block of the shape. {@code height} runs 0..1 along the layer axis; {@code order} sorts the build. */
    public record Cell(BlockPos pos, float height, int order) {}

    public record Plan(Target target, List<Cell> free, List<Cell> blocked, List<BuildSettings.Entry> palette,
                       int needed, int available) {
        public boolean unlimited() { return available < 0; }
        public boolean enough() { return unlimited() || available >= needed; }
    }

    public static Target target(World world, PlayerEntity player, float tickDelta) {
        HitResult hit = player.raycast(REACH, tickDelta, false);
        if (!(hit instanceof BlockHitResult bhr) || hit.getType() != HitResult.Type.BLOCK) return null;
        BlockPos pos = bhr.getBlockPos();
        // Grass, snow and the like get built over in place, just like placing a block by hand
        if (world.getBlockState(pos).isReplaceable()) return new Target(pos, Direction.UP, player.getHorizontalFacing());
        Direction face = bhr.getSide();
        Direction forward = face.getAxis().isVertical() ? player.getHorizontalFacing() : face;
        return new Target(pos.offset(face), face, forward);
    }

    /**
     * Every block of the shape in the world. On top of a block it's centred on the spot you aim at and grows up;
     * under a ceiling it hangs down; on the side of a block it sticks out from that face.
     */
    public static List<Cell> cells(BuildSettings s, Target t) {
        int width = s.effectiveWidth(), height = s.effectiveHeight(), length = s.effectiveLength();
        int[] b = ShapeGeometry.bounds(s.shape, width, height, length);
        boolean hanging = t.face() == Direction.DOWN;
        boolean fromFace = s.shape.startsAtAnchor() || t.face().getAxis().isHorizontal();
        Direction forward = t.forward();
        Direction right = forward.rotateYClockwise();

        List<Cell> out = new ArrayList<>();
        for (int[] c : ShapeGeometry.cells(s.shape, width, height, length, s.hollow)) {
            int u = c[0] - (b[0] - 1) / 2;
            int v = hanging ? -c[1] : c[1];
            int w = fromFace ? c[2] : c[2] - (b[2] - 1) / 2;
            BlockPos pos = t.anchor().offset(right, u).up(v).offset(forward, w);

            float layer;
            if (b[1] > 1) layer = hanging ? 1f - c[1] / (float) (b[1] - 1) : c[1] / (float) (b[1] - 1);
            else if (b[2] > 1) layer = c[2] / (float) (b[2] - 1);
            else if (b[0] > 1) layer = c[0] / (float) (b[0] - 1);
            else layer = 0.5f;

            // Build out from where you aimed: layer by layer, nearest first
            int order = c[1] * 10000 + u * u + w * w;
            out.add(new Cell(pos, layer, order));
        }
        out.sort(Comparator.comparingInt(Cell::order));
        return out;
    }

    /** True if a block can go here: empty (or grass/water/snow), inside the world, and nobody standing in it. */
    public static boolean isFree(World world, BlockPos pos) {
        if (!world.isInBuildLimit(pos) || !world.getWorldBorder().contains(pos)) return false;
        if (!world.getBlockState(pos).isReplaceable()) return false;
        return world.canPlace(PROBE, pos, ShapeContext.absent());
    }

    /** The blocks to build with: the chosen mix, or the block in your off hand when none is chosen. */
    public static List<BuildSettings.Entry> palette(BuildSettings s, PlayerEntity player) {
        if (!s.palette.isEmpty()) return s.palette;
        Item off = player.getOffHandStack().getItem();
        if (BuildSettings.isBuildable(off)) return List.of(new BuildSettings.Entry(off, 1, Layer.BOTTOM));
        return List.of();
    }

    /** How many of this item the player carries (hotbar, inventory and off hand; never worn armour). */
    public static int count(PlayerEntity player, Item item) {
        PlayerInventory inv = player.getInventory();
        int n = 0;
        for (ItemStack stack : inv.main) if (stack.isOf(item)) n += stack.getCount();
        for (ItemStack stack : inv.offHand) if (stack.isOf(item)) n += stack.getCount();
        return n;
    }

    /** Takes one of the item, hotbar first so you can watch the stack go down. Returns false if there's none. */
    public static boolean takeOne(PlayerEntity player, Item item) {
        PlayerInventory inv = player.getInventory();
        for (ItemStack stack : inv.main) {
            if (stack.isOf(item) && !stack.isEmpty()) {
                stack.decrement(1);
                inv.markDirty();
                return true;
            }
        }
        for (ItemStack stack : inv.offHand) {
            if (stack.isOf(item) && !stack.isEmpty()) {
                stack.decrement(1);
                inv.markDirty();
                return true;
            }
        }
        return false;
    }

    public static Plan plan(World world, PlayerEntity player, BuildSettings s, Target t) {
        List<Cell> free = new ArrayList<>(), blocked = new ArrayList<>();
        for (Cell c : cells(s, t)) (isFree(world, c.pos()) ? free : blocked).add(c);
        List<BuildSettings.Entry> palette = palette(s, player);
        int available;
        if (player.isCreative()) {
            available = -1;
        } else {
            available = 0;
            for (BuildSettings.Entry e : palette) available += count(player, e.item);
        }
        return new Plan(t, free, blocked, palette, free.size(), available);
    }
}
