package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.item.BowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.ragnar.ragnarsmagicmod.aiming.Aiming;

/** Tome of Aiming: a bow fully drawn in 7 ticks (0.35s), a crossbow loaded in 13 (0.65s); nothing else touched. */
public class AimingGameTests implements FabricGameTest {

    private static ServerPlayerEntity archer(TestContext ctx, int x, boolean tome, ItemStack weapon) {
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        p.getInventory().clear();
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(x, 1, 1)));
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
        p.getInventory().setStack(9, new ItemStack(Items.ARROW, 64));
        if (tome) p.getInventory().setStack(20, new ItemStack(Aiming.TOME_OF_AIMING));
        p.setStackInHand(Hand.MAIN_HAND, weapon);
        p.setCurrentHand(Hand.MAIN_HAND);
        return p;
    }

    /** Mock players aren't ticked by the server: hold the draw by hand. */
    private static void hold(int ticks, ServerPlayerEntity... players) {
        for (int i = 0; i < ticks; i++) for (ServerPlayerEntity p : players) p.playerTick();
    }

    private static void done(TestContext ctx, ServerPlayerEntity... players) {
        for (ServerPlayerEntity p : players) p.discard();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "passive_aiming_bow")
    public void bowsAreFullyDrawnInSevenTicks(TestContext ctx) {
        ServerPlayerEntity plain = archer(ctx, 1, false, new ItemStack(Items.BOW));
        ServerPlayerEntity aimer = archer(ctx, 3, true, new ItemStack(Items.BOW));
        ServerPlayerEntity blocker = archer(ctx, 5, true, new ItemStack(Items.SHIELD));
        ServerPlayerEntity plainBlocker = archer(ctx, 7, false, new ItemStack(Items.SHIELD));

        hold(6, plain, aimer, blocker, plainBlocker);
        ctx.assertTrue(BowItem.getPullProgress(aimer.getItemUseTime()) < 1f, "not quite full after 6 ticks: " + aimer.getItemUseTime());
        hold(1, plain, aimer, blocker, plainBlocker);
        ctx.assertEquals(1f, BowItem.getPullProgress(aimer.getItemUseTime()), "fully drawn after 7 ticks (0.35s): " + aimer.getItemUseTime());
        ctx.assertTrue(BowItem.getPullProgress(plain.getItemUseTime()) < 0.6f, "barely a third drawn without it: " + plain.getItemUseTime());
        ctx.assertEquals(plainBlocker.getItemUseTime(), blocker.getItemUseTime(), "a shield is left alone");
        done(ctx, plain, aimer, blocker, plainBlocker);
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "passive_aiming_crossbow")
    public void crossbowsLoadInThirteenTicks(TestContext ctx) {
        ServerPlayerEntity early = archer(ctx, 1, true, new ItemStack(Items.CROSSBOW));
        ServerPlayerEntity aimer = archer(ctx, 3, true, new ItemStack(Items.CROSSBOW));
        ServerPlayerEntity plain = archer(ctx, 5, false, new ItemStack(Items.CROSSBOW));

        hold(12, early, aimer, plain);
        early.stopUsingItem();
        ctx.assertFalse(CrossbowItem.isCharged(early.getMainHandStack()), "not loaded after 12 ticks");
        hold(1, aimer, plain);
        aimer.stopUsingItem();
        plain.stopUsingItem();
        ctx.assertTrue(CrossbowItem.isCharged(aimer.getMainHandStack()), "loaded after 13 ticks (0.65s)");
        ctx.assertFalse(CrossbowItem.isCharged(plain.getMainHandStack()), "nowhere near without it");
        done(ctx, early, aimer, plain);
    }
}
