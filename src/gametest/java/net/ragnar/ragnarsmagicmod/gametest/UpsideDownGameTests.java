package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.MovementType;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.upsidedown.UpsideDown;
import net.ragnar.ragnarsmagicmod.upsidedown.UpsideDownSpell;

/** Tome of Upside Down: gravity pulls up, the ceiling is ground, the eyes swap ends, and casting again undoes it. */
public class UpsideDownGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void flipsGravityAndBack(TestContext ctx) {
        ctx.assertEquals(TomeTier.BEGINNER, UpsideDown.TOME_OF_UPSIDE_DOWN.getTier(), "tier");
        ctx.assertEquals(10, UpsideDown.TOME_OF_UPSIDE_DOWN.getXpCost(), "xp");
        ctx.assertEquals(40, UpsideDown.TOME_OF_UPSIDE_DOWN.getCooldown(), "cooldown");
        ctx.assertTrue(Spells.get(SpellId.UPSIDE_DOWN) instanceof UpsideDownSpell, "spell registered");

        // A floor and a ceiling four blocks up
        for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) {
            ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
            ctx.setBlockState(new BlockPos(x, 5, z), Blocks.STONE);
        }
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(2, 1, 2)));
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
        double eyeUp = p.getEyeY() - p.getY();
        double gravity = p.getFinalGravity();

        ctx.assertTrue(Spells.get(SpellId.UPSIDE_DOWN).cast(ctx.getWorld(), p, ItemStack.EMPTY), "cast");
        ctx.assertTrue(UpsideDown.isFlipped(p), "flipped");
        ctx.assertTrue(p.getFinalGravity() == -gravity, "gravity pulls up");
        ctx.assertTrue(Math.abs((p.getEyeY() - p.getY()) - (p.getHeight() - eyeUp)) < 1e-4, "eyes at the other end");

        // Moving up into the ceiling is landing on it
        p.move(MovementType.SELF, new Vec3d(0, 4, 0));
        ctx.assertTrue(p.isOnGround(), "standing on the ceiling");
        ctx.assertTrue(Math.abs(p.getBoundingBox().maxY - ctx.getAbsolutePos(new BlockPos(2, 5, 2)).getY()) < 1e-4, "head against the ceiling");
        // And a jump pushes away from it, downward
        p.jump();
        ctx.assertTrue(p.getVelocity().y < 0, "jumps down, vy " + p.getVelocity().y);

        ctx.assertTrue(Spells.get(SpellId.UPSIDE_DOWN).cast(ctx.getWorld(), p, ItemStack.EMPTY), "cast again");
        ctx.assertTrue(!UpsideDown.isFlipped(p), "right way up again");
        ctx.assertTrue(p.getFinalGravity() == gravity, "gravity back to normal");
        p.discard();
        ctx.complete();
    }
}
