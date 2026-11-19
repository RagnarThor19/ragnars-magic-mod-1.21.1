package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.ZombieEntity;
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
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.mimicry.Mimicry;
import net.ragnar.ragnarsmagicmod.mimicry.MimicrySpell;

/**
 * Tome of Mimicry: become the block you look at, mobs lose you, and moving, getting hurt or casting again brings you
 * back out, with the cooldown starting only then.
 */
public class MimicryGameTests implements FabricGameTest {

    private record Setup(ServerPlayerEntity player, StaffItem item, ItemStack staff) {}

    /** A caster on a stone floor, looking down at a crafting table two blocks ahead. */
    private static Setup setup(TestContext ctx) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
        ctx.setBlockState(new BlockPos(3, 1, 3), Blocks.CRAFTING_TABLE);
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(3, 1, 1))).add(0.2, 0, -0.1); // a little off-grid
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 30f);
        p.setOnGround(true);
        StaffItem item = (StaffItem) ModItems.DIAMOND_STAFF;
        ItemStack staff = new ItemStack(item);
        item.insertTome(staff, Mimicry.TOME_OF_MIMICRY);
        p.setStackInHand(Hand.MAIN_HAND, staff);
        return new Setup(p, item, staff);
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 40)
    public void becomeTheBlockThenMoveOut(TestContext ctx) {
        ctx.assertEquals(TomeTier.BEGINNER, Mimicry.TOME_OF_MIMICRY.getTier(), "tier");
        ctx.assertEquals(5, Mimicry.TOME_OF_MIMICRY.getXpCost(), "xp");
        ctx.assertEquals(80, Mimicry.TOME_OF_MIMICRY.getCooldown(), "cooldown");
        ctx.assertTrue(Spells.get(SpellId.MIMICRY) instanceof MimicrySpell, "spell registered");

        Setup s = setup(ctx);
        ServerPlayerEntity p = s.player();
        ZombieEntity zombie = ctx.spawnEntity(EntityType.ZOMBIE, new BlockPos(6, 1, 6));
        zombie.setTarget(p);
        ctx.assertTrue(zombie.getTarget() == p, "the zombie's after you");

        s.item().use(ctx.getWorld(), p, Hand.MAIN_HAND);
        ctx.assertTrue(MimicrySpell.isDisguised(p), "disguised");
        ctx.assertTrue(MimicrySpell.disguiseOf(p).isOf(Blocks.CRAFTING_TABLE), "as the crafting table");
        Vec3d snapped = Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(3, 1, 1)));
        ctx.assertTrue(p.getPos().squaredDistanceTo(snapped) < 1e-4, "snapped onto the block grid, at " + p.getPos());
        ctx.assertTrue(zombie.getTarget() == null, "the zombie lost you");
        zombie.setTarget(p);
        ctx.assertTrue(zombie.getTarget() == null, "and can't pick you again");
        ctx.assertTrue(!p.getItemCooldownManager().isCoolingDown(Mimicry.TOME_OF_MIMICRY), "no cooldown while disguised");

        // Take a step: found out
        ctx.waitAndRun(3, () -> {
            ctx.assertTrue(MimicrySpell.isDisguised(p), "still a crafting table while standing still");
            p.setPosition(p.getPos().add(1, 0, 0));
        });
        ctx.waitAndRun(5, () -> {
            ctx.assertTrue(!MimicrySpell.isDisguised(p), "moving gives you away");
            ctx.assertTrue(p.getItemCooldownManager().isCoolingDown(Mimicry.TOME_OF_MIMICRY), "cooldown once you're out");
            p.discard();
            zombie.discard();
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 90)
    public void hurtOrCastAgainBringsYouOut(TestContext ctx) {
        Setup s = setup(ctx);
        ServerPlayerEntity p = s.player();
        MimicrySpell spell = (MimicrySpell) Spells.get(SpellId.MIMICRY);

        s.item().use(ctx.getWorld(), p, Hand.MAIN_HAND);
        ctx.assertTrue(MimicrySpell.isDisguised(p), "disguised");
        ctx.assertEquals(0, spell.xpCost(p, 5), "coming out is free");
        s.item().use(ctx.getWorld(), p, Hand.MAIN_HAND);
        ctx.assertTrue(!MimicrySpell.isDisguised(p), "casting again drops it");

        // Nothing to copy: looking at the sky
        p.getItemCooldownManager().remove(Mimicry.TOME_OF_MIMICRY);
        p.setPitch(-60f);
        s.item().use(ctx.getWorld(), p, Hand.MAIN_HAND);
        ctx.assertTrue(!MimicrySpell.isDisguised(p), "no block, no disguise");

        // Once the player's join protection is over: back in, then a hit knocks you out of it
        ctx.waitAndRun(70, () -> {
            p.getItemCooldownManager().remove(Mimicry.TOME_OF_MIMICRY);
            p.setOnGround(true);
            p.setPitch(30f);
            s.item().use(ctx.getWorld(), p, Hand.MAIN_HAND);
            ctx.assertTrue(MimicrySpell.isDisguised(p), "disguised again");
            p.onTeleportationDone(); // a real client confirms the snap at once; until then players can't be hurt
            p.damage(ctx.getWorld().getDamageSources().generic(), 1f);
            ctx.assertTrue(!MimicrySpell.isDisguised(p), "getting hurt gives you away");
            p.discard();
            ctx.complete();
        });
    }
}
