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
import net.ragnar.ragnarsmagicmod.sun.SunEntity;

import java.util.List;

/** Tome of the Sun (kindles, drifts, burns, collapses, bursts, leaves the world as it was) and Dimension Split's untouched ground. */
public class SunGameTests implements FabricGameTest {

    private static void floor(TestContext ctx, int size) {
        for (int x = 0; x <= size; x++) for (int z = 0; z <= size; z++) {
            ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y <= 10; y++) ctx.setBlockState(new BlockPos(x, y, z), Blocks.AIR);
        }
    }

    private static ServerPlayerEntity caster(TestContext ctx, int x, int z, float pitch) {
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(x, 1, z)));
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, pitch);
        return p;
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sun_lifecycle", tickLimit = 400)
    public void kindlesDriftsBurnsAndGoesNova(TestContext ctx) {
        floor(ctx, 24);
        ServerPlayerEntity p = caster(ctx, 12, 2, 0f);
        IronGolemEntity golem = EntityType.IRON_GOLEM.create(ctx.getWorld());
        golem.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 10, 0f, 0f);
        golem.setAiDisabled(true);
        ctx.getWorld().spawnEntity(golem);

        ctx.assertTrue(Spells.get(SpellId.SUN).cast(ctx.getWorld(), p, ItemStack.EMPTY), "cast");
        Box around = new Box(p.getBlockPos()).expand(60);
        List<SunEntity> suns = ctx.getWorld().getEntitiesByClass(SunEntity.class, around, s -> true);
        ctx.assertTrue(suns.size() == 1, "a sun is kindling");
        SunEntity sun = suns.get(0);
        ctx.assertEquals(sun.stage(), SunEntity.IGNITING, "igniting");

        // Run it by hand (fresh entities aren't reliably ticked in the test server)
        ctx.waitAndRun(1, () -> {
            for (int i = 0; i < SunEntity.IGNITE_TICKS + 2; i++) sun.tick();
            ctx.assertEquals(sun.stage(), SunEntity.DRIFTING, "let go");
            for (int i = 0; i < 80; i++) sun.tick();
            ctx.assertTrue(golem.getHealth() < golem.getMaxHealth() && golem.isOnFire(), "the golem in its path is burning: " + golem.getHealth());
            ctx.assertTrue(p.getHealth() == p.getMaxHealth(), "never the caster");
            for (int i = 0; i < 400 && !sun.isRemoved(); i++) sun.tick();
            ctx.assertTrue(sun.isRemoved(), "it collapsed, burst and was gone");
            // Nothing in the world was set alight or changed
            for (BlockPos pos : BlockPos.iterate(ctx.getAbsolutePos(new BlockPos(0, 1, 0)), ctx.getAbsolutePos(new BlockPos(24, 10, 24)))) {
                ctx.assertFalse(ctx.getWorld().getBlockState(pos).isOf(Blocks.FIRE) || ctx.getWorld().getBlockState(pos).isOf(Blocks.LIGHT),
                        "left fire or light at " + pos);
            }
            golem.discard();
            p.discard();
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "dimension_split_ground", tickLimit = 300)
    public void dimensionSplitLeavesTheGroundWhole(TestContext ctx) {
        floor(ctx, 16);
        ServerPlayerEntity p = caster(ctx, 8, 0, 35f); // looking down at the floor a few blocks out
        ctx.assertTrue(Spells.get(SpellId.DIMENSION_SPLIT).cast(ctx.getWorld(), p, ItemStack.EMPTY), "cast");
        ctx.waitAndRun(200, () -> {
            for (int x = 0; x <= 16; x++) for (int z = 0; z <= 16; z++) {
                ctx.assertTrue(ctx.getBlockState(new BlockPos(x, 0, z)).isOf(Blocks.STONE), "the floor is whole at " + x + "," + z);
            }
            p.discard();
            ctx.complete();
        });
    }
}
