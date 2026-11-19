package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.riptide.Riptide;
import net.ragnar.ragnarsmagicmod.riptide.RiptideSpell;

/** Tome of Riptide: a riptide launch along your aim, with the fall forgiven. */
public class RiptideGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 20)
    public void launchesInARiptideAndForgivesTheFall(TestContext ctx) {
        ctx.assertEquals(TomeTier.ADVANCED, Riptide.TOME_OF_RIPTIDE.getTier(), "tier");
        ctx.assertEquals(5, Riptide.TOME_OF_RIPTIDE.getXpCost(), "xp");
        ctx.assertEquals(80, Riptide.TOME_OF_RIPTIDE.getCooldown(), "cooldown");
        ctx.assertTrue(Spells.get(SpellId.RIPTIDE) instanceof RiptideSpell, "spell registered");

        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(1, 1, 1)));
        p.refreshPositionAndAngles(at.x, at.y + 3, at.z, 0f, -20f);
        p.setVelocity(Vec3d.ZERO);
        Vec3d look = p.getRotationVec(1f);
        double safeBefore = p.getAttributeValue(EntityAttributes.GENERIC_SAFE_FALL_DISTANCE);

        ctx.assertTrue(Spells.get(SpellId.RIPTIDE).cast(ctx.getWorld(), p, ItemStack.EMPTY), "cast");
        ctx.assertTrue(p.isUsingRiptide(), "spinning like a riptide");
        Vec3d v = p.getVelocity();
        ctx.assertTrue(v.length() > 2.9 && v.normalize().dotProduct(look) > 0.99, "thrown along the aim at riptide III speed, " + v);
        ctx.assertTrue(p.getAttributeValue(EntityAttributes.GENERIC_SAFE_FALL_DISTANCE) > safeBefore + 100, "the fall won't hurt");
        p.discard();
        ctx.complete();
    }
}
