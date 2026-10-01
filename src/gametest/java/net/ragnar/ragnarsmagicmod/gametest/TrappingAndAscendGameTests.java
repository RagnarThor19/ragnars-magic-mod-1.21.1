package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.spell.AscendSpell;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.item.spell.TrappingSpell;

/** In-world tests for the Tomes of Trapping and Ascend. */
public class TrappingAndAscendGameTests implements FabricGameTest {
    private static final BlockPos STAND = new BlockPos(3, 1, 3);
    private static final int ARMED = 35; // past the arming and the disguise settling in

    private static void floor(TestContext ctx) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
    }

    private static PlayerEntity player(TestContext ctx) {
        floor(ctx);
        PlayerEntity p = ctx.createMockPlayer(GameMode.SURVIVAL);
        GameMode.SURVIVAL.setAbilities(p.getAbilities());
        p.setPosition(Vec3d.ofBottomCenter(ctx.getAbsolutePos(STAND)));
        p.setOnGround(true);
        return p;
    }

    private static boolean cast(TestContext ctx, PlayerEntity p, SpellId id) {
        return Spells.get(id).cast(ctx.getWorld(), p, ItemStack.EMPTY);
    }

    private static int displaysNear(TestContext ctx, BlockPos rel) {
        Box box = new Box(ctx.getAbsolutePos(rel)).expand(4);
        return ctx.getWorld().getEntitiesByClass(DisplayEntity.class, box, e -> true).size();
    }

    // ------------------------------------------------------------------ registration

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void tomesHaveTheRightTiersAndCosts(TestContext ctx) {
        ctx.assertEquals(TomeTier.ADVANCED, ModItems.TOME_OF_TRAPPING.getTier(), "trapping tier");
        ctx.assertEquals(15, ModItems.TOME_OF_TRAPPING.getXpCost(), "trapping xp");
        ctx.assertEquals(300, ModItems.TOME_OF_TRAPPING.getCooldown(), "trapping cooldown");
        ctx.assertEquals(TomeTier.BEGINNER, ModItems.TOME_OF_ASCEND.getTier(), "ascend tier");
        ctx.assertEquals(25, ModItems.TOME_OF_ASCEND.getXpCost(), "ascend xp");
        ctx.assertEquals(2400, ModItems.TOME_OF_ASCEND.getCooldown(), "ascend cooldown");
        ctx.assertTrue(Spells.get(SpellId.TRAPPING) instanceof TrappingSpell, "trapping registered");
        ctx.assertTrue(Spells.get(SpellId.ASCEND) instanceof AscendSpell, "ascend registered");
        ctx.assertTrue(ModItems.getTomeFor(SpellId.TRAPPING, TomeTier.ADVANCED) == ModItems.TOME_OF_TRAPPING, "trapping lookup");
        ctx.assertTrue(ModItems.getTomeFor(SpellId.ASCEND, TomeTier.BEGINNER) == ModItems.TOME_OF_ASCEND, "ascend lookup");
        ctx.complete();
    }

    // ------------------------------------------------------------------ trapping

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 200)
    public void trapBitesWhateverStepsOnIt(TestContext ctx) {
        PlayerEntity p = player(ctx);
        Spell s = Spells.get(SpellId.TRAPPING);
        ctx.assertEquals(0, s.cooldownAfterCast(p, 300), "no cooldown on setting it");
        ctx.assertTrue(cast(ctx, p, SpellId.TRAPPING), "set the trap");
        ctx.assertFalse(cast(ctx, p, SpellId.TRAPPING), "only one trap at a time");

        // The owner walks off; the trap disguises itself
        p.setPosition(Vec3d.ofBottomCenter(ctx.getAbsolutePos(STAND.add(-3, 0, -3))));
        ctx.waitAndRun(ARMED, () -> {
            ctx.assertTrue(displaysNear(ctx, STAND) == 1, "just the disguise left, found " + displaysNear(ctx, STAND));

            IronGolemEntity golem = ctx.spawnEntity(EntityType.IRON_GOLEM, STAND);
            golem.setAiDisabled(true);
            float before = golem.getHealth();
            ctx.waitAndRun(15, () -> {
                ctx.assertTrue(Math.abs(before - golem.getHealth() - 25f) < 0.01f,
                        "bitten for 25, health went " + before + " -> " + golem.getHealth());
                ctx.assertTrue(displaysNear(ctx, STAND) > 20, "the jaw is out");
                ctx.assertTrue(cast(ctx, p, SpellId.TRAPPING), "free to set another once sprung");
                p.setSneaking(true);
                ctx.assertTrue(cast(ctx, p, SpellId.TRAPPING), "discard it");
                ctx.waitAndRun(60, () -> {
                    ctx.assertEquals(0, displaysNear(ctx, STAND), "the jaw and the traps are cleaned up");
                    golem.discard();
                    ctx.complete();
                });
            });
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 120)
    public void ownerCanWalkOverTheirOwnTrap(TestContext ctx) {
        PlayerEntity p = player(ctx);
        ctx.assertTrue(cast(ctx, p, SpellId.TRAPPING), "set the trap");
        ctx.waitAndRun(ARMED, () -> {
            ctx.assertTrue(displaysNear(ctx, STAND) == 1, "still just the disguise");
            p.setSneaking(true);
            Spell s = Spells.get(SpellId.TRAPPING);
            ctx.assertEquals(0, s.xpCost(p, 15), "discarding is free");
            ctx.assertTrue(s.ignoresCooldown(p), "discarding works during a cooldown");
            ctx.assertTrue(cast(ctx, p, SpellId.TRAPPING), "discard");
            ctx.assertEquals(0, displaysNear(ctx, STAND), "gone");
            ctx.assertFalse(cast(ctx, p, SpellId.TRAPPING), "nothing left to discard");
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void trapNeedsSolidGround(TestContext ctx) {
        PlayerEntity p = player(ctx);
        p.setOnGround(false);
        ctx.assertFalse(cast(ctx, p, SpellId.TRAPPING), "not mid-air");
        ctx.complete();
    }

    // ------------------------------------------------------------------ ascend

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 80)
    public void ascendCarriesYouUpThroughTheCeiling(TestContext ctx) {
        floor(ctx);
        // A thick roof four blocks up
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) for (int y = 4; y <= 6; y++) {
            ctx.setBlockState(new BlockPos(x, y, z), Blocks.STONE);
        }
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        Vec3d start = Vec3d.ofBottomCenter(ctx.getAbsolutePos(STAND));
        p.requestTeleport(start.x, start.y, start.z);
        double roofTop = ctx.getAbsolutePos(new BlockPos(0, 7, 0)).getY();

        ctx.assertTrue(cast(ctx, p, SpellId.ASCEND), "ascend");
        ctx.assertFalse(cast(ctx, p, SpellId.ASCEND), "not twice at once");
        ctx.waitAndRun(25, () -> {
            ctx.assertTrue(p.getY() >= roofTop - 0.01, "on top of the roof (" + roofTop + "), at " + p.getY());
            ctx.assertFalse(p.hasNoGravity(), "gravity back");
            p.discard();
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, skyAccess = true) // no barrier roof over this one
    public void ascendNeedsSomethingOverhead(TestContext ctx) {
        floor(ctx);
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        Vec3d start = Vec3d.ofBottomCenter(ctx.getAbsolutePos(STAND));
        p.requestTeleport(start.x, start.y, start.z);
        ctx.assertFalse(cast(ctx, p, SpellId.ASCEND), "open sky");
        p.discard();
        ctx.complete();
    }
}
