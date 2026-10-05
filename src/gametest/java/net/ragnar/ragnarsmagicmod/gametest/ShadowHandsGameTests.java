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
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.shadowhands.ShadowHands;
import net.ragnar.ragnarsmagicmod.shadowhands.ShadowHandsSpell;

/** Tome of Unseen Hands: after the creep, everything in the pool is seized, held still and crushed for 25. */
public class ShadowHandsGameTests implements FabricGameTest {

    /** Keeps the test's chunks ticking whatever its neighbours do (see BallLightningGameTests). */
    private static void forceChunks(TestContext ctx) {
        BlockPos a = ctx.getAbsolutePos(new BlockPos(0, 0, 0)), b = ctx.getAbsolutePos(new BlockPos(7, 0, 7));
        for (int cx = Math.min(a.getX(), b.getX()) >> 4; cx <= Math.max(a.getX(), b.getX()) >> 4; cx++) {
            for (int cz = Math.min(a.getZ(), b.getZ()) >> 4; cz <= Math.max(a.getZ(), b.getZ()) >> 4; cz++) {
                ctx.getWorld().setChunkForced(cx, cz, true);
            }
        }
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 200, batchId = "shadow_hands")
    public void seizesHoldsAndCrushes(TestContext ctx) {
        ctx.assertEquals(TomeTier.MASTER, ShadowHands.TOME_OF_SHADOW_HANDS.getTier(), "tier");
        ctx.assertEquals(35, ShadowHands.TOME_OF_SHADOW_HANDS.getXpCost(), "xp");
        ctx.assertEquals(800, ShadowHands.TOME_OF_SHADOW_HANDS.getCooldown(), "cooldown");
        ctx.assertTrue(Spells.get(SpellId.SHADOW_HANDS) instanceof ShadowHandsSpell, "spell registered");

        forceChunks(ctx);
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
        IronGolemEntity golem = ctx.spawnEntity(EntityType.IRON_GOLEM, new BlockPos(5, 1, 5));
        golem.setAiDisabled(true);
        float max = golem.getMaxHealth();

        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(2, 1, 2)));
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 90f); // straight down at their own feet
        ctx.assertTrue(Spells.get(SpellId.SHADOW_HANDS).cast(ctx.getWorld(), p, ItemStack.EMPTY), "cast");

        // Nothing happens while the shadow creeps
        ctx.waitAndRun(ShadowHands.WAVE_TICKS - 5, () -> ctx.assertTrue(golem.getHealth() == max, "untouched during the creep"));
        // Caught: shove it, and it doesn't budge
        ctx.waitAndRun(ShadowHands.WAVE_TICKS + 20, () -> {
            ctx.assertTrue(golem.getHealth() < max, "being crushed, health " + golem.getHealth());
            golem.setVelocity(1.5, 0.5, 0);
            golem.velocityModified = true;
        });
        Vec3d[] heldAt = new Vec3d[1];
        ctx.waitAndRun(ShadowHands.WAVE_TICKS + 21, () -> heldAt[0] = golem.getPos());
        ctx.waitAndRun(ShadowHands.WAVE_TICKS + 30, () ->
                ctx.assertTrue(golem.getPos().distanceTo(heldAt[0]) < 0.05, "held still, moved " + golem.getPos().distanceTo(heldAt[0])));
        ctx.waitAndRun(ShadowHands.WAVE_TICKS + ShadowHands.SQUEEZE_TICKS + 5, () -> {
            ctx.assertTrue(Math.abs(golem.getHealth() - (max - 25f)) < 0.01f, "25 damage in all, health " + golem.getHealth());
            ctx.assertTrue(p.getHealth() == p.getMaxHealth(), "the caster is never caught");
            p.discard();
            golem.discard();
            ctx.complete();
        });
    }
}
