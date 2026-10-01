package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.spell.BuildingSpell;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.util.building.BlockMixer;
import net.ragnar.ragnarsmagicmod.util.building.BuildPlanner;
import net.ragnar.ragnarsmagicmod.util.building.BuildSettings;
import net.ragnar.ragnarsmagicmod.util.building.BuildShape;
import net.ragnar.ragnarsmagicmod.util.building.ShapeGeometry;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** In-world tests for the Tome of Building. Run with {@code ./gradlew runGametest}. */
public class BuildingGameTests implements FabricGameTest {
    // Every build here is aimed at this spot (relative to the 8x8x8 test area), facing south
    private static final BlockPos ANCHOR = new BlockPos(4, 1, 3);
    // Long enough for the biggest build to finish placing
    private static final int SETTLE = BuildingSpell.GROW_TICKS + 14;

    // ------------------------------------------------------------------ helpers

    private static PlayerEntity player(TestContext ctx, GameMode mode) {
        PlayerEntity p = ctx.createMockPlayer(mode);
        mode.setAbilities(p.getAbilities());
        p.setPosition(Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(4, 1, 0))));
        return p;
    }

    private static void give(PlayerEntity p, Item item, int count) {
        while (count > 0) {
            int n = Math.min(64, count);
            p.getInventory().insertStack(new ItemStack(item, n));
            count -= n;
        }
    }

    private static BuildSettings settings(BuildShape shape, int w, int h, int l, boolean hollow, Item... items) {
        BuildSettings s = new BuildSettings();
        s.shape = shape;
        s.width = w;
        s.height = h;
        s.length = l;
        s.hollow = hollow;
        for (int i = 0; i < items.length; i++) s.palette.add(new BuildSettings.Entry(items[i], 1, BlockMixer.Layer.byIndex(i)));
        s.clamp();
        return s;
    }

    private static BuildPlanner.Target target(TestContext ctx, BlockPos rel, Direction face, Direction forward) {
        return new BuildPlanner.Target(ctx.getAbsolutePos(rel), face, forward);
    }

    private static BuildPlanner.Target up(TestContext ctx) {
        return target(ctx, ANCHOR, Direction.UP, Direction.SOUTH);
    }

    private static boolean build(TestContext ctx, PlayerEntity p, BuildPlanner.Target t, BuildSettings s) {
        return BuildingSpell.build(ctx.getWorld(), p, t, s);
    }

    private static int countIn(TestContext ctx, Block block) {
        int n = 0;
        for (int x = 0; x < 8; x++) for (int y = 0; y < 8; y++) for (int z = 0; z < 8; z++) {
            if (ctx.getBlockState(new BlockPos(x, y, z)).isOf(block)) n++;
        }
        return n;
    }

    /** Relative positions of a south-facing, centred wall at ANCHOR: x across (west is right when facing south). */
    private static BlockPos wallCell(int u, int v) {
        // Facing south, "right" is west
        return ANCHOR.add(-u, v, 0);
    }

    // ------------------------------------------------------------------ registration

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void tomeIsRegisteredAsMaster(TestContext ctx) {
        ctx.assertTrue(ModItems.getTomeFor(SpellId.BUILDING, TomeTier.MASTER) == ModItems.TOME_OF_BUILDING, "tome lookup");
        ctx.assertTrue(Spells.get(SpellId.BUILDING) instanceof BuildingSpell, "spell registered");
        ctx.assertTrue(ModItems.TOME_OF_BUILDING.getXpCost() == 2, "costs 2 XP");
        // Every other tome is still registered
        for (SpellId id : SpellId.values()) ctx.assertTrue(Spells.get(id) != null, "spell missing: " + id);
        ctx.complete();
    }

    // ------------------------------------------------------------------ basic building

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void wallTakesBlocksFromInventory(TestContext ctx) {
        PlayerEntity p = player(ctx, GameMode.SURVIVAL);
        give(p, Items.STONE, 64);
        ctx.assertTrue(build(ctx, p, up(ctx), settings(BuildShape.WALL, 3, 3, 1, false, Items.STONE)), "build started");
        // Nothing is placed instantly: the blocks grow in first
        ctx.assertTrue(countIn(ctx, Blocks.STONE) == 0, "placed before the animation");
        ctx.waitAndRun(SETTLE, () -> {
            for (int u = -1; u <= 1; u++) for (int v = 0; v <= 2; v++) {
                ctx.assertTrue(ctx.getBlockState(wallCell(u, v)).isOf(Blocks.STONE), "missing wall block " + u + "," + v);
            }
            ctx.assertEquals(9, countIn(ctx, Blocks.STONE), "stone in world");
            ctx.assertEquals(55, BuildPlanner.count(p, Items.STONE), "stone left");
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void blocksArePlacedGraduallyAndCountTicksDown(TestContext ctx) {
        PlayerEntity p = player(ctx, GameMode.SURVIVAL);
        give(p, Items.STONE, 64);
        ctx.assertTrue(build(ctx, p, up(ctx), settings(BuildShape.WALL, 5, 5, 1, false, Items.STONE)), "build started");
        int[] seen = new int[1];
        ctx.waitAndRun(BuildingSpell.GROW_TICKS + 2, () -> {
            int placed = countIn(ctx, Blocks.STONE);
            ctx.assertTrue(placed > 0 && placed < 25, "expected a partly built wall, got " + placed);
            // Inventory always matches what's been placed so far
            ctx.assertEquals(64 - placed, BuildPlanner.count(p, Items.STONE), "inventory in step with placement");
            seen[0] = placed;
        });
        ctx.waitAndRun(SETTLE, () -> {
            ctx.assertEquals(25, countIn(ctx, Blocks.STONE), "finished wall");
            ctx.assertEquals(39, BuildPlanner.count(p, Items.STONE), "stone left");
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void notEnoughBlocksBuildsNothing(TestContext ctx) {
        PlayerEntity p = player(ctx, GameMode.SURVIVAL);
        give(p, Items.STONE, 8);
        ctx.assertFalse(build(ctx, p, up(ctx), settings(BuildShape.WALL, 3, 3, 1, false, Items.STONE)), "should refuse 9 with 8");
        ctx.waitAndRun(SETTLE, () -> {
            ctx.assertEquals(0, countIn(ctx, Blocks.STONE), "nothing placed");
            ctx.assertEquals(8, BuildPlanner.count(p, Items.STONE), "nothing taken");
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void neverBuildsOverBlocks(TestContext ctx) {
        PlayerEntity p = player(ctx, GameMode.SURVIVAL);
        give(p, Items.STONE, 64);
        ctx.setBlockState(wallCell(0, 1), Blocks.DIRT);
        ctx.setBlockState(wallCell(1, 2), Blocks.GLASS);
        // With 2 spots taken, 7 blocks are enough
        PlayerEntity tight = player(ctx, GameMode.SURVIVAL);
        give(tight, Items.STONE, 7);
        ctx.assertTrue(build(ctx, tight, up(ctx), settings(BuildShape.WALL, 3, 3, 1, false, Items.STONE)), "7 is enough");
        ctx.waitAndRun(SETTLE, () -> {
            ctx.assertTrue(ctx.getBlockState(wallCell(0, 1)).isOf(Blocks.DIRT), "dirt replaced");
            ctx.assertTrue(ctx.getBlockState(wallCell(1, 2)).isOf(Blocks.GLASS), "glass replaced");
            ctx.assertEquals(7, countIn(ctx, Blocks.STONE), "stone placed");
            ctx.assertEquals(0, BuildPlanner.count(tight, Items.STONE), "all 7 used");
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void grassAndWaterAreBuiltThrough(TestContext ctx) {
        PlayerEntity p = player(ctx, GameMode.SURVIVAL);
        give(p, Items.STONE, 64);
        ctx.setBlockState(wallCell(0, 0), Blocks.SHORT_GRASS);
        ctx.setBlockState(wallCell(1, 0), Blocks.WATER);
        ctx.assertTrue(build(ctx, p, up(ctx), settings(BuildShape.WALL, 3, 1, 1, false, Items.STONE)), "build started");
        ctx.waitAndRun(SETTLE, () -> {
            ctx.assertEquals(3, countIn(ctx, Blocks.STONE), "all three placed");
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void mobsAreNotBuiltInto(TestContext ctx) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) ctx.setBlockState(new BlockPos(x, 0, z), Blocks.BEDROCK);
        PlayerEntity p = player(ctx, GameMode.SURVIVAL);
        give(p, Items.STONE, 64);
        ctx.spawnMob(EntityType.PIG, ANCHOR);
        ctx.assertTrue(build(ctx, p, up(ctx), settings(BuildShape.WALL, 3, 1, 1, false, Items.STONE)), "build started");
        ctx.waitAndRun(SETTLE, () -> {
            ctx.assertTrue(ctx.getBlockState(ANCHOR).isAir(), "built inside the pig");
            ctx.assertEquals(2, countIn(ctx, Blocks.STONE), "the two free spots");
            ctx.assertEquals(62, BuildPlanner.count(p, Items.STONE), "only 2 taken");
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void blockPlacedInTheWayDuringAnimationIsSkipped(TestContext ctx) {
        PlayerEntity p = player(ctx, GameMode.SURVIVAL);
        give(p, Items.STONE, 64);
        ctx.assertTrue(build(ctx, p, up(ctx), settings(BuildShape.WALL, 1, 3, 1, false, Items.STONE)), "build started");
        // Someone puts a block there before the tome gets to it
        ctx.setBlockState(wallCell(0, 2), Blocks.GOLD_BLOCK);
        ctx.waitAndRun(SETTLE, () -> {
            ctx.assertTrue(ctx.getBlockState(wallCell(0, 2)).isOf(Blocks.GOLD_BLOCK), "gold overwritten");
            ctx.assertEquals(2, countIn(ctx, Blocks.STONE), "stone placed");
            ctx.assertEquals(62, BuildPlanner.count(p, Items.STONE), "the skipped block wasn't charged");
            ctx.complete();
        });
    }

    // ------------------------------------------------------------------ inventory rules

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void creativeNeedsNoBlocks(TestContext ctx) {
        PlayerEntity p = player(ctx, GameMode.CREATIVE);
        ctx.assertTrue(build(ctx, p, up(ctx), settings(BuildShape.BOX, 3, 3, 3, false, Items.OAK_PLANKS)), "creative build");
        ctx.waitAndRun(SETTLE, () -> {
            ctx.assertEquals(27, countIn(ctx, Blocks.OAK_PLANKS), "box placed");
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void adventureModeCannotBuild(TestContext ctx) {
        PlayerEntity p = player(ctx, GameMode.ADVENTURE);
        give(p, Items.STONE, 64);
        ctx.assertFalse(build(ctx, p, up(ctx), settings(BuildShape.WALL, 3, 3, 1, false, Items.STONE)), "adventure built");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void tooFarAwayIsRefused(TestContext ctx) {
        PlayerEntity p = player(ctx, GameMode.SURVIVAL);
        give(p, Items.STONE, 64);
        p.setPosition(p.getPos().add(0, 0, -60));
        ctx.assertFalse(build(ctx, p, up(ctx), settings(BuildShape.WALL, 1, 1, 1, false, Items.STONE)), "built from 60 blocks");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void offHandBlockIsUsedWithoutAMix(TestContext ctx) {
        PlayerEntity p = player(ctx, GameMode.SURVIVAL);
        p.getInventory().offHand.set(0, new ItemStack(Items.BRICKS, 10));
        ctx.assertTrue(build(ctx, p, up(ctx), settings(BuildShape.WALL, 2, 2, 1, false)), "off-hand build");
        ctx.waitAndRun(SETTLE, () -> {
            ctx.assertEquals(4, countIn(ctx, Blocks.BRICKS), "bricks placed");
            ctx.assertEquals(6, p.getOffHandStack().getCount(), "taken from the off hand");
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void noBlocksChosenIsRefused(TestContext ctx) {
        PlayerEntity p = player(ctx, GameMode.SURVIVAL);
        give(p, Items.STONE, 64);
        ctx.assertFalse(build(ctx, p, up(ctx), settings(BuildShape.WALL, 2, 2, 1, false)), "built with no palette");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void wornArmourIsNeverUsed(TestContext ctx) {
        PlayerEntity p = player(ctx, GameMode.SURVIVAL);
        p.getInventory().armor.set(3, new ItemStack(Items.CARVED_PUMPKIN));
        ctx.assertFalse(build(ctx, p, up(ctx), settings(BuildShape.WALL, 1, 1, 1, false, Items.CARVED_PUMPKIN)), "used the helmet");
        ctx.assertTrue(p.getInventory().armor.get(3).isOf(Items.CARVED_PUMPKIN), "helmet still worn");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void runningOutMidwayStopsCleanly(TestContext ctx) {
        PlayerEntity p = player(ctx, GameMode.SURVIVAL);
        give(p, Items.STONE, 25);
        ctx.assertTrue(build(ctx, p, up(ctx), settings(BuildShape.WALL, 5, 5, 1, false, Items.STONE)), "build started");
        // The player throws most of their stone away mid-build
        ctx.waitAndRun(BuildingSpell.GROW_TICKS + 1, () -> {
            int left = BuildPlanner.count(p, Items.STONE);
            p.getInventory().clear();
            give(p, Items.STONE, 3);
            int placedSoFar = 25 - left;
            ctx.waitAndRun(SETTLE, () -> {
                ctx.assertEquals(placedSoFar + 3, countIn(ctx, Blocks.STONE), "stops when out of blocks");
                ctx.assertEquals(0, BuildPlanner.count(p, Items.STONE), "used what was there");
                ctx.complete();
            });
        });
    }

    // ------------------------------------------------------------------ mixing

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void mixUsesEveryBlockWithinWhatYouHave(TestContext ctx) {
        PlayerEntity p = player(ctx, GameMode.SURVIVAL);
        give(p, Items.STONE, 5);
        give(p, Items.COBBLESTONE, 5);
        BuildSettings s = settings(BuildShape.WALL, 3, 3, 1, false, Items.STONE, Items.COBBLESTONE);
        s.palette.get(0).weight = 5; // prefers stone, but only has 5
        ctx.assertTrue(build(ctx, p, up(ctx), s), "10 blocks cover 9");
        ctx.waitAndRun(SETTLE, () -> {
            int stone = countIn(ctx, Blocks.STONE), cobble = countIn(ctx, Blocks.COBBLESTONE);
            ctx.assertEquals(9, stone + cobble, "all placed");
            ctx.assertTrue(stone <= 5 && cobble <= 5, "used more than carried: " + stone + "/" + cobble);
            ctx.assertEquals(1, BuildPlanner.count(p, Items.STONE) + BuildPlanner.count(p, Items.COBBLESTONE), "one left over");
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void layeredPutsEachBlockInItsLayer(TestContext ctx) {
        PlayerEntity p = player(ctx, GameMode.CREATIVE);
        // Stone at the bottom, planks at the top (the 3-layer blend is covered statistically by the pure tests)
        BuildSettings s = settings(BuildShape.WALL, 5, 5, 1, false, Items.STONE, Items.OAK_PLANKS);
        s.palette.get(1).layer = BlockMixer.Layer.TOP;
        s.pattern = BlockMixer.Pattern.LAYERED;
        ctx.assertTrue(build(ctx, p, up(ctx), s), "build started");
        ctx.waitAndRun(SETTLE, () -> {
            // Layers blend a little, so look at the bottom two rows and the top two rather than expecting hard lines
            int lowStone = 0, highPlanks = 0;
            for (int u = -2; u <= 2; u++) {
                for (int v : new int[]{0, 1}) if (ctx.getBlockState(wallCell(u, v)).isOf(Blocks.STONE)) lowStone++;
                for (int v : new int[]{3, 4}) if (ctx.getBlockState(wallCell(u, v)).isOf(Blocks.OAK_PLANKS)) highPlanks++;
            }
            ctx.assertTrue(lowStone >= 7, "bottom should be mostly stone: " + lowStone + "/10");
            ctx.assertTrue(highPlanks >= 7, "top should be mostly planks: " + highPlanks + "/10");
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void checkerAlternates(TestContext ctx) {
        PlayerEntity p = player(ctx, GameMode.CREATIVE);
        BuildSettings s = settings(BuildShape.WALL, 3, 3, 1, false, Items.WHITE_WOOL, Items.BLACK_WOOL);
        s.pattern = BlockMixer.Pattern.CHECKER;
        ctx.assertTrue(build(ctx, p, up(ctx), s), "build started");
        ctx.waitAndRun(SETTLE, () -> {
            for (int u = -1; u <= 1; u++) for (int v = 0; v < 2; v++) {
                ctx.assertTrue(ctx.getBlockState(wallCell(u, v)).getBlock() != ctx.getBlockState(wallCell(u, v + 1)).getBlock(),
                        "neighbours match at " + u + "," + v);
            }
            ctx.complete();
        });
    }

    // ------------------------------------------------------------------ shapes and placement

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void everyShapeMapsToDistinctBlocks(TestContext ctx) {
        for (BuildShape shape : BuildShape.values()) {
            for (Direction face : new Direction[]{Direction.UP, Direction.DOWN, Direction.EAST}) {
                for (boolean hollow : new boolean[]{false, true}) {
                    BuildSettings s = settings(shape, 99, 99, 99, hollow, Items.STONE); // clamps to the max
                    Direction forward = face.getAxis().isVertical() ? Direction.NORTH : face;
                    BuildPlanner.Target t = target(ctx, ANCHOR, face, forward);
                    List<BuildPlanner.Cell> cells = BuildPlanner.cells(s, t);
                    int expected = ShapeGeometry.cells(shape, s.effectiveWidth(), s.effectiveHeight(), s.effectiveLength(), hollow).size();
                    Set<BlockPos> unique = new HashSet<>();
                    boolean touchesAnchor = false;
                    int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
                    for (BuildPlanner.Cell c : cells) {
                        unique.add(c.pos());
                        touchesAnchor |= c.pos().getY() == t.anchor().getY();
                        minY = Math.min(minY, c.pos().getY());
                        maxY = Math.max(maxY, c.pos().getY());
                        ctx.assertTrue(c.height() >= 0f && c.height() <= 1f, "layer height out of range");
                    }
                    String id = shape + " " + face + (hollow ? " hollow" : "");
                    ctx.assertEquals(expected, unique.size(), id + " distinct blocks");
                    ctx.assertTrue(touchesAnchor, id + " doesn't start at the aimed block");
                    // Grows up from the ground, hangs down from a ceiling
                    if (face == Direction.DOWN) ctx.assertEquals(t.anchor().getY(), maxY, id + " should hang");
                    else ctx.assertEquals(t.anchor().getY(), minY, id + " should stand");
                    // Sizes: nothing bigger than 7 across
                    ctx.assertTrue(maxY - minY < 7, id + " too tall");
                }
            }
            // Minimum size is always a single block, right where you aim
            List<BuildPlanner.Cell> one = BuildPlanner.cells(settings(shape, 0, 0, 0, false, Items.STONE), up(ctx));
            ctx.assertEquals(1, one.size(), shape + " min size");
            ctx.assertEquals(ctx.getAbsolutePos(ANCHOR), one.get(0).pos(), shape + " min size position");
        }
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void sphereBuildsFully(TestContext ctx) {
        PlayerEntity p = player(ctx, GameMode.CREATIVE);
        ctx.assertTrue(build(ctx, p, up(ctx), settings(BuildShape.SPHERE, 5, 1, 1, false, Items.GLASS)), "sphere");
        ctx.waitAndRun(SETTLE, () -> {
            ctx.assertEquals(ShapeGeometry.cells(BuildShape.SPHERE, 5, 1, 1, false).size(), countIn(ctx, Blocks.GLASS), "sphere blocks");
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void hollowDomeIsEmptyInside(TestContext ctx) {
        PlayerEntity p = player(ctx, GameMode.CREATIVE);
        ctx.assertTrue(build(ctx, p, target(ctx, new BlockPos(4, 1, 4), Direction.UP, Direction.SOUTH),
                settings(BuildShape.DOME, 7, 1, 1, true, Items.GLASS)), "dome");
        ctx.waitAndRun(SETTLE, () -> {
            ctx.assertEquals(ShapeGeometry.cells(BuildShape.DOME, 7, 1, 1, true).size(), countIn(ctx, Blocks.GLASS), "dome blocks");
            ctx.assertTrue(ctx.getBlockState(new BlockPos(4, 1, 4)).isAir(), "centre should be hollow");
            ctx.assertTrue(ctx.getBlockState(new BlockPos(4, 2, 4)).isAir(), "centre should be hollow");
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void stairsClimbAwayFromYou(TestContext ctx) {
        PlayerEntity p = player(ctx, GameMode.CREATIVE);
        ctx.assertTrue(build(ctx, p, up(ctx), settings(BuildShape.STAIRS, 1, 1, 4, true, Items.STONE)), "stairs");
        ctx.waitAndRun(SETTLE, () -> {
            for (int k = 0; k < 4; k++) {
                ctx.assertTrue(ctx.getBlockState(ANCHOR.add(0, k, k)).isOf(Blocks.STONE), "step " + k);
            }
            ctx.assertEquals(4, countIn(ctx, Blocks.STONE), "steps only");
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void hangsDownFromACeiling(TestContext ctx) {
        PlayerEntity p = player(ctx, GameMode.CREATIVE);
        BlockPos anchor = new BlockPos(4, 5, 3);
        ctx.assertTrue(build(ctx, p, target(ctx, anchor, Direction.DOWN, Direction.SOUTH),
                settings(BuildShape.WALL, 1, 3, 1, false, Items.STONE)), "hanging wall");
        ctx.waitAndRun(SETTLE, () -> {
            for (int k = 0; k < 3; k++) ctx.assertTrue(ctx.getBlockState(anchor.down(k)).isOf(Blocks.STONE), "hanging block " + k);
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void floorSticksOutFromASide(TestContext ctx) {
        PlayerEntity p = player(ctx, GameMode.CREATIVE);
        BlockPos anchor = new BlockPos(1, 2, 3);
        ctx.assertTrue(build(ctx, p, target(ctx, anchor, Direction.EAST, Direction.EAST),
                settings(BuildShape.FLOOR, 1, 1, 4, false, Items.STONE)), "bridge");
        ctx.waitAndRun(SETTLE, () -> {
            for (int k = 0; k < 4; k++) ctx.assertTrue(ctx.getBlockState(anchor.east(k)).isOf(Blocks.STONE), "bridge block " + k);
            ctx.complete();
        });
    }

    // ------------------------------------------------------------------ settings safety

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void settingsFromClientAreSanitised(TestContext ctx) {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("shape", "SPHERE");
        nbt.putInt("width", 500);
        nbt.putString("pattern", "NOT_A_PATTERN");
        NbtList list = new NbtList();
        for (String id : new String[]{"minecraft:chest", "minecraft:shulker_box", "minecraft:oak_door", "not an id",
                "minecraft:stone", "minecraft:stone", "minecraft:diamond", "minecraft:dirt", "minecraft:glass",
                "minecraft:bricks", "minecraft:sand"}) {
            NbtCompound e = new NbtCompound();
            e.putString("item", id);
            e.putInt("weight", 99);
            e.putString("layer", "SIDEWAYS");
            list.add(e);
        }
        nbt.put("palette", list);
        BuildSettings s = BuildSettings.fromNbt(nbt);
        ctx.assertEquals(BuildShape.SPHERE, s.shape, "shape");
        ctx.assertEquals(5, s.width, "width clamped");
        ctx.assertEquals(BlockMixer.Pattern.RANDOM, s.pattern, "bad pattern falls back");
        ctx.assertEquals(BuildSettings.MAX_PALETTE, s.palette.size(), "palette capped");
        ctx.assertEquals(Items.STONE, s.palette.get(0).item, "containers, doors and junk dropped");
        ctx.assertEquals(Items.DIRT, s.palette.get(1).item, "duplicates and non-blocks dropped");
        ctx.assertEquals(BuildSettings.MAX_WEIGHT, s.palette.get(0).weight, "weight clamped");
        // Round trip
        BuildSettings again = BuildSettings.fromNbt(s.toNbt());
        ctx.assertEquals(s.toNbt(), again.toNbt(), "round trip");
        ctx.assertFalse(BuildSettings.isBuildable(Items.CHEST), "chest buildable");
        ctx.assertFalse(BuildSettings.isBuildable(Items.RED_BED), "bed buildable");
        ctx.assertTrue(BuildSettings.isBuildable(Items.OAK_LOG), "logs");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void resizeStaysInLimits(TestContext ctx) {
        BuildSettings s = settings(BuildShape.CIRCLE, 1, 1, 1, false, Items.STONE);
        for (int i = 0; i < 20; i++) s.resize(1);
        ctx.assertEquals(7, s.width, "circle max 7");
        s.shape = BuildShape.BOX;
        s.clamp();
        ctx.assertEquals(5, s.width, "box max 5");
        for (int i = 0; i < 20; i++) s.resize(-1);
        ctx.assertEquals(1, s.width, "min 1");
        ctx.assertEquals(1, s.height, "min 1");
        ctx.assertFalse(s.resize(-1), "can't go below 1");
        ctx.complete();
    }
}
