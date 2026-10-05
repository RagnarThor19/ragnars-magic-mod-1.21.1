package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.ChickenEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.jumping.Jumping;
import net.ragnar.ragnarsmagicmod.jumping.JumpingSpell;
import net.ragnar.ragnarsmagicmod.ricochet.Ricochet;
import net.ragnar.ragnarsmagicmod.ricochet.RicochetArrowEntity;
import net.ragnar.ragnarsmagicmod.ricochet.RicochetSpell;

/** Tome of Ricochet (the arrow winds up, then chains between nearby mobs) and Tome of Jumping (passive). */
public class RicochetAndJumpingGameTests implements FabricGameTest {

    private static ServerPlayerEntity player(TestContext ctx, BlockPos rel, float yaw, float pitch) {
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(rel));
        p.refreshPositionAndAngles(at.x, at.y, at.z, yaw, pitch);
        return p;
    }

    private static IronGolemEntity golem(TestContext ctx, BlockPos rel) {
        IronGolemEntity g = ctx.spawnEntity(EntityType.IRON_GOLEM, rel);
        g.setAiDisabled(true);
        return g;
    }

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

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 100)
    public void ricochetChainsToMobsInRange(TestContext ctx) {
        ctx.assertEquals(TomeTier.ADVANCED, Ricochet.TOME_OF_RICOCHET.getTier(), "tier");
        ctx.assertEquals(10, Ricochet.TOME_OF_RICOCHET.getXpCost(), "xp");
        ctx.assertEquals(200, Ricochet.TOME_OF_RICOCHET.getCooldown(), "cooldown");
        ctx.assertTrue(Spells.get(SpellId.RICOCHET) instanceof RicochetSpell, "spell registered");

        // A neighbouring test finishing can release a chunk this one shares, freezing the arrow mid-flight
        forceChunks(ctx, true);
        // All inside the test's own 8x8 area, so neighbouring tests can't get in the way
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
        IronGolemEntity first = golem(ctx, new BlockPos(4, 1, 3));
        IronGolemEntity second = golem(ctx, new BlockPos(6, 1, 3));  // 2 blocks from the first, well clear of the edge
        // Small, so it fits in the corner without poking into the wall: over 5 blocks from both golems
        ChickenEntity farAway = ctx.spawnEntity(EntityType.CHICKEN, new BlockPos(0, 1, 7));
        farAway.setAiDisabled(true);
        ServerPlayerEntity p = player(ctx, new BlockPos(4, 1, 0), 0f, 0f); // looking at the first

        ctx.assertTrue(Spells.get(SpellId.RICOCHET).cast(ctx.getWorld(), p, ItemStack.EMPTY), "cast");
        Box around = new Box(p.getBlockPos()).expand(32);
        ctx.waitAndRun(RicochetArrowEntity.CHARGE_TICKS - 5, () -> {
            ctx.assertTrue(ctx.getWorld().getEntitiesByClass(RicochetArrowEntity.class, around, a -> true).size() == 1, "still winding up");
            ctx.assertTrue(first.getHealth() == first.getMaxHealth(), "nothing hit before it lets go");
        });
        ctx.waitAndRun(RicochetArrowEntity.CHARGE_TICKS + 15, () -> {
            // The test server sometimes stops ticking a fast-moving entity part way, so finish its flight by hand
            for (RicochetArrowEntity arrow : ctx.getWorld().getEntitiesByClass(RicochetArrowEntity.class, around, x -> true)) {
                for (int i = 0; i < 80 && !arrow.isRemoved(); i++) arrow.tick();
            }
            float hurt = first.getMaxHealth() - RicochetArrowEntity.DAMAGE;
            ctx.assertTrue(Math.abs(first.getHealth() - hurt) < 0.01, "first hit for 20, health " + first.getHealth());
            ctx.assertTrue(Math.abs(second.getHealth() - hurt) < 0.01, "bounced to the second, health " + second.getHealth());
            ctx.assertTrue(farAway.getHealth() == farAway.getMaxHealth(), "out of range, health " + farAway.getHealth());
            ctx.assertTrue(ctx.getWorld().getEntitiesByClass(RicochetArrowEntity.class, around, a -> true).isEmpty(), "arrow gone");
            p.discard();
            first.discard();
            second.discard();
            farAway.discard();
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void jumpingIsPassive(TestContext ctx) {
        ctx.assertEquals(TomeTier.ADVANCED, Jumping.TOME_OF_JUMPING.getTier(), "tier");
        ctx.assertEquals(1, Jumping.TOME_OF_JUMPING.getXpCost(), "xp");
        ctx.assertEquals(0, Jumping.TOME_OF_JUMPING.getCooldown(), "cooldown");
        ctx.assertTrue(Spells.get(SpellId.JUMPING) instanceof JumpingSpell, "spell registered");

        ServerPlayerEntity p = player(ctx, new BlockPos(1, 1, 1), 0f, 0f);
        p.addExperience(50);
        ctx.assertFalse(Jumping.canAirJump(p), "no staff, no air jumps");

        ItemStack staff = new ItemStack(ModItems.DIAMOND_STAFF);
        ((StaffItem) ModItems.DIAMOND_STAFF).insertTome(staff, Jumping.TOME_OF_JUMPING);
        ((StaffItem) ModItems.DIAMOND_STAFF).insertTome(staff, Ricochet.TOME_OF_RICOCHET); // not even selected
        p.getInventory().setStack(5, staff);
        ctx.assertTrue(Jumping.canAirJump(p), "works from anywhere in the inventory, selected or not");

        ctx.assertFalse(Spells.get(SpellId.JUMPING).cast(ctx.getWorld(), p, staff), "casting does nothing");
        ctx.assertEquals(0, Spells.get(SpellId.JUMPING).xpCost(p, 1), "and costs nothing");
        p.discard();
        ctx.complete();
    }
}
