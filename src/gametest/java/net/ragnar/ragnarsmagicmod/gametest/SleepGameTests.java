package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
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
import net.ragnar.ragnarsmagicmod.lunging.Lunging;
import net.ragnar.ragnarsmagicmod.sight.Sight;
import net.ragnar.ragnarsmagicmod.sleep.Sleep;
import net.ragnar.ragnarsmagicmod.sleep.SleepDartEntity;
import net.ragnar.ragnarsmagicmod.sleep.SleepDartSpell;
import net.ragnar.ragnarsmagicmod.sleep.SleepPotionEntity;
import net.ragnar.ragnarsmagicmod.sleep.SleepPotionSpell;

import java.util.List;

/** Tome of Sleep Darts / Sleep Potions: drowsy for 2 seconds, asleep for 10, woken at once by any damage. */
public class SleepGameTests implements FabricGameTest {

    /** See BallLightningGameTests.forceChunks. */
    private static void forceChunks(TestContext ctx) {
        BlockPos a = ctx.getAbsolutePos(new BlockPos(0, 0, 0)), b = ctx.getAbsolutePos(new BlockPos(7, 0, 7));
        for (int cx = Math.min(a.getX(), b.getX()) >> 4; cx <= Math.max(a.getX(), b.getX()) >> 4; cx++) {
            for (int cz = Math.min(a.getZ(), b.getZ()) >> 4; cz <= Math.max(a.getZ(), b.getZ()) >> 4; cz++) {
                ctx.getWorld().setChunkForced(cx, cz, true);
            }
        }
    }

