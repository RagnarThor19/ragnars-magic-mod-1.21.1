package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.ragnar.ragnarsmagicmod.impulse.Impulse;
import net.ragnar.ragnarsmagicmod.impulse.ImpulseCubeEntity;
import net.ragnar.ragnarsmagicmod.impulse.ImpulseSpell;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;

/** Tome of Impulse: the cube sticks where it lands, then flings everything near it away - caster included - unhurt. */
public class ImpulseGameTests implements FabricGameTest {

    /** Keeps the test's chunks ticking whatever its neighbours do (see BallLightningGameTests). */
    private static void forceChunks(TestContext ctx) {
        BlockPos a = ctx.getAbsolutePos(new BlockPos(0, 0, 0)), b = ctx.getAbsolutePos(new BlockPos(7, 0, 7));
        for (int cx = Math.min(a.getX(), b.getX()) >> 4; cx <= Math.max(a.getX(), b.getX()) >> 4; cx++) {
            for (int cz = Math.min(a.getZ(), b.getZ()) >> 4; cz <= Math.max(a.getZ(), b.getZ()) >> 4; cz++) {
                ctx.getWorld().setChunkForced(cx, cz, true);
            }
        }
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 80)
    public void sticksToTheFloorAndFlingsEveryoneAway(TestContext ctx) {
        ctx.assertEquals(TomeTier.BEGINNER, Impulse.TOME_OF_IMPULSE.getTier(), "tier");
        ctx.assertEquals(7, Impulse.TOME_OF_IMPULSE.getXpCost(), "xp");
        ctx.assertEquals(200, Impulse.TOME_OF_IMPULSE.getCooldown(), "cooldown");
        ctx.assertTrue(Spells.get(SpellId.IMPULSE) instanceof ImpulseSpell, "spell registered");

        forceChunks(ctx);
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
        PigEntity pig = ctx.spawnEntity(EntityType.PIG, new BlockPos(5, 1, 4));
        pig.setAiDisabled(true);

        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(3, 1, 4)));
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 90f); // straight down at their own feet

        ctx.assertTrue(Spells.get(SpellId.IMPULSE).cast(ctx.getWorld(), p, ItemStack.EMPTY), "cast");
        Box around = new Box(p.getBlockPos()).expand(16);
        ImpulseCubeEntity cube = ctx.getWorld().getEntitiesByClass(ImpulseCubeEntity.class, around, c -> true).stream().findFirst().orElse(null);
        ctx.assertTrue(cube != null, "cube out");
        float pigHealth = pig.getHealth();

        // The test server sometimes stops ticking a fast-moving entity part way, so drive it by hand
        for (int i = 0; i < 4 && !cube.isStuck(); i++) cube.tick();
        ctx.assertTrue(cube.isStuck(), "stuck to the floor");
        ctx.assertTrue(cube.getY() < at.y + 0.3, "on the floor, y " + cube.getY());
        for (int i = 0; i < ImpulseCubeEntity.FUSE + 2 && !cube.isRemoved(); i++) cube.tick();
        ctx.assertTrue(cube.isRemoved(), "went off");

        ctx.assertTrue(p.getVelocity().y > 1.0, "caster launched up, vy " + p.getVelocity().y);
        Vec3d pigPush = pig.getVelocity();
        ctx.assertTrue(pigPush.x > 0.3 && pigPush.y > 0.3, "pig flung away and up, " + pigPush);
        ctx.assertTrue(pig.getHealth() == pigHealth, "the blast itself hurts nothing");
        ctx.assertTrue(p.getHealth() == p.getMaxHealth(), "nor the caster");
        ctx.assertTrue(ctx.getBlockState(new BlockPos(3, 0, 4)).isOf(Blocks.STONE), "breaks no blocks");
        p.discard();
        pig.discard();
        ctx.complete();
    }
}
