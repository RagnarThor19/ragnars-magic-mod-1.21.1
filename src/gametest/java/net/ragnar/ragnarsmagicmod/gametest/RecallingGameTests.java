package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.RecallingSpell;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;

/** Tome of Recalling: a free mark, a 10 XP recall, from anywhere - even the Nether. (And Meteor's cooldown.) */
public class RecallingGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void theNumbers(TestContext ctx) {
        ctx.assertEquals(((TomeItem) ModItems.TOME_RECALLING).getXpCost(), 10, "recalling costs 10");
        ctx.assertEquals(((TomeItem) ModItems.TOME_METEOR).getCooldown(), 2000, "meteor: 100 seconds");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "recalling_dimensions", tickLimit = 100)
    public void recallsFromAnotherDimension(TestContext ctx) {
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        Vec3d mark = Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(2, 1, 2)));
        p.refreshPositionAndAngles(mark.x, mark.y, mark.z, 0f, 0f);
        Spell recalling = Spells.get(SpellId.RECALLING);

        ctx.assertEquals(recalling.xpCost(p, 10), 0, "setting the mark is free");
        ctx.assertTrue(recalling.cast(ctx.getWorld(), p, ItemStack.EMPTY), "mark set");
        ctx.assertTrue(RecallingSpell.hasMark(p), "marked");
        ctx.assertTrue(RecallingSpell.survivesSaving(p), "the mark is saved with the world, dimension and all");
        ctx.assertEquals(recalling.xpCost(p, 10), 10, "the recall costs the XP");

        // Off to the Nether
        ServerWorld nether = ctx.getWorld().getServer().getWorld(World.NETHER);
        ctx.assertTrue(nether != null, "there's a Nether");
        p.teleport(nether, 0.5, 100, 0.5, 0f, 0f);
        ctx.waitAndRun(2, () -> {
            ctx.assertTrue(p.getServerWorld() == nether, "in the Nether");
            ctx.assertTrue(recalling.cast(p.getServerWorld(), p, ItemStack.EMPTY), "recall");
            ctx.waitAndRun(2, () -> {
                ctx.assertTrue(p.getServerWorld() == ctx.getWorld(), "back in the Overworld");
                ctx.assertTrue(p.getPos().distanceTo(mark) < 0.75, "right on the mark: " + p.getPos() + " vs " + mark);
                ctx.assertFalse(RecallingSpell.hasMark(p), "the mark is used up");
                p.discard();
                ctx.complete();
            });
        });
    }
}
