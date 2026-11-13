package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.MagmaCubeEntity;
import net.minecraft.entity.mob.WitherSkeletonEntity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.ragnar.ragnarsmagicmod.fishing.Fishing;
import net.ragnar.ragnarsmagicmod.lavafish.LavaFish;
import net.ragnar.ragnarsmagicmod.lavafish.LavaFishEntity;

/** The lava fish: hauled out of lava, hot on land, at home in lava, bucketable both ways, and it bites in water. */
public class LavaFishGameTests implements FabricGameTest {

    private static ServerPlayerEntity player(TestContext ctx, int x, int z) {
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        p.getInventory().clear();
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(x, 1, z)));
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
        return p;
    }

    private static void floor(TestContext ctx) {
        for (int x = 0; x <= 8; x++) for (int z = 0; z <= 8; z++) {
            ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y <= 4; y++) ctx.setBlockState(new BlockPos(x, y, z), Blocks.AIR);
        }
    }

    /** A lava fish in the middle of block {@code x, z}, {@code y} up: placed the way blocks are, whichever way the test is turned. */
    private static LavaFishEntity fish(TestContext ctx, int x, double y, int z) {
        LavaFishEntity f = LavaFish.LAVA_FISH.create(ctx.getWorld());
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(x, 0, z))).add(0, y, 0);
        f.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
        ctx.getWorld().spawnEntity(f);
        return f;
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "lava_fish_pull")
    public void creaturesAreHauledOutOfTheLavaTowardTheAngler(TestContext ctx) {
        floor(ctx);
        ServerPlayerEntity p = player(ctx, 1, 1);
        Vec3d from = Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(6, 1, 6)));
        var fish = Fishing.pullCreature(ctx.getWorld(), from, p, LavaFish.LAVA_FISH);
        var skeleton = Fishing.pullCreature(ctx.getWorld(), from, p, EntityType.WITHER_SKELETON);
        var cube = Fishing.pullCreature(ctx.getWorld(), from, p, EntityType.MAGMA_CUBE);

        ctx.assertTrue(fish instanceof LavaFishEntity, "a lava fish");
        Vec3d toward = p.getPos().subtract(from);
        ctx.assertTrue(fish.getVelocity().dotProduct(toward) > 0 && fish.getVelocity().y > 0, "flung up and toward the angler: " + fish.getVelocity());
        ctx.assertTrue(fish.isFireImmune() && ((LavaFishEntity) fish).cannotDespawn(), "fireproof, and a keepsake that never despawns");
        ctx.assertTrue(skeleton instanceof WitherSkeletonEntity ws && ws.getMainHandStack().isOf(Items.STONE_SWORD), "an armed wither skeleton");
        ctx.assertTrue(cube instanceof MagmaCubeEntity mc && mc.getSize() == 2, "a middling magma cube");

        // The odds as asked: 1% lava fish, 0.1% each of the others
        ctx.assertEquals(0.01f, Fishing.LAVA_FISH_CHANCE, "lava fish chance");
        ctx.assertEquals(0.001f, Fishing.WITHER_SKELETON_CHANCE, "wither skeleton chance");
        ctx.assertEquals(0.001f, Fishing.MAGMA_CUBE_CHANCE, "magma cube chance");
        ctx.assertEquals(0.05f, Fishing.TRIPLE_CHANCE, "triple pull chance");
        fish.discard();
        skeleton.discard();
        cube.discard();
        p.discard();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "lava_fish_land")
    public void scorchesWhatComesNearOnLand(TestContext ctx) {
        floor(ctx);
        LavaFishEntity f = fish(ctx, 4, 1, 4);
        // Pigs: unlike zombies, nothing else (like the sun) sets them alight
        PigEntity pig = ctx.spawnEntity(EntityType.PIG, new BlockPos(4, 1, 5));
        pig.setAiDisabled(true);
        PigEntity far = ctx.spawnEntity(EntityType.PIG, new BlockPos(8, 1, 8));
        far.setAiDisabled(true);
        for (int i = 0; i < 25; i++) f.tick();
        ctx.assertTrue(pig.getHealth() < pig.getMaxHealth() && pig.isOnFire(), "the pig beside it is scorched and set alight");
        ctx.assertTrue(far.getHealth() == far.getMaxHealth(), "one further off is left alone");
        ctx.assertTrue(f.getAir() < f.getMaxAir(), "and it's running out of air out here");
        f.discard();
        pig.discard();
        far.discard();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "lava_fish_lava", tickLimit = 100)
    public void livesInLava(TestContext ctx) {
        floor(ctx);
        // A walled pool of lava, three deep
        for (int x = 1; x <= 7; x++) for (int z = 1; z <= 7; z++) for (int y = 1; y <= 4; y++) {
            boolean wall = x == 1 || x == 7 || z == 1 || z == 7;
            ctx.setBlockState(new BlockPos(x, y, z), wall ? Blocks.STONE : y <= 3 ? Blocks.LAVA : Blocks.AIR);
        }
        LavaFishEntity f = fish(ctx, 4, 1.5, 4);
        for (int i = 0; i < 400; i++) f.tick();
        ctx.assertTrue(f.isAlive() && f.getHealth() == f.getMaxHealth(), "unhurt after 20 seconds in lava");
        ctx.assertTrue(f.getAir() > f.getMaxAir() - 40, "and breathing easy: " + f.getAir());
        ctx.assertTrue(f.isInLava(), "still in the lava");
        f.discard();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "lava_fish_bucket")
    public void goesInAWaterBucketOrALavaBucket(TestContext ctx) {
        floor(ctx);
        ServerPlayerEntity p = player(ctx, 4, 3);
        LavaFishEntity first = fish(ctx, 4, 1, 4), second = fish(ctx, 5, 1, 4);

        p.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.WATER_BUCKET));
        first.interact(p, Hand.MAIN_HAND);
        ctx.assertTrue(p.getMainHandStack().isOf(LavaFish.BUCKET_OF_LAVA_FISH), "a water bucket scoops it up: " + p.getMainHandStack());
        ctx.assertTrue(first.isRemoved(), "and it's in the bucket");

        p.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.LAVA_BUCKET));
        second.interact(p, Hand.MAIN_HAND);
        ctx.assertTrue(p.getMainHandStack().isOf(LavaFish.LAVA_BUCKET_OF_LAVA_FISH), "so does a lava bucket: " + p.getMainHandStack());
        ctx.assertTrue(second.isRemoved(), "and it's in that one");
        p.discard();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "lava_fish_water", tickLimit = 100)
    public void bitesWhatComesRightUpToItInWater(TestContext ctx) {
        floor(ctx);
        // A walled pool, so the pig (kept right up against the fish) can't shove it out onto dry stone
        for (int x = 0; x <= 8; x++) for (int z = 0; z <= 8; z++) for (int y = 1; y <= 4; y++) {
            boolean wall = x == 0 || x == 8 || z == 0 || z == 8;
            ctx.setBlockState(new BlockPos(x, y, z), wall ? Blocks.STONE : y <= 3 ? Blocks.WATER : Blocks.AIR);
        }
        LavaFishEntity f = fish(ctx, 4, 1.5, 4);
        PigEntity pig = ctx.spawnEntity(EntityType.PIG, new BlockPos(4, 1, 4));
        pig.setAiDisabled(true);
        // A bite is a one-in-three chance each second: 20 seconds of chances
        for (int i = 0; i < 400 && pig.getHealth() == pig.getMaxHealth(); i++) {
            pig.refreshPositionAndAngles(f.getX(), f.getY(), f.getZ() + 0.4, 0f, 0f); // keep it right up against the fish
            f.tick();
        }
        ctx.assertTrue(pig.getHealth() < pig.getMaxHealth(), "bitten");
        ctx.assertTrue(f.isTouchingWater(), "it was in the water all along");
        ctx.assertFalse(pig.isOnFire(), "no burn in water");
        f.discard();
        pig.discard();
        ctx.complete();
    }
}
