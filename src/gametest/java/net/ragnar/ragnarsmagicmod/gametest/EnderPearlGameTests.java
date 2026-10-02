package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.spell.EnderPearlSpell;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;

import java.util.List;

/** Tome of Ender Pearls: throws a real pearl that takes you where it lands. */
public class EnderPearlGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 60)
    public void throwsAPearlThatTakesYouThere(TestContext ctx) {
        ctx.assertEquals(TomeTier.BEGINNER, ModItems.TOME_OF_ENDER_PEARLS.getTier(), "tier");
        ctx.assertEquals(5, ModItems.TOME_OF_ENDER_PEARLS.getXpCost(), "xp");
        ctx.assertEquals(600, ModItems.TOME_OF_ENDER_PEARLS.getCooldown(), "cooldown");
        ctx.assertTrue(Spells.get(SpellId.ENDER_PEARLS) instanceof EnderPearlSpell, "spell registered");

        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        Vec3d start = Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(3, 1, 1)));
        p.refreshPositionAndAngles(start.x, start.y, start.z, 0f, 50f); // facing into the area, aimed at the floor

        ctx.assertTrue(Spells.get(SpellId.ENDER_PEARLS).cast(ctx.getWorld(), p, ItemStack.EMPTY), "cast");
        List<EnderPearlEntity> pearls = ctx.getWorld().getEntitiesByClass(EnderPearlEntity.class, new Box(p.getBlockPos()).expand(4), e -> true);
        ctx.assertTrue(pearls.size() == 1 && pearls.get(0).getOwner() == p, "a pearl of ours is in the air");

        ctx.waitAndRun(30, () -> {
            double moved = p.getPos().distanceTo(start);
            ctx.assertTrue(moved > 1.0, "landed with it, moved " + moved);
            p.discard();
            ctx.complete();
        });
    }
}
