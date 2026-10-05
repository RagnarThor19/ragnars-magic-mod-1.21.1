package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.sight.OreKind;
import net.ragnar.ragnarsmagicmod.sight.Sight;
import net.ragnar.ragnarsmagicmod.sight.SightSpell;

import java.util.List;

/** Tome of Sight: finds the ores within ten blocks, tells them apart, and leaves the rest alone. */
public class SightGameTests implements FabricGameTest {

    private static OreKind kindAt(List<Sight.Found> found, BlockPos pos) {
        return found.stream().filter(f -> f.pos().equals(pos)).map(Sight.Found::kind).findFirst().orElse(null);
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void findsOresWithinTenBlocks(TestContext ctx) {
        ctx.assertEquals(TomeTier.ADVANCED, Sight.TOME_OF_SIGHT.getTier(), "tier");
        ctx.assertEquals(10, Sight.TOME_OF_SIGHT.getXpCost(), "xp");
        ctx.assertEquals(360, Sight.TOME_OF_SIGHT.getCooldown(), "cooldown");
        ctx.assertTrue(Spells.get(SpellId.SIGHT) instanceof SightSpell, "spell registered");

        BlockPos diamond = ctx.getAbsolutePos(new BlockPos(1, 1, 1));
        BlockPos iron = ctx.getAbsolutePos(new BlockPos(2, 1, 1));
        BlockPos debris = ctx.getAbsolutePos(new BlockPos(3, 1, 1));
        BlockPos stone = ctx.getAbsolutePos(new BlockPos(4, 1, 1));
        ctx.getWorld().setBlockState(diamond, Blocks.DEEPSLATE_DIAMOND_ORE.getDefaultState());
        ctx.getWorld().setBlockState(iron, Blocks.IRON_ORE.getDefaultState());
        ctx.getWorld().setBlockState(debris, Blocks.ANCIENT_DEBRIS.getDefaultState());
        ctx.getWorld().setBlockState(stone, Blocks.STONE.getDefaultState());

        // Measured from the centre of the diamond ore: 9.5 blocks away it's found, 10.5 it isn't
        Vec3d c = Vec3d.ofCenter(diamond);
        List<Sight.Found> near = SightSpell.scan(ctx.getWorld(), c.add(-9.5, 0, 0));
        ctx.assertEquals(OreKind.DIAMOND, kindAt(near, diamond), "diamond found");
        List<Sight.Found> far = SightSpell.scan(ctx.getWorld(), c.add(-10.5, 0, 0));
        ctx.assertTrue(kindAt(far, diamond) == null, "diamond out of reach");

        List<Sight.Found> here = SightSpell.scan(ctx.getWorld(), c);
        ctx.assertEquals(OreKind.DIAMOND, kindAt(here, diamond), "diamond");
        ctx.assertEquals(OreKind.IRON, kindAt(here, iron), "iron");
        ctx.assertEquals(OreKind.DEBRIS, kindAt(here, debris), "ancient debris");
        ctx.assertTrue(kindAt(here, stone) == null, "stone is no ore");
        ctx.assertTrue(here.get(0).pos().equals(diamond), "nearest first");

        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        ctx.assertTrue(Spells.get(SpellId.SIGHT).cast(ctx.getWorld(), p, ItemStack.EMPTY), "cast");
        p.discard();
        ctx.complete();
    }
}
