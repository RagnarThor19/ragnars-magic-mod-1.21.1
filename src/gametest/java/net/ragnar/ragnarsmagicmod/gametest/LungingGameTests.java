package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.lunging.Lunging;
import net.ragnar.ragnarsmagicmod.lunging.LungingSpell;

/** Tome of Lunging: when a right-click lunges, what it costs, and the stab the server lands. */
public class LungingGameTests implements FabricGameTest {

    /** A survival player with an iron sword in hand and the tome on a staff in their inventory. */
    private static ServerPlayerEntity lunger(TestContext ctx, BlockPos rel) {
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(rel));
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
        p.getInventory().clear();
        p.getInventory().setStack(0, new ItemStack(Items.IRON_SWORD));
        p.getInventory().selectedSlot = 0;
        ItemStack staff = new ItemStack(ModItems.NETHERITE_STAFF);
        ((StaffItem) ModItems.NETHERITE_STAFF).insertTome(staff, Lunging.TOME_OF_LUNGING);
        p.getInventory().setStack(5, staff);
        p.addExperience(100);
        p.playerTick(); // picks up the sword's attack damage, as holding it does in play
        return p;
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void tomeIsABeginnerPassiveWithTheRightNumbers(TestContext ctx) {
        ctx.assertEquals(TomeTier.BEGINNER, Lunging.TOME_OF_LUNGING.getTier(), "tier");
        ctx.assertEquals(2, Lunging.TOME_OF_LUNGING.getXpCost(), "xp");
        ctx.assertEquals(100, Lunging.TOME_OF_LUNGING.getCooldown(), "cooldown (5 seconds)");
        ctx.assertTrue(Spells.get(SpellId.LUNGING) instanceof LungingSpell, "spell registered");
        ctx.assertTrue(Lunging.LUNGE_DISTANCE == 4 && Lunging.BACK_DISTANCE == 5, "4 forward, 5 back");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void onlyASwordWithTheTomeCarriedLunges(TestContext ctx) {
        ServerPlayerEntity p = lunger(ctx, new BlockPos(3, 1, 3));
        ctx.assertTrue(Lunging.triggers(p, Hand.MAIN_HAND), "sword in hand, tome on a staff: lunges");
        ctx.assertFalse(Lunging.triggers(p, Hand.OFF_HAND), "never from the off hand");

        // A shield in the off hand: holding right-click is for blocking, so only a tap lunges
        ctx.assertFalse(Lunging.tapOnly(p), "empty off hand: any right-click lunges");
        p.setStackInHand(Hand.OFF_HAND, new ItemStack(Items.SHIELD));
        ctx.assertTrue(Lunging.tapOnly(p), "shield: tap to lunge, hold to block");
        ctx.assertTrue(Lunging.triggers(p, Hand.MAIN_HAND), "still a lunge on a tap");
        p.setStackInHand(Hand.OFF_HAND, ItemStack.EMPTY);

        p.getInventory().setStack(0, new ItemStack(Items.IRON_AXE));
        ctx.assertFalse(Lunging.triggers(p, Hand.MAIN_HAND), "not with an axe");
        p.getInventory().setStack(0, new ItemStack(Items.IRON_SWORD));
        p.getInventory().setStack(5, ItemStack.EMPTY);
        ctx.assertFalse(Lunging.triggers(p, Hand.MAIN_HAND), "not without the tome");
        p.discard();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "lunging")
    public void coolsDownThenStabsOnce(TestContext ctx) {
        ServerPlayerEntity p = lunger(ctx, new BlockPos(3, 1, 1));
        IronGolemEntity golem = ctx.spawnEntity(EntityType.IRON_GOLEM, new BlockPos(3, 1, 3));
        golem.setAiDisabled(true);

        ctx.assertTrue(Lunging.canLunge(p), "ready");
        Lunging.start(p);
        // (The XP cost isn't checked: the mock player always counts as creative, and lunging is free in creative)
        ctx.assertTrue(p.getItemCooldownManager().isCoolingDown(Lunging.TOME_OF_LUNGING), "tome cooling down");
        ctx.assertTrue(p.getItemCooldownManager().isCoolingDown(Items.IRON_SWORD), "shown on the sword");
        ctx.assertFalse(Lunging.canLunge(p), "can't lunge again yet");

        Lunging.stab(p, golem.getId());
        float hurt = golem.getMaxHealth() - golem.getHealth();
        ctx.assertTrue(hurt >= 6f, "a full-strength iron sword stab, took " + hurt);
        Lunging.stab(p, golem.getId());
        ctx.assertEquals(hurt, golem.getMaxHealth() - golem.getHealth(), "only one stab per lunge");
        p.discard();
        golem.discard();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "lunging")
    public void noStabWithoutALungeOrOutOfReach(TestContext ctx) {
        ServerPlayerEntity p = lunger(ctx, new BlockPos(0, 1, 0));
        IronGolemEntity far = ctx.spawnEntity(EntityType.IRON_GOLEM, new BlockPos(7, 1, 7));
        far.setAiDisabled(true);
        Lunging.stab(p, far.getId());
        ctx.assertTrue(far.getHealth() == far.getMaxHealth(), "no lunge, no stab");
        Lunging.start(p);
        Lunging.stab(p, far.getId());
        ctx.assertTrue(far.getHealth() == far.getMaxHealth(), "too far away to have reached it");
        p.discard();
        far.discard();
        ctx.complete();
    }
}
