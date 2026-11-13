package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.ragnar.ragnarsmagicmod.fishing.Fishing;
import net.ragnar.ragnarsmagicmod.mixin.FishingBobberAccessor;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Tome of Fishing: +3 luck, quicker bites, double catches, and fishing in lava with its own loot. */
public class FishingGameTests implements FabricGameTest {

    /** A 5x5 pool of lava (inside x/z 2..6, at y 1) on a stone floor, with a stone rim to stand on. */
    private static void lavaPool(TestContext ctx) {
        for (int x = 0; x <= 8; x++) {
            for (int z = 0; z <= 8; z++) {
                ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
                boolean inside = x >= 2 && x <= 6 && z >= 2 && z <= 6;
                ctx.setBlockState(new BlockPos(x, 1, z), inside ? Blocks.LAVA : Blocks.STONE);
                for (int y = 2; y <= 5; y++) ctx.setBlockState(new BlockPos(x, y, z), Blocks.AIR);
            }
        }
    }

    private static ServerPlayerEntity angler(TestContext ctx, boolean tome) {
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        p.getInventory().clear();
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(0, 2, 4)));
        p.refreshPositionAndAngles(at.x, at.y, at.z, -90f, 20f);
        p.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.FISHING_ROD));
        if (tome) p.getInventory().setStack(20, new ItemStack(Fishing.TOME_OF_FISHING));
        return p;
    }

    /** A bobber cast by {@code p}, put down at {@code y} over the middle of the pool. */
    private static FishingBobberEntity bobber(TestContext ctx, ServerPlayerEntity p, double y) {
        FishingBobberEntity b = new FishingBobberEntity(p, ctx.getWorld(), 0, 0);
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(4, 1, 4)));
        b.refreshPositionAndAngles(at.x, at.y + y, at.z, 0f, 0f);
        b.setVelocity(Vec3d.ZERO);
        ctx.getWorld().spawnEntity(b);
        return b;
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "passive_fishing_float", tickLimit = 100)
    public void floatsOnLavaWithTheTomeAndSinksWithout(TestContext ctx) {
        lavaPool(ctx);
        ServerPlayerEntity with = angler(ctx, true), without = angler(ctx, false);
        FishingBobberEntity floating = bobber(ctx, with, 1.2), sinking = bobber(ctx, without, 1.2);
        ctx.assertEquals(Fishing.LUCK, ((FishingBobberAccessor) floating).ragnarsmagicmod$getLuckBonus(), "+3 luck with the tome");
        ctx.assertEquals(0, ((FishingBobberAccessor) sinking).ragnarsmagicmod$getLuckBonus(), "none without");

        double surface = ctx.getAbsolutePos(new BlockPos(0, 1, 0)).getY();
        for (int i = 0; i < 60; i++) {
            floating.tick();
            sinking.tick();
        }
        ctx.assertTrue(!floating.isRemoved() && floating.getY() > surface + 0.5, "floats on the lava: " + (floating.getY() - surface));
        ctx.assertFalse(floating.isOnFire(), "and never catches fire");
        ctx.assertTrue(sinking.isRemoved() || sinking.getY() < surface + 0.3, "sinks without the tome: " + (sinking.getY() - surface));

        // Bites come quicker: a wait of 200 ticks runs down at least half as fast again
        ((FishingBobberAccessor) floating).ragnarsmagicmod$setWaitCountdown(200);
        for (int i = 0; i < 20; i++) floating.tick();
        int left = ((FishingBobberAccessor) floating).ragnarsmagicmod$getWaitCountdown();
        ctx.assertTrue(left <= 172 && left > 0, "the wait runs down faster: " + left);

        floating.discard();
        sinking.discard();
        with.discard();
        without.discard();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "passive_fishing_catch", tickLimit = 100)
    public void lavaCatchesAreTheLavasOwnFireproofAndSometimesDoubleOrTriple(TestContext ctx) {
        lavaPool(ctx);
        ServerPlayerEntity p = angler(ctx, true);
        Set<Item> waterFish = Set.of(Items.COD, Items.SALMON, Items.PUFFERFISH, Items.TROPICAL_FISH);
        Set<Item> seen = new HashSet<>();
        Box around = new Box(ctx.getAbsolutePos(new BlockPos(4, 1, 4))).expand(6);
        int casts = 200, items = 0;
        for (int i = 0; i < casts; i++) {
            FishingBobberEntity b = bobber(ctx, p, 0.5); // in the lava
            ((FishingBobberAccessor) b).ragnarsmagicmod$setHookCountdown(10);
            ctx.assertEquals(1, b.use(p.getMainHandStack()), "reeled something in");
            List<ItemEntity> caught = ctx.getWorld().getEntitiesByClass(ItemEntity.class, around, e -> true);
            for (ItemEntity e : caught) {
                ctx.assertFalse(waterFish.contains(e.getStack().getItem()), "a lava catch, not a water one: " + e.getStack());
                ctx.assertTrue(e.isInvulnerable(), "kept from burning on its way out");
                seen.add(e.getStack().getItem());
                items++;
                e.discard();
            }
            ctx.getWorld().getEntitiesByClass(ExperienceOrbEntity.class, around.expand(4), e -> true).forEach(Entity::discard);
            // Now and then a creature comes up instead (lava fish, wither skeleton, magma cube): clear those away too
            ctx.getWorld().getEntitiesByClass(net.minecraft.entity.mob.MobEntity.class, around.expand(8), e -> true).forEach(Entity::discard);
        }
        ctx.assertTrue(seen.contains(Items.GOLD_NUGGET) || seen.contains(Items.QUARTZ) || seen.contains(Items.MAGMA_CREAM), "Nether catches: " + seen);
        ctx.assertTrue(seen.size() >= 8, "a wide spread of catches: " + seen);
        // 200 casts, 20% double and 5% triple: about 260 catches (a few become creatures, a few more items come from
        // tomes and books)
        ctx.assertTrue(items >= 225 && items <= 300, "doubles and triples: " + items + " from " + casts);
        p.discard();
        ctx.complete();
    }
}
