package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.terraform.Terraform;
import net.ragnar.ragnarsmagicmod.terraform.TerraformMode;
import net.ragnar.ragnarsmagicmod.terraform.TerraformSettings;

/**
 * Tome of Terraforming on real ground: grassy columns of stone, dirt and grass (ground at y 5), 21 blocks square,
 * with whatever hills, pits, builds, trunks, ores and flowers each test needs.
 */
public class TerraformGameTests implements FabricGameTest {
    private static final int SIZE = 20, GROUND = 5, C = 10;

    /** Sets column {@code x, z} to stone, then two of dirt, then grass at {@code h}, with air above. */
    private static void column(TestContext ctx, int x, int z, int h) {
        for (int y = 0; y <= GROUND + 12; y++) {
            Block b = y > h ? Blocks.AIR : y == h ? Blocks.GRASS_BLOCK : y >= h - 2 ? Blocks.DIRT : Blocks.STONE;
            ctx.setBlockState(new BlockPos(x, y, z), b);
        }
    }

    private static void flatGround(TestContext ctx) {
        for (int x = 0; x <= SIZE; x++) for (int z = 0; z <= SIZE; z++) column(ctx, x, z, GROUND);
        BlockPos a = ctx.getAbsolutePos(new BlockPos(0, 0, 0)), b = ctx.getAbsolutePos(new BlockPos(SIZE, 0, SIZE));
        for (int cx = Math.min(a.getX(), b.getX()) >> 4; cx <= Math.max(a.getX(), b.getX()) >> 4; cx++) {
            for (int cz = Math.min(a.getZ(), b.getZ()) >> 4; cz <= Math.max(a.getZ(), b.getZ()) >> 4; cz++) {
                ctx.getWorld().setChunkForced(cx, cz, true);
            }
        }
    }