    private static void floor(TestContext ctx) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
    }

    /** A survival caster at {@code rel}, looking straight down +z. */
    private static ServerPlayerEntity caster(TestContext ctx, BlockPos rel) {
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(rel));
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
        p.setHeadYaw(0f);
        return p;
    }

    private static ZombieEntity zombie(TestContext ctx, BlockPos rel) {
        // Husks: zombies would catch fire in the sun, and the burning would wake them
        ZombieEntity z = ctx.spawnEntity(EntityType.HUSK, rel);
        z.setPersistent();
        return z;
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 300, batchId = "sleep_drowsyThenAsleepThenWakes")
    public void drowsyThenAsleepThenWakes(TestContext ctx) {
        ctx.assertEquals(TomeTier.BEGINNER, Sleep.TOME_OF_SLEEP_DARTS.getTier(), "dart tier");
        ctx.assertEquals(10, Sleep.TOME_OF_SLEEP_DARTS.getXpCost(), "dart xp");
        ctx.assertEquals(20 * 23, Sleep.TOME_OF_SLEEP_DARTS.getCooldown(), "dart cooldown");
        ctx.assertEquals(TomeTier.ADVANCED, Sleep.TOME_OF_SLEEP_POTIONS.getTier(), "potion tier");
        ctx.assertEquals(12, Sleep.TOME_OF_SLEEP_POTIONS.getXpCost(), "potion xp");
        ctx.assertEquals(20 * 22, Sleep.TOME_OF_SLEEP_POTIONS.getCooldown(), "potion cooldown");
        ctx.assertTrue(Spells.get(SpellId.SLEEP_DARTS) instanceof SleepDartSpell, "dart spell registered");
        ctx.assertTrue(Spells.get(SpellId.SLEEP_POTIONS) instanceof SleepPotionSpell, "potion spell registered");

        forceChunks(ctx);
        floor(ctx);
        ZombieEntity sleeper = zombie(ctx, new BlockPos(2, 1, 2));
        ZombieEntity napper = zombie(ctx, new BlockPos(5, 1, 5));
        double speed = sleeper.getAttributeValue(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        ctx.assertTrue(Sleep.putToSleep(ctx.getWorld(), sleeper), "can be put to sleep");
        ctx.assertTrue(Sleep.putToSleep(ctx.getWorld(), napper), "can be put to sleep");
        ctx.assertTrue(Sleep.isSleepy(sleeper) && !Sleep.isAsleep(sleeper), "drowsy first");
        ctx.assertTrue(sleeper.getAttributeValue(EntityAttributes.GENERIC_MOVEMENT_SPEED) < speed, "slowed while drowsy");

        ArmorStandEntity stand = ctx.spawnEntity(EntityType.ARMOR_STAND, new BlockPos(1, 1, 6));
        ctx.assertTrue(!Sleep.putToSleep(ctx.getWorld(), stand), "armor stands don't sleep");

        ctx.runAtTick(Sleep.DROWSY_TICKS + 5, () -> {
            ctx.assertTrue(Sleep.isAsleep(sleeper), "asleep after two seconds");
            ctx.assertTrue(sleeper.getAttributeValue(EntityAttributes.GENERIC_MOVEMENT_SPEED) == 0, "can't move");
            // A slap wakes it straight up
            sleeper.damage(ctx.getWorld().getDamageSources().generic(), 1f);
            ctx.assertTrue(!Sleep.isSleepy(sleeper), "woken by damage");
            ctx.assertTrue(sleeper.getAttributeValue(EntityAttributes.GENERIC_MOVEMENT_SPEED) == speed, "moves again");
            ctx.assertTrue(Sleep.isAsleep(napper), "the other sleeps on");
        });
        ctx.runAtTick(Sleep.TOTAL_TICKS - 5, () -> ctx.assertTrue(Sleep.isAsleep(napper), "still asleep near the end"));
        ctx.runAtTick(Sleep.TOTAL_TICKS + 5, () -> {
            ctx.assertTrue(!Sleep.isSleepy(napper), "woke up on its own after ten seconds");
            ctx.assertTrue(napper.getAttributeValue(EntityAttributes.GENERIC_MOVEMENT_SPEED) == speed, "moves again");
            sleeper.discard();
            napper.discard();
            stand.discard();
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void sitWithTheirTiersInTheCreativeTab(TestContext ctx) {
        ItemGroups.updateDisplayContext(ctx.getWorld().getEnabledFeatures(), true, ctx.getWorld().getRegistryManager());
        List<Item> tab = Registries.ITEM_GROUP.get(ItemGroups.TOOLS).getDisplayStacks().stream().map(ItemStack::getItem).toList();
        ctx.assertEquals(tab.indexOf(Lunging.TOME_OF_LUNGING) + 1, tab.indexOf(Sleep.TOME_OF_SLEEP_DARTS), "darts after the last beginner tome");
        ctx.assertEquals(tab.indexOf(Sight.TOME_OF_SIGHT) + 1, tab.indexOf(Sleep.TOME_OF_SLEEP_POTIONS), "potions after the last advanced tome");
        ctx.assertEquals(TomeTier.ADVANCED, ((net.ragnar.ragnarsmagicmod.item.custom.TomeItem) tab.get(tab.indexOf(Sleep.TOME_OF_SLEEP_DARTS) + 1)).getTier(), "advanced tomes start right after the darts");
        ctx.assertEquals(TomeTier.MASTER, ((net.ragnar.ragnarsmagicmod.item.custom.TomeItem) tab.get(tab.indexOf(Sleep.TOME_OF_SLEEP_POTIONS) + 1)).getTier(), "master tomes start right after the potions");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 100, batchId = "sleep_dartHitsAfterItsCharge")
    public void dartHitsAfterItsCharge(TestContext ctx) {
        forceChunks(ctx);
        floor(ctx);
        ZombieEntity target = zombie(ctx, new BlockPos(3, 1, 6));
        target.setAiDisabled(true);
        ServerPlayerEntity p = caster(ctx, new BlockPos(3, 1, 0));
        p.setPitch(5f); // the dart leaves from about chest height; aim a little down at the zombie
        ctx.assertTrue(Spells.get(SpellId.SLEEP_DARTS).cast(ctx.getWorld(), p, ItemStack.EMPTY), "cast");

        Box around = new Box(p.getBlockPos()).expand(12);
        ctx.runAtTick(SleepDartSpell.CHARGE_TICKS - 5, () ->
                ctx.assertTrue(ctx.getWorld().getEntitiesByClass(SleepDartEntity.class, around, d -> true).isEmpty(), "still charging"));
        ctx.waitAndRun(SleepDartSpell.CHARGE_TICKS + 15, () -> {
            ctx.assertTrue(Sleep.isSleepy(target), "the dart put it to sleep");
            ctx.assertTrue(!Sleep.isSleepy(p), "never its own caster");
            Sleep.wake(target, false);
            target.discard();
            p.discard();
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 100, batchId = "sleep_potionCatchesSeveral")
    public void potionCatchesSeveral(TestContext ctx) {
        forceChunks(ctx);
        floor(ctx);
        List<ZombieEntity> zombies = List.of(
                zombie(ctx, new BlockPos(2, 1, 2)), zombie(ctx, new BlockPos(5, 1, 2)), zombie(ctx, new BlockPos(3, 1, 5)));
        zombies.forEach(z -> z.setAiDisabled(true));
        ServerPlayerEntity p = caster(ctx, new BlockPos(6, 1, 6));
        // Dropped from just above the middle of them
        SleepPotionEntity potion = new SleepPotionEntity(ctx.getWorld(), p);
        Vec3d mid = Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(3, 3, 3)));
        potion.setPosition(mid.x, mid.y, mid.z);
        potion.setVelocity(0, -0.5, 0);
        ctx.getWorld().spawnEntity(potion);

        ctx.waitAndRun(20, () -> {
            ctx.assertTrue(potion.isRemoved(), "burst on landing");
            for (ZombieEntity z : zombies) ctx.assertTrue(Sleep.isSleepy(z), "every zombie in the cloud is nodding off");
            ctx.assertTrue(!Sleep.isSleepy(p), "the thrower is spared");
            zombies.forEach(z -> {
                Sleep.wake(z, false);
                z.discard();
            });
            p.discard();
            ctx.complete();
        });
    }
}
