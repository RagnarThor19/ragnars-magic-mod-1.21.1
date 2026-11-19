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
import net.ragnar.ragnarsmagicmod.water.Water;
import net.ragnar.ragnarsmagicmod.water.WaterOrbEntity;
import net.ragnar.ragnarsmagicmod.water.WaterSpell;

import java.util.List;

/** Tome of Water: washes through mobs, pops at the wall or after ten seconds, and never strays a block off its line. */
public class WaterGameTests implements FabricGameTest {

    private static IronGolemEntity golem(TestContext ctx, BlockPos rel) {
        IronGolemEntity g = ctx.spawnEntity(EntityType.IRON_GOLEM, rel);
        g.setAiDisabled(true);
        return g;
    }

    private static ServerPlayerEntity caster(TestContext ctx, BlockPos rel, float yaw, float pitch) {
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(rel));
        p.refreshPositionAndAngles(at.x, at.y, at.z, yaw, pitch);
        return p;
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 40)
    public void washesThroughAndPopsAtTheWall(TestContext ctx) {
        ctx.assertEquals(TomeTier.ADVANCED, Water.TOME_OF_WATER.getTier(), "tier");
        ctx.assertEquals(5, Water.TOME_OF_WATER.getXpCost(), "xp");
        ctx.assertEquals(40, Water.TOME_OF_WATER.getCooldown(), "cooldown");
        ctx.assertTrue(Spells.get(SpellId.WATER) instanceof WaterSpell, "spell registered");

        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
        for (int x = 0; x < 8; x++) for (int y = 1; y < 5; y++) ctx.setBlockState(new BlockPos(x, y, 7), Blocks.STONE);
        IronGolemEntity inTheWay = golem(ctx, new BlockPos(1, 1, 3));
        IronGolemEntity byTheWall = golem(ctx, new BlockPos(4, 1, 6)); // off its path, but in reach of the pop
        IronGolemEntity farAway = golem(ctx, new BlockPos(7, 1, 1));
        ServerPlayerEntity p = caster(ctx, new BlockPos(1, 1, 0), 0f, 0f); // looking straight at the wall

        ctx.assertTrue(Spells.get(SpellId.WATER).cast(ctx.getWorld(), p, ItemStack.EMPTY), "cast");
        Box around = new Box(p.getBlockPos()).expand(32);
        List<WaterOrbEntity> orbs = ctx.getWorld().getEntitiesByClass(WaterOrbEntity.class, around, o -> true);
        ctx.assertTrue(orbs.size() == 1, "ball out");
        WaterOrbEntity orb = orbs.get(0);
        int ticks = 0;
        while (!orb.isRemoved() && ticks < 100) {
            orb.tick();
            ticks++;
        }
        ctx.assertTrue(orb.isRemoved(), "popped");
        ctx.assertTrue(ticks < 25, "popped at the wall, not later (" + ticks + " ticks)");

        float max = inTheWay.getMaxHealth();
        ctx.assertTrue(inTheWay.getHealth() <= max - WaterOrbEntity.HIT_DAMAGE, "washed through for 10, health " + inTheWay.getHealth());
        ctx.assertTrue(byTheWall.getHealth() < max && byTheWall.getHealth() >= max - WaterOrbEntity.POP_DAMAGE - 0.01f,
                "only caught in the pop, health " + byTheWall.getHealth());
        ctx.assertTrue(farAway.getHealth() == max, "out of reach, health " + farAway.getHealth());
        ctx.assertTrue(ctx.getBlockState(new BlockPos(1, 2, 7)).isOf(Blocks.STONE), "breaks no blocks");
        ctx.assertTrue(p.getHealth() == p.getMaxHealth(), "never hurts the caster");
        p.discard();
        inTheWay.discard();
        byTheWall.discard();
        farAway.discard();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 20)
    public void swaysUnderABlockAndPopsAfterTenSeconds(TestContext ctx) {
        ServerPlayerEntity p = caster(ctx, new BlockPos(3, 120, 3), 30f, -10f); // high up in the open sky, clear of the test arena
        Vec3d look = p.getRotationVec(1f);
        ctx.assertTrue(Spells.get(SpellId.WATER).cast(ctx.getWorld(), p, ItemStack.EMPTY), "cast");
        Box around = new Box(p.getBlockPos()).expand(8);
        ctx.getWorld().getChunk(p.getBlockPos()); // make sure its chunk is there
        WaterOrbEntity orb = ctx.getWorld().getEntitiesByClass(WaterOrbEntity.class, around, o -> true).get(0);
        Vec3d start = orb.getPos();

        double worst = 0;
        int ticks = 0;
        while (!orb.isRemoved() && ticks < WaterOrbEntity.LIFETIME + 20) {
            orb.tick();
            ticks++;
            Vec3d off = orb.getPos().subtract(start);
            Vec3d across = off.subtract(look.multiply(off.dotProduct(look)));
            worst = Math.max(worst, across.length());
        }
        ctx.assertTrue(ticks == WaterOrbEntity.LIFETIME, "popped after ten seconds, took " + ticks + " ticks");
        ctx.assertTrue(worst < 1.0, "never more than a block off its line, worst " + worst);
        ctx.assertTrue(worst > 0.15, "but it does sway, worst " + worst);
        p.discard();
        ctx.complete();
    }
}
