package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;

/** A staff in each hand: only the main hand casts. A lone staff casts from either hand. */
public class StaffHandGameTests implements FabricGameTest {

    private static ServerPlayerEntity caster(TestContext ctx) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(3, 1, 1)));
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
        p.setExperienceLevel(30);
        return p;
    }

    private static ItemStack loadedStaff() {
        ItemStack staff = new ItemStack(ModItems.NETHERITE_STAFF);
        ((StaffItem) ModItems.NETHERITE_STAFF).insertTome(staff, ModItems.TOME_OF_FIREBALLS);
        return staff;
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void offHandStaffIsIgnoredWhileMainHandHoldsOne(TestContext ctx) {
        ServerPlayerEntity p = caster(ctx);
        p.setStackInHand(Hand.MAIN_HAND, loadedStaff());
        p.setStackInHand(Hand.OFF_HAND, loadedStaff());

        ctx.assertFalse(p.getOffHandStack().use(ctx.getWorld(), p, Hand.OFF_HAND).getResult().isAccepted(), "off-hand staff stays quiet");
        ctx.assertFalse(p.getItemCooldownManager().isCoolingDown(ModItems.TOME_OF_FIREBALLS), "nothing was cast");
        ctx.assertTrue(p.getMainHandStack().use(ctx.getWorld(), p, Hand.MAIN_HAND).getResult().isAccepted(), "main-hand staff casts");
        ctx.assertTrue(p.getItemCooldownManager().isCoolingDown(ModItems.TOME_OF_FIREBALLS), "and goes on cooldown");
        p.discard();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void loneOffHandStaffStillCasts(TestContext ctx) {
        ServerPlayerEntity p = caster(ctx);
        p.setStackInHand(Hand.OFF_HAND, loadedStaff());

        ctx.assertTrue(p.getOffHandStack().use(ctx.getWorld(), p, Hand.OFF_HAND).getResult().isAccepted(), "off-hand staff casts on its own");
        ctx.assertTrue(p.getItemCooldownManager().isCoolingDown(ModItems.TOME_OF_FIREBALLS), "and goes on cooldown");
        p.discard();
        ctx.complete();
    }
}
