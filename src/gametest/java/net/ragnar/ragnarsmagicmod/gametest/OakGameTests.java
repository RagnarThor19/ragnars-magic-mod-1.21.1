package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.DisplayEntity;
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
import net.ragnar.ragnarsmagicmod.oak.Oak;
import net.ragnar.ragnarsmagicmod.oak.OakSpell;
import net.ragnar.ragnarsmagicmod.oak.OakTree;

/** Tome of Oak: grows behind you, falls the way you face, crushes what's under it, throws what's near, leaves nothing. */
public class OakGameTests implements FabricGameTest {
    // The caster, in test coordinates, facing +Z (south); the tree takes root behind them and to their right (-X)
    private static final int PX = 20, PZ = 10;

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void tomeIsAMasterTomeWithTheRightNumbers(TestContext ctx) {
        ctx.assertEquals(TomeTier.MASTER, Oak.TOME_OF_OAK.getTier(), "tier");
        ctx.assertEquals(55, Oak.TOME_OF_OAK.getXpCost(), "xp");
        ctx.assertEquals(20 * 120, Oak.TOME_OF_OAK.getCooldown(), "cooldown");
        ctx.assertTrue(Spells.get(SpellId.OAK) instanceof OakSpell, "spell registered");
        ctx.complete();
    }

    private static void floor(TestContext ctx) {
        for (int x = 0; x <= 44; x++) {
            for (int z = 0; z <= 52; z++) {
                ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
                for (int y = 1; y <= 14; y++) ctx.setBlockState(new BlockPos(x, y, z), Blocks.AIR);
            }
        }
        BlockPos a = ctx.getAbsolutePos(new BlockPos(0, 0, 0)), b = ctx.getAbsolutePos(new BlockPos(44, 0, 52));
        for (int cx = Math.min(a.getX(), b.getX()) >> 4; cx <= Math.max(a.getX(), b.getX()) >> 4; cx++) {
            for (int cz = Math.min(a.getZ(), b.getZ()) >> 4; cz <= Math.max(a.getZ(), b.getZ()) >> 4; cz++) {
                ctx.getWorld().setChunkForced(cx, cz, true);
            }
        }
    }

    private static IronGolemEntity golem(TestContext ctx, double x, double z) {
        IronGolemEntity g = ctx.spawnEntity(EntityType.IRON_GOLEM, new BlockPos((int) Math.floor(x), 1, (int) Math.floor(z)));
        g.setAiDisabled(true);
        return g;
    }

    private static ServerPlayerEntity caster(TestContext ctx) {
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(PX, 1, PZ)));
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
        return p;
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "oak_fall", tickLimit = 400)
    public void growsFallsCrushesThrowsAndCrumbles(TestContext ctx) {
        floor(ctx);
        // Test coordinates are mirrored relative to the world for some rotations; work out "right" from the world
        ServerPlayerEntity p = caster(ctx);
        Vec3d right = new Vec3d(-1, 0, 0); // facing +Z, the right hand is -X
        BlockPos rel0 = ctx.getAbsolutePos(new BlockPos(0, 0, 0)), relX = ctx.getAbsolutePos(new BlockPos(1, 0, 0));
        int sx = relX.getX() - rel0.getX(); // +1 if test X runs along world X
        double side = PX + right.x * OakSpell.ASIDE * sx;

        IronGolemEntity underTrunk = golem(ctx, side, PZ + 9);
        IronGolemEntity underCrown = golem(ctx, side, PZ + 31);
        IronGolemEntity nearby = golem(ctx, side + 17, PZ + 31);

        ctx.assertTrue(Spells.get(SpellId.OAK).cast(ctx.getWorld(), p, ItemStack.EMPTY), "cast");
        ctx.assertTrue(Oak.trees().size() == 1, "an oak is growing");
        OakTree tree = Oak.trees().get(0);
        ctx.assertTrue(tree.pivot().z < p.getZ() - 3, "behind the caster: " + tree.pivot() + " vs " + p.getPos());
        net.ragnar.ragnarsmagicmod.RagnarsMagicMod.LOGGER.info("[OAK] {} pieces", tree.pieceCount());
        ctx.assertTrue(tree.pieceCount() <= 260, "a sensible number of pieces: " + tree.pieceCount());

        ctx.waitAndRun(OakTree.GROW_TICKS + OakTree.STAND_TICKS + 5, () ->
                ctx.assertFalse(tree.landed(), "still standing or on its way down"));
        ctx.waitAndRun(OakTree.GROW_TICKS + OakTree.STAND_TICKS + 70, () -> {
            ctx.assertTrue(tree.landed(), "down");
            float trunk = underTrunk.getMaxHealth() - underTrunk.getHealth();
            float crown = underCrown.getMaxHealth() - underCrown.getHealth();
            float near = nearby.getMaxHealth() - nearby.getHealth();
            ctx.assertTrue(trunk >= OakTree.CRUSH_DAMAGE - 1, "crushed under the trunk: " + trunk);
            ctx.assertTrue(crown >= OakTree.CRUSH_DAMAGE - 1, "crushed under the crown: " + crown);
            ctx.assertTrue(near >= OakTree.SHOCK_MIN - 1 && near <= OakTree.SHOCK_MAX + 1, "caught by the shockwave: " + near);
            ctx.assertTrue(p.getHealth() == p.getMaxHealth(), "the caster is never hurt");
        });
        ctx.waitAndRun(OakTree.GROW_TICKS + OakTree.STAND_TICKS + 230, () -> {
            ctx.assertTrue(Oak.trees().isEmpty(), "gone");
            Box around = new Box(p.getBlockPos()).expand(48);
            ctx.assertTrue(ctx.getWorld().getEntitiesByClass(DisplayEntity.BlockDisplayEntity.class, around, e -> true).isEmpty(),
                    "every piece cleared away");
            ctx.assertTrue(ctx.getBlockState(new BlockPos(PX, 0, PZ + 25)).isOf(Blocks.STONE), "breaks no blocks");
            p.discard();
            underTrunk.discard();
            underCrown.discard();
            nearby.discard();
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "oak_room")
    public void needsRoomToGrow(TestContext ctx) {
        floor(ctx);
        ServerPlayerEntity p = caster(ctx);
        // Roof over where it would take root
        for (int x = 0; x <= 44; x++) for (int z = 0; z <= PZ; z++) ctx.setBlockState(new BlockPos(x, 6, z), Blocks.STONE);
        ctx.assertFalse(Spells.get(SpellId.OAK).cast(ctx.getWorld(), p, ItemStack.EMPTY), "no room, no oak");
        ctx.assertTrue(Oak.trees().isEmpty(), "nothing grows");
        p.discard();
        ctx.complete();
    }
}