    private static ServerPlayerEntity player(TestContext ctx, boolean creative) {
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(creative ? GameMode.CREATIVE : GameMode.SURVIVAL);
        p.getInventory().clear();
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(0, GROUND + 1, 0)));
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
        Terraform.setReserve(p, 0);
        return p;
    }

    private static TerraformSettings settings(TerraformMode mode, int radius, boolean soft, int strength) {
        TerraformSettings s = new TerraformSettings();
        s.mode = mode;
        s.radius = radius;
        s.softEdge = soft;
        if (mode.hasStrength()) s.setStrength(strength);
        s.clamp();
        return s;
    }

    /** The height of the ground (highest grass, dirt or stone) in column {@code x, z}. */
    private static int ground(TestContext ctx, int x, int z) {
        for (int y = GROUND + 12; y >= 0; y--) {
            BlockState s = ctx.getBlockState(new BlockPos(x, y, z));
            if (s.isOf(Blocks.GRASS_BLOCK) || s.isOf(Blocks.DIRT) || s.isOf(Blocks.STONE)) return y;
        }
        return -1;
    }

    private static BlockPos at(TestContext ctx, int x, int y, int z) {
        return ctx.getAbsolutePos(new BlockPos(x, y, z));
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void tomeIsAMasterTomeLikeBuilding(TestContext ctx) {
        ctx.assertEquals(TomeTier.MASTER, Terraform.TOME_OF_TERRAFORMING.getTier(), "tier");
        ctx.assertEquals(2, Terraform.TOME_OF_TERRAFORMING.getXpCost(), "xp");
        ctx.assertEquals(10, Terraform.TOME_OF_TERRAFORMING.getCooldown(), "half a second");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "terraform_flatten", tickLimit = 200)
    public void flattenCutsHillsFillsPitsAndLeavesBuildsAlone(TestContext ctx) {
        flatGround(ctx);
        // A hill, a pit, a hill with coal in it, a player-built plank block, and a tree trunk
        for (int x = 8; x <= 9; x++) for (int z = 8; z <= 9; z++) column(ctx, x, z, GROUND + 4);
        column(ctx, 12, 10, GROUND - 2);
        column(ctx, 10, 13, GROUND + 3);
        ctx.setBlockState(new BlockPos(10, GROUND + 1, 13), Blocks.COAL_ORE);
        ctx.setBlockState(new BlockPos(13, GROUND + 1, 13), Blocks.OAK_PLANKS);
        ctx.setBlockState(new BlockPos(7, GROUND + 1, 12), Blocks.OAK_LOG);
        ctx.setBlockState(new BlockPos(7, GROUND + 2, 12), Blocks.OAK_LOG);
        ctx.setBlockState(new BlockPos(11, GROUND + 1, 7), Blocks.POPPY);
        column(ctx, 11, 7, GROUND + 2); // ...the poppy sits on a little rise that will be flattened
        ctx.setBlockState(new BlockPos(11, GROUND + 3, 7), Blocks.POPPY);
        ServerPlayerEntity p = player(ctx, true);

        ctx.assertTrue(Terraform.start(ctx.getWorld(), p, at(ctx, C, GROUND, C), settings(TerraformMode.FLATTEN, 6, false, 0)), "starts");
        ctx.waitAndRun(40, () -> {
            ctx.assertFalse(Terraform.isBusy(p), "done");
            for (int x = C - 4; x <= C + 4; x++) {
                for (int z = C - 4; z <= C + 4; z++) {
                    if ((x == 13 && z == 13) || (x == 7 && z == 12) || (x == 10 && z == 13)) continue;
                    ctx.assertEquals(ground(ctx, x, z), GROUND, "level at " + x + "," + z);
                }
            }
            ctx.assertTrue(ctx.getBlockState(new BlockPos(9, GROUND, 9)).isOf(Blocks.GRASS_BLOCK), "the cut hill grows its grass back");
            ctx.assertTrue(ctx.getBlockState(new BlockPos(12, GROUND, 10)).isOf(Blocks.GRASS_BLOCK), "the filled pit is grassed over");
            ctx.assertTrue(ctx.getBlockState(new BlockPos(12, GROUND - 1, 10)).isOf(Blocks.DIRT), "with soil under it");
            ctx.assertTrue(ctx.getBlockState(new BlockPos(13, GROUND + 1, 13)).isOf(Blocks.OAK_PLANKS), "the build is untouched");
            ctx.assertTrue(ctx.getBlockState(new BlockPos(7, GROUND + 2, 12)).isOf(Blocks.OAK_LOG), "so is the tree");
            ctx.assertTrue(ctx.getBlockState(new BlockPos(10, GROUND + 1, 13)).isOf(Blocks.COAL_ORE), "the coal is left to be mined");
            ctx.assertTrue(ctx.getBlockState(new BlockPos(11, GROUND + 1, 7)).isOf(Blocks.POPPY), "the poppy rode down with its ground");
            p.discard();
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "terraform_undo", tickLimit = 200)
    public void undoPutsTheLandBack(TestContext ctx) {
        flatGround(ctx);
        for (int x = 9; x <= 11; x++) for (int z = 9; z <= 11; z++) column(ctx, x, z, GROUND + 3);
        ServerPlayerEntity p = player(ctx, true);
        ctx.assertTrue(Terraform.start(ctx.getWorld(), p, at(ctx, C, GROUND, C - 4), settings(TerraformMode.FLATTEN, 5, false, 0)), "flattens");
        ctx.waitAndRun(30, () -> {
            ctx.assertEquals(ground(ctx, C, C), GROUND, "the hill is gone");
            ctx.assertTrue(Terraform.undo(p), "undo");
            ctx.waitAndRun(30, () -> {
                ctx.assertEquals(ground(ctx, C, C), GROUND + 3, "the hill is back");
                ctx.assertTrue(ctx.getBlockState(new BlockPos(C, GROUND + 3, C)).isOf(Blocks.GRASS_BLOCK), "grass on top, as it was");
                ctx.assertFalse(Terraform.undo(p), "and only once");
                p.discard();
                ctx.complete();
            });
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "terraform_earth", tickLimit = 300)
    public void survivalMovesEarthItDoesntMakeIt(TestContext ctx) {
        flatGround(ctx);
        ServerPlayerEntity p = player(ctx, false);
        TerraformSettings raise = settings(TerraformMode.RAISE, 3, true, 4);
        ctx.assertFalse(Terraform.start(ctx.getWorld(), p, at(ctx, C, GROUND, C), raise), "no earth, no hill");
        p.getInventory().setStack(5, new ItemStack(Items.DIRT, 64));
        p.getInventory().setStack(6, new ItemStack(Items.COBBLESTONE, 64));
        ctx.assertTrue(Terraform.start(ctx.getWorld(), p, at(ctx, C, GROUND, C), raise), "with dirt and cobble in hand, it rises");
        ctx.waitAndRun(30, () -> {
            ctx.assertEquals(ground(ctx, C, C), GROUND + 4, "a hill four high in the middle");
            int used = 128 - p.getInventory().count(Items.DIRT) - p.getInventory().count(Items.COBBLESTONE);
            ctx.assertTrue(used > 0 && Terraform.reserve(p) == 0, "it took what it needed from the inventory: " + used + ", reserve " + Terraform.reserve(p));
            // Cutting it back down puts the earth into the reserve (aimed at the flat foot of the hill, and kept
            // inside the test's own ground: past its edge the land drops away, and flattening would fill that in too)
            ctx.assertTrue(Terraform.start(ctx.getWorld(), p, at(ctx, C, GROUND, C - 3), settings(TerraformMode.FLATTEN, 7, false, 0)), "flatten it again");
            ctx.waitAndRun(30, () -> {
                ctx.assertEquals(ground(ctx, C, C), GROUND, "flat again");
                ctx.assertEquals(Terraform.reserve(p), used, "every block cut went into the reserve");
                p.discard();
                ctx.complete();
            });
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "terraform_cliff", tickLimit = 200)
    public void cliffTurnsASlopeIntoASheerDrop(TestContext ctx) {
        flatGround(ctx);
        // A gentle slope rising toward +x: ground 5 at x = 4, up to 11 at x = 16
        for (int x = 0; x <= SIZE; x++) for (int z = 0; z <= SIZE; z++) column(ctx, x, z, GROUND + Math.max(0, Math.min(6, (x - 4) / 2)));
        ServerPlayerEntity p = player(ctx, true);
        // Aim at the middle of the slope (ground 8 at x = 10): a 6-block drop, hard-edged
        ctx.assertTrue(Terraform.start(ctx.getWorld(), p, at(ctx, C, ground(ctx, C, C), C), settings(TerraformMode.CLIFF, 6, false, 6)), "starts");
        ctx.waitAndRun(30, () -> {
            int topH = ground(ctx, C, C);
            ctx.assertEquals(topH, GROUND + 3, "the ground at the aim stays");
            ctx.assertEquals(ground(ctx, C + 3, C), GROUND + 4, "uphill is untouched");
            ctx.assertTrue(ground(ctx, C - 1, C) <= topH - 6, "one step downhill it falls away sheer: " + ground(ctx, C - 1, C));
            p.discard();
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "terraform_lift", tickLimit = 200)
    public void whatStandsOnRisingGroundIsCarriedUp(TestContext ctx) {
        flatGround(ctx);
        ServerPlayerEntity p = player(ctx, true);
        PigEntity pig = EntityType.PIG.create(ctx.getWorld());
        Vec3d on = Vec3d.ofBottomCenter(at(ctx, C, GROUND + 1, C));
        pig.refreshPositionAndAngles(on.x, on.y, on.z, 0f, 0f);
        pig.setAiDisabled(true);
        ctx.getWorld().spawnEntity(pig);
        ctx.assertTrue(Terraform.start(ctx.getWorld(), p, at(ctx, C, GROUND, C), settings(TerraformMode.RAISE, 4, false, 5)), "raise");
        ctx.waitAndRun(30, () -> {
            ctx.assertEquals(ground(ctx, C, C), GROUND + 5, "raised");
            ctx.assertTrue(pig.getY() >= on.y + 5 - 0.01, "the pig rode up on top: " + (pig.getY() - on.y));
            ctx.assertFalse(pig.isInsideWall(), "not buried");
            pig.discard();
            p.discard();
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "terraform_smooth", tickLimit = 200)
    public void smoothIronsOutSpikesAndPits(TestContext ctx) {
        flatGround(ctx);
        // A lone spike and a lone pit: smoothing should all but erase them
        column(ctx, 9, 10, GROUND + 6);
        column(ctx, 11, 10, GROUND - 3);
        ServerPlayerEntity p = player(ctx, true);
        ctx.assertTrue(Terraform.start(ctx.getWorld(), p, at(ctx, C, GROUND, C), settings(TerraformMode.SMOOTH, 5, false, 4)), "smooth");
        ctx.waitAndRun(30, () -> {
            ctx.assertTrue(ground(ctx, 9, 10) <= GROUND + 1, "the spike is smoothed away: " + ground(ctx, 9, 10));
            ctx.assertTrue(ground(ctx, 11, 10) >= GROUND - 1, "the pit is smoothed over: " + ground(ctx, 11, 10));
            p.discard();
            ctx.complete();
        });
    }
}
