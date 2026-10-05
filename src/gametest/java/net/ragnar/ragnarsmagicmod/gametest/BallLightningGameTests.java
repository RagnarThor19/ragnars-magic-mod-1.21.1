package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.ragnar.ragnarsmagicmod.balllightning.BallLightning;
import net.ragnar.ragnarsmagicmod.balllightning.BallLightningEntity;
import net.ragnar.ragnarsmagicmod.balllightning.BallLightningSpell;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;

/** Tome of Ball Lightning: tears through what it hits, zaps what it passes, and goes off at the wall. */
public class BallLightningGameTests implements FabricGameTest {

    /**
     * Keeps the test's chunks ticking whatever its neighbours do. Never un-forced: forcing is on/off per chunk, not
     * counted, so un-forcing here could stop a neighbouring test that shares a chunk.
     */
    private static void forceChunks(TestContext ctx, boolean force) {
        BlockPos a = ctx.getAbsolutePos(new BlockPos(0, 0, 0)), b = ctx.getAbsolutePos(new BlockPos(7, 0, 7));
        for (int cx = Math.min(a.getX(), b.getX()) >> 4; cx <= Math.max(a.getX(), b.getX()) >> 4; cx++) {
            for (int cz = Math.min(a.getZ(), b.getZ()) >> 4; cz <= Math.max(a.getZ(), b.getZ()) >> 4; cz++) {
                ctx.getWorld().setChunkForced(cx, cz, force);
            }
        }
    }

    private static IronGolemEntity golem(TestContext ctx, BlockPos rel) {
        IronGolemEntity g = ctx.spawnEntity(EntityType.IRON_GOLEM, rel);
        g.setAiDisabled(true);
        return g;
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 60)
    public void tearsThroughZapsAndGoesOffAtTheWall(TestContext ctx) {
        ctx.assertEquals(TomeTier.MASTER, BallLightning.TOME_OF_BALL_LIGHTNING.getTier(), "tier");
        ctx.assertEquals(20, BallLightning.TOME_OF_BALL_LIGHTNING.getXpCost(), "xp");
        ctx.assertEquals(360, BallLightning.TOME_OF_BALL_LIGHTNING.getCooldown(), "cooldown");
        ctx.assertTrue(Spells.get(SpellId.BALL_LIGHTNING) instanceof BallLightningSpell, "spell registered");

        forceChunks(ctx, true);
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
        for (int x = 0; x < 8; x++) for (int y = 1; y < 5; y++) ctx.setBlockState(new BlockPos(x, y, 7), Blocks.STONE);
        IronGolemEntity inTheWay = golem(ctx, new BlockPos(1, 1, 3));
        IronGolemEntity offToTheSide = golem(ctx, new BlockPos(5, 1, 3)); // ~4 blocks from its path
        CreeperEntity creeper = ctx.spawnEntity(EntityType.CREEPER, new BlockPos(4, 1, 5));
        creeper.setAiDisabled(true);

        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(1, 1, 0)));
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f); // looking straight at the wall, through the first golem

        ctx.assertTrue(Spells.get(SpellId.BALL_LIGHTNING).cast(ctx.getWorld(), p, ItemStack.EMPTY), "cast");
        Box around = new Box(p.getBlockPos()).expand(32);
        ctx.assertTrue(ctx.getWorld().getEntitiesByClass(BallLightningEntity.class, around, b -> true).size() == 1, "ball out");

        ctx.waitAndRun(20, () -> {
            // The test server sometimes stops ticking a fast-moving entity part way, so finish its flight by hand
            for (BallLightningEntity ball : ctx.getWorld().getEntitiesByClass(BallLightningEntity.class, around, x -> true)) {
                for (int i = 0; i < 60 && !ball.isRemoved(); i++) ball.tick();
            }
            float max = inTheWay.getMaxHealth();
            ctx.assertTrue(inTheWay.getHealth() <= max - BallLightningEntity.HIT_DAMAGE,
                    "hit head on for at least 24, health " + inTheWay.getHealth());
            ctx.assertTrue(offToTheSide.getHealth() < max && offToTheSide.getHealth() > max - BallLightningEntity.HIT_DAMAGE,
                    "only zapped and caught in the blast, health " + offToTheSide.getHealth());
            ctx.assertTrue(creeper.shouldRenderOverlay() || !creeper.isAlive(), "creeper got charged");
            ctx.assertTrue(ctx.getWorld().getEntitiesByClass(BallLightningEntity.class, around, b -> true).isEmpty(), "went off");
            ctx.assertTrue(ctx.getBlockState(new BlockPos(1, 2, 7)).isOf(Blocks.STONE), "breaks no blocks");
            ctx.assertTrue(p.getHealth() == p.getMaxHealth(), "never hurts the caster");
            p.discard();
            inTheWay.discard();
            offToTheSide.discard();
            creeper.discard();
            ctx.complete();
        });
    }
}
