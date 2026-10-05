package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.ragnar.ragnarsmagicmod.beaming.Beaming;
import net.ragnar.ragnarsmagicmod.beaming.BeamingSpell;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;

/**
 * Tome of Beaming: held like a bow, 16 damage a second, the charge spendable in bursts, XP only for a fresh charge and
 * the cooldown only once it's all spent.
 */
public class BeamingGameTests implements FabricGameTest {

    /** One held tick, the way the server would run it. */
    private static void hold(StaffItem item, TestContext ctx, ServerPlayerEntity p, ItemStack staff, int ticks) {
        for (int i = 0; i < ticks && p.isUsingItem(); i++) item.usageTick(ctx.getWorld(), p, staff, 72000 - i);
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void burstsThenOverheats(TestContext ctx) {
        ctx.assertEquals(TomeTier.ADVANCED, Beaming.TOME_OF_BEAMING.getTier(), "tier");
        ctx.assertEquals(15, Beaming.TOME_OF_BEAMING.getXpCost(), "xp");
        ctx.assertEquals(240, Beaming.TOME_OF_BEAMING.getCooldown(), "cooldown");
        ctx.assertTrue(Spells.get(SpellId.BEAMING) instanceof BeamingSpell, "spell registered");

        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
        IronGolemEntity golem = ctx.spawnEntity(EntityType.IRON_GOLEM, new BlockPos(2, 1, 6));
        golem.setAiDisabled(true);
        float max = golem.getMaxHealth();

        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(2, 1, 1)));
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 12f); // facing +z, down into the golem's chest
        StaffItem item = (StaffItem) ModItems.DIAMOND_STAFF;
        ItemStack staff = new ItemStack(item);
        item.insertTome(staff, Beaming.TOME_OF_BEAMING);
        p.setStackInHand(Hand.MAIN_HAND, staff);

        // A fresh charge costs XP and starts the beam held like a bow
        BeamingSpell spell = (BeamingSpell) Spells.get(SpellId.BEAMING);
        ctx.assertEquals(15, spell.xpCost(p, 15), "a fresh charge costs the tome's XP");
        item.use(ctx.getWorld(), p, Hand.MAIN_HAND);
        ctx.assertTrue(p.isUsingItem(), "held");

        // One second on the golem: 16 damage
        hold(item, ctx, p, staff, 20);
        ctx.assertTrue(Math.abs(golem.getHealth() - (max - 16f)) < 0.01f, "16 in a second, health " + golem.getHealth());
        ctx.assertEquals(Beaming.FUEL_TICKS - 20, BeamingSpell.fuel(p), "a second of the charge spent");

        // Let go: no cooldown yet
        p.stopUsingItem();
        ctx.assertTrue(!p.getItemCooldownManager().isCoolingDown(Beaming.TOME_OF_BEAMING), "no cooldown mid-charge");

        // Pick it back up: free this time, and the rest of the charge runs out into an overheat
        ctx.assertEquals(0, spell.xpCost(p, 15), "carrying on is free");
        item.use(ctx.getWorld(), p, Hand.MAIN_HAND);
        ctx.assertTrue(p.isUsingItem(), "held again");
        hold(item, ctx, p, staff, 200);
        ctx.assertTrue(!p.isUsingItem(), "stops when the charge is spent");
        ctx.assertTrue(p.getItemCooldownManager().isCoolingDown(Beaming.TOME_OF_BEAMING), "overheated");
        ctx.assertEquals(Beaming.FUEL_TICKS, BeamingSpell.fuel(p), "fresh charge waiting after the cooldown");
        ctx.assertTrue(Math.abs(golem.getHealth() - (max - 56f)) < 0.01f, "3.5 seconds in all is 56, health " + golem.getHealth());
        p.discard();
        golem.discard();
        ctx.complete();
    }
}
