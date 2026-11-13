package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.ragnar.ragnarsmagicmod.longarms.LongArms;

/** Tome of Long Arms: blocks reachable out to 7 and creatures out to 4, only while it's carried. */
public class LongArmsGameTests implements FabricGameTest {

    private static PigEntity pig(TestContext ctx, ServerPlayerEntity p, double distance) {
        PigEntity pig = EntityType.PIG.create(ctx.getWorld());
        pig.refreshPositionAndAngles(p.getX() + distance, p.getY(), p.getZ(), 0f, 0f);
        pig.setAiDisabled(true);
        ctx.getWorld().spawnEntity(pig);
        return pig;
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "passive_long_arms")
    public void reachesFurtherForBlocksAndCreatures(TestContext ctx) {
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        p.getInventory().clear();
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(1, 1, 1)));
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
        double blocks = p.getBlockInteractionRange(), creatures = p.getEntityInteractionRange();
        ctx.assertTrue(Math.abs(blocks - 4.5) < 1e-6 && Math.abs(creatures - 3.0) < 1e-6, "normal survival reach: " + blocks + " / " + creatures);

        // A block 6.5 blocks off, a pig whose near side is 3.75 off and one whose near side is 4.55 off
        BlockPos far = p.getBlockPos().up().south(6);
        PigEntity pig = pig(ctx, p, 4.2), farPig = pig(ctx, p, 5.0);
        ctx.assertFalse(p.canInteractWithBlockAt(far, 0), "the block is out of reach without the tome");
        ctx.assertFalse(p.canInteractWithEntity(pig, 0), "and so is the pig");

        p.getInventory().setStack(20, new ItemStack(LongArms.TOME_OF_LONG_ARMS));
        LongArms.update(p);
        ctx.assertTrue(Math.abs(p.getBlockInteractionRange() - 7.0) < 1e-6, "blocks out to 7: " + p.getBlockInteractionRange());
        ctx.assertTrue(Math.abs(p.getEntityInteractionRange() - 4.0) < 1e-6, "creatures out to 4: " + p.getEntityInteractionRange());
        ctx.assertTrue(p.canInteractWithBlockAt(far, 0), "the block is in reach with the tome");
        ctx.assertTrue(p.canInteractWithEntity(pig, 0), "so is the nearer pig");
        ctx.assertFalse(p.canInteractWithEntity(farPig, 0), "but not the further one");

        p.getInventory().setStack(20, ItemStack.EMPTY);
        LongArms.update(p);
        ctx.assertTrue(Math.abs(p.getBlockInteractionRange() - 4.5) < 1e-6, "back to normal without it");
        ctx.assertFalse(p.canInteractWithBlockAt(far, 0), "the block's out of reach again");
        ctx.assertFalse(p.canInteractWithEntity(pig, 0), "and so is the pig");
        pig.discard();
        farPig.discard();
        p.discard();
        ctx.complete();
    }
}
