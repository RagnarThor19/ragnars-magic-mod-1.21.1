package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.ragnar.ragnarsmagicmod.crows.Crows;
import net.ragnar.ragnarsmagicmod.crows.CrowsSpell;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;

/** Tome of Crows: where you end up, when you're hidden and safe, and that it's all over in about five seconds. */
public class CrowsGameTests implements FabricGameTest {

    /** A survival player facing +z with the tome on a staff, on a stone floor with a stone wall 6 blocks ahead. */
    private static ServerPlayerEntity caster(TestContext ctx, BlockPos rel) {
        for (int z = 0; z <= 7; z++) {
            for (int x = 0; x <= 4; x++) ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
        }
        for (int x = 0; x <= 4; x++) {
            ctx.setBlockState(new BlockPos(x, 1, rel.getZ() + 6), Blocks.STONE);
            ctx.setBlockState(new BlockPos(x, 2, rel.getZ() + 6), Blocks.STONE);
        }
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(rel));
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f); // yaw 0: looking along +z
        p.setHeadYaw(0f);
        p.getInventory().clear();
        ItemStack staff = new ItemStack(ModItems.NETHERITE_STAFF);
        ((StaffItem) ModItems.NETHERITE_STAFF).insertTome(staff, Crows.TOME_OF_CROWS);
        p.getInventory().setStack(0, staff);
        p.getInventory().selectedSlot = 0;
        p.addExperience(1000);
        return p;
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void tomeIsAnAdvancedTomeWithTheRightNumbers(TestContext ctx) {
        ctx.assertEquals(TomeTier.ADVANCED, Crows.TOME_OF_CROWS.getTier(), "tier");
        ctx.assertEquals(8, Crows.TOME_OF_CROWS.getXpCost(), "xp");
        ctx.assertEquals(240, Crows.TOME_OF_CROWS.getCooldown(), "cooldown (12 seconds)");
        ctx.assertTrue(Crows.RANGE == 30, "30 blocks");
        ctx.assertEquals(40, Crows.CHARGE_TICKS, "two second charge-up");
        ctx.assertTrue(Crows.DURATION >= 90 && Crows.DURATION <= 110, "about five seconds in all");
        ctx.assertTrue(Spells.get(SpellId.CROWS) instanceof CrowsSpell, "spell registered");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void tomeIsInTheToolsCreativeTab(TestContext ctx) {
        net.minecraft.item.ItemGroups.updateDisplayContext(ctx.getWorld().getEnabledFeatures(), true, ctx.getWorld().getRegistryManager());
        var tools = net.minecraft.registry.Registries.ITEM_GROUP.get(net.minecraft.item.ItemGroups.TOOLS);
        ctx.assertTrue(tools.getDisplayStacks().stream().anyMatch(s -> s.isOf(Crows.TOME_OF_CROWS)), "in Tools & Utilities");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 200)
    public void travelsToTheWallHiddenAndSafeOnTheWay(TestContext ctx) {
        BlockPos rel = new BlockPos(2, 1, 1);
        ServerPlayerEntity p = caster(ctx, rel);
        Vec3d start = p.getPos();
        double wallZ = ctx.getAbsolutePos(rel.add(0, 0, 6)).getZ();

        ctx.assertTrue(Spells.get(SpellId.CROWS).cast(p.getServerWorld(), p, p.getMainHandStack()), "cast");
        Crows.Journey j = Crows.of(p);
        ctx.assertTrue(j != null, "on the way");
        ctx.assertTrue(j.to.z < wallZ && j.to.z > wallZ - 1.0, "lands right in front of the wall, not " + j.to.z);
        ctx.assertTrue(Math.abs(j.to.y - start.y) < 0.01, "on the floor, not " + j.to.y);
        ctx.assertFalse(Spells.get(SpellId.CROWS).cast(p.getServerWorld(), p, p.getMainHandStack()), "can't set off twice");

        var sources = p.getServerWorld().getDamageSources();
        var allow = ServerLivingEntityEvents.ALLOW_DAMAGE.invoker();
        ctx.runAtTick(Crows.VANISH - 6, () -> {
            ctx.assertFalse(Crows.isHidden(p), "still there during the charge-up");
            ctx.assertTrue(p.getPos().squaredDistanceTo(start) < 0.01, "and hasn't moved yet");
        });
        ctx.runAtTick(Crows.VANISH + 3, () -> {
            ctx.assertTrue(Crows.isHidden(p), "gone into the crows");
            ctx.assertFalse(allow.allowDamage(p, sources.generic(), 6f), "nothing hurts you among the crows");
            ctx.assertTrue(allow.allowDamage(p, sources.outOfWorld(), 4f), "but the void still does");
        });
        ctx.runAtTick(Crows.LEAVE + 4, () -> {
            ctx.assertTrue(Crows.isHidden(p), "still hidden on the far side");
            ctx.assertTrue(p.getPos().squaredDistanceTo(j.to) < 0.01, "already moved over");
        });
        ctx.runAtTick(Crows.APPEAR + 4, () -> {
            ctx.assertFalse(Crows.isHidden(p), "there again");
            ctx.assertFalse(p.hasNoGravity(), "gravity back");
            ctx.assertTrue(allow.allowDamage(p, sources.generic(), 1f), "and can be hurt again");
        });
        ctx.runAtTick(Crows.DURATION + 4, () -> {
            ctx.assertTrue(Crows.of(p) == null, "the journey is over");
            ctx.assertTrue(p.getPos().squaredDistanceTo(j.to) < 0.25, "standing where the crows set you down");
            p.discard();
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 200)
    public void dyingOnTheWayLeavesNothingBehind(TestContext ctx) {
        ServerPlayerEntity p = caster(ctx, new BlockPos(2, 1, 1));
        Spells.get(SpellId.CROWS).cast(p.getServerWorld(), p, p.getMainHandStack());
        ctx.runAtTick(Crows.LEAVE + 4, () -> p.kill());
        ctx.runAtTick(Crows.LEAVE + 8, () -> {
            ctx.assertTrue(Crows.of(p) == null, "journey dropped");
            ctx.assertFalse(Crows.isHidden(p), "not left hidden");
            ctx.assertFalse(p.hasNoGravity(), "not left floating");
            p.discard();
            ctx.complete();
        });
    }
}
