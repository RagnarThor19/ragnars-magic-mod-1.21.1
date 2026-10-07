package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.moon.Moon;
import net.ragnar.ragnarsmagicmod.moon.MoonEntity;
import net.ragnar.ragnarsmagicmod.moon.MoonSpell;

import java.util.List;

/** Tome of the Moon: lifts everything near where it lands, slams it all down, hits hard, and leaves nothing behind. */
public class MoonGameTests implements FabricGameTest {

    /** Keeps the test's chunks ticking whatever its neighbours do (see BallLightningGameTests). */
    private static void forceChunks(TestContext ctx) {
        BlockPos a = ctx.getAbsolutePos(new BlockPos(0, 0, 0)), b = ctx.getAbsolutePos(new BlockPos(7, 0, 7));
        for (int cx = Math.min(a.getX(), b.getX()) >> 4; cx <= Math.max(a.getX(), b.getX()) >> 4; cx++) {
            for (int cz = Math.min(a.getZ(), b.getZ()) >> 4; cz <= Math.max(a.getZ(), b.getZ()) >> 4; cz++) {
                ctx.getWorld().setChunkForced(cx, cz, true);
            }
        }
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void tomeIsAMasterTomeWithTheRightNumbers(TestContext ctx) {
        ctx.assertEquals(TomeTier.MASTER, Moon.TOME_OF_THE_MOON.getTier(), "tier");
        ctx.assertEquals(40, Moon.TOME_OF_THE_MOON.getXpCost(), "xp");
        ctx.assertEquals(400, Moon.TOME_OF_THE_MOON.getCooldown(), "cooldown");
        ctx.assertTrue(Spells.get(SpellId.MOON) instanceof MoonSpell, "spell registered");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 240)
    public void liftsSlamsAndLeavesNothingBehind(TestContext ctx) {
        forceChunks(ctx);
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
        IronGolemEntity left = ctx.spawnEntity(EntityType.IRON_GOLEM, new BlockPos(0, 1, 5));
        IronGolemEntity right = ctx.spawnEntity(EntityType.IRON_GOLEM, new BlockPos(7, 1, 5));
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(3, 1, 0)));
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 40f); // looking down at the floor a couple of blocks out
        double floor = at.y;

        ctx.assertTrue(Spells.get(SpellId.MOON).cast(ctx.getWorld(), p, ItemStack.EMPTY), "cast");
        Box around = new Box(p.getBlockPos()).expand(40);
        List<MoonEntity> moons = ctx.getWorld().getEntitiesByClass(MoonEntity.class, around, m -> true);
        ctx.assertTrue(moons.size() == 1, "a moon is forming");
        MoonEntity moon = moons.get(0);
        ctx.assertTrue(moon.getY() > floor + MoonEntity.RADIUS, "it forms up over the landing spot");
        ctx.assertTrue(Math.abs(moon.ground().y - floor) < 0.01, "and comes down on the floor");

        // The test server doesn't reliably tick fresh entities (see BallLightningGameTests), so run it by hand
        ctx.waitAndRun(2, () -> {
            for (int i = 0; i < MoonEntity.FORM_TICKS + 30 && moon.stage() != MoonEntity.FALLING; i++) moon.tick();
            for (int i = 0; i < 20; i++) moon.tick();
            ctx.assertTrue(moon.stage() == MoonEntity.FALLING, "falling");
            ctx.assertTrue(left.getVelocity().y > 0.05 && right.getVelocity().y > 0.05,
                    "both golems being lifted: " + left.getVelocity() + " / " + right.getVelocity());
            ctx.assertTrue(p.getVelocity().y <= 0.0, "the caster isn't lifted");

            for (int i = 0; i < MoonEntity.FALL_TICKS && moon.stage() == MoonEntity.FALLING; i++) moon.tick();
            ctx.assertTrue(moon.stage() == MoonEntity.AFTERGLOW, "landed");
            float lostLeft = left.getMaxHealth() - left.getHealth(), lostRight = right.getMaxHealth() - right.getHealth();
            ctx.assertTrue(lostLeft >= 18f && lostRight >= 18f, "slammed down hard: " + lostLeft + " / " + lostRight);
            ctx.assertTrue(left.getVelocity().y < -1.0, "lifted things are slammed back down");
            ctx.assertTrue(p.getHealth() == p.getMaxHealth(), "never hurts the caster");

            for (int i = 0; i < MoonEntity.AFTERGLOW_TICKS + 5 && !moon.isRemoved(); i++) moon.tick();
            ctx.assertTrue(moon.isRemoved(), "faded away");
            for (BlockPos pos : BlockPos.iterate(ctx.getAbsolutePos(new BlockPos(-2, 0, -2)), ctx.getAbsolutePos(new BlockPos(9, 30, 9)))) {
                ctx.assertFalse(ctx.getWorld().getBlockState(pos).isOf(Blocks.LIGHT), "left a light block at " + pos);
            }
            ctx.assertTrue(ctx.getBlockState(new BlockPos(3, 0, 3)).isOf(Blocks.STONE), "breaks no blocks");
            p.discard();
            left.discard();
            right.discard();
            ctx.complete();
        });
    }
}
