package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.mob.GhastEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.WardenEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Unit;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.ragnar.ragnarsmagicmod.bubbles.BubbleEntity;
import net.ragnar.ragnarsmagicmod.bubbles.BubbleSpell;
import net.ragnar.ragnarsmagicmod.bubbles.Bubbles;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;

import java.util.List;

/** Tome of Bubbles: seals in what it touches (even the big ones), carries it, and pops on sharp things or in time. */
public class BubbleGameTests implements FabricGameTest {

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
        return p;
    }

    private static List<BubbleEntity> bubbles(TestContext ctx, ServerPlayerEntity p) {
        return ctx.getWorld().getEntitiesByClass(BubbleEntity.class, new Box(p.getBlockPos()).expand(12), b -> true);
    }

    /** Casts, and hands back the one new bubble (others' may be drifting nearby). */
    private static BubbleEntity castOne(TestContext ctx, ServerPlayerEntity p) {
        List<BubbleEntity> before = bubbles(ctx, p);
        ctx.assertTrue(Spells.get(SpellId.BUBBLES).cast(ctx.getWorld(), p, ItemStack.EMPTY), "cast");
        List<BubbleEntity> out = bubbles(ctx, p);
        out.removeAll(before);
        ctx.assertTrue(out.size() == 1, "one bubble out");
        return out.get(0);
    }

    /** Runs {@code body}, then clears every bubble around so none floats off into another test. */
    private static void clean(TestContext ctx, Runnable body) {
        try {
            body.run();
        } finally {
            BlockPos o = ctx.getAbsolutePos(BlockPos.ORIGIN);
            ctx.getWorld().getEntitiesByClass(BubbleEntity.class, new Box(o).expand(24), b -> true).forEach(BubbleEntity::discard);
        }
    }

    private static String state(BubbleEntity b, net.minecraft.entity.Entity e) {
        return "removed=" + b.isRemoved() + " holding=" + b.held() + " age=" + b.age + " center=" + b.center()
                + " r=" + b.radius() + " target=" + e.getBoundingBox().getCenter() + " alive=" + e.isAlive();
    }

    /** Ticks it by hand until {@code done} (the test server doesn't always tick it reliably), at most {@code max}. */
    private static void tickUntil(BubbleEntity b, int max, java.util.function.BooleanSupplier done) {
        for (int i = 0; i < max && !b.isRemoved() && !done.getAsBoolean(); i++) {
            b.age++; // the world does this before each tick, not tick() itself
            b.tick();
        }
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 80, batchId = "bubble_sealsAnIronGolemAndLiftsIt")
    public void sealsAnIronGolemAndLiftsIt(TestContext ctx) {
        clean(ctx, () -> {
            ctx.assertEquals(TomeTier.BEGINNER, Bubbles.TOME_OF_BUBBLES.getTier(), "tier");
            ctx.assertEquals(10, Bubbles.TOME_OF_BUBBLES.getXpCost(), "xp");
            ctx.assertEquals(360, Bubbles.TOME_OF_BUBBLES.getCooldown(), "cooldown");
            ctx.assertTrue(Spells.get(SpellId.BUBBLES) instanceof BubbleSpell, "spell registered");

            forceChunks(ctx);
            floor(ctx);
            IronGolemEntity golem = ctx.spawnEntity(EntityType.IRON_GOLEM, new BlockPos(3, 1, 5));
            golem.setAiDisabled(true);
            ServerPlayerEntity p = caster(ctx, new BlockPos(3, 1, 0));
            BubbleEntity bubble = castOne(ctx, p);

            tickUntil(bubble, 60, bubble::isHolding);
            ctx.assertTrue(Bubbles.bubbleOf(golem) == bubble, "golem sealed in");
            ctx.assertTrue(bubble.radius() >= BubbleEntity.fitRadius(golem), "swelled to fit, radius " + bubble.radius());
            ctx.assertTrue(Bubbles.bubbleOf(p) == null, "never seals in its caster");

            double groundY = ctx.getAbsolutePos(new BlockPos(0, 1, 0)).getY();
            tickUntil(bubble, 60, () -> false);
            ctx.assertTrue(!bubble.isRemoved(), "still floating");
            ctx.assertTrue(golem.getY() > groundY + 1.0, "lifted off the ground, feet at " + (golem.getY() - groundY));
            ctx.assertTrue(golem.getBoundingBox().getCenter().distanceTo(bubble.center()) < 0.5, "held in the middle");

            // Its time runs out: it pops and lets go
            tickUntil(bubble, BubbleEntity.LIFE, () -> false);
            ctx.assertTrue(bubble.isRemoved(), "popped after its 15 seconds");
            ctx.assertTrue(Bubbles.bubbleOf(golem) == null, "let go");
            p.discard();
            golem.discard();
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 80, batchId = "bubble_anArrowPopsIt")
    public void anArrowPopsIt(TestContext ctx) {
        clean(ctx, () -> {
            forceChunks(ctx);
            floor(ctx);
            MobEntity zombie = ctx.spawnEntity(EntityType.ZOMBIE, new BlockPos(3, 1, 5));
            zombie.setAiDisabled(true);
            ServerPlayerEntity p = caster(ctx, new BlockPos(3, 1, 0));
            BubbleEntity bubble = castOne(ctx, p);
            tickUntil(bubble, 60, bubble::isHolding);
            ctx.assertTrue(Bubbles.bubbleOf(zombie) == bubble, "zombie sealed in; " + state(bubble, zombie));

            // An arrow (from anyone, even something inside) reaching the film pops it
            ArrowEntity arrow = new ArrowEntity(ctx.getWorld(), zombie, new ItemStack(Items.ARROW), null);
            Vec3d c = bubble.center();
            arrow.setPosition(c.x, c.y, c.z);
            ctx.getWorld().spawnEntity(arrow);
            bubble.tick();
            ctx.assertTrue(bubble.isRemoved(), "popped by the arrow");
            ctx.assertTrue(Bubbles.bubbleOf(zombie) == null, "zombie let go");
            arrow.discard();
            p.discard();
            zombie.discard();
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 80, batchId = "bubble_aWardensSonicBoomPopsIt")
    public void aWardensSonicBoomPopsIt(TestContext ctx) {
        clean(ctx, () -> {
            forceChunks(ctx);
            floor(ctx);
            WardenEntity warden = ctx.spawnEntity(EntityType.WARDEN, new BlockPos(3, 1, 5));
            warden.setAiDisabled(true);
            ServerPlayerEntity p = caster(ctx, new BlockPos(3, 1, 0));
            BubbleEntity bubble = castOne(ctx, p);
            tickUntil(bubble, 60, bubble::isHolding);
            ctx.assertTrue(Bubbles.bubbleOf(warden) == bubble, "warden fits and is sealed in; " + state(bubble, warden));
            ctx.assertTrue(bubble.radius() <= BubbleEntity.MAX_RADIUS, "within the most it stretches");

            bubble.tick();
            ctx.assertTrue(!bubble.isRemoved(), "holds while it's quiet");
            // What SonicBoomTask records the moment the boom goes off
            warden.getBrain().remember(MemoryModuleType.SONIC_BOOM_SOUND_COOLDOWN, Unit.INSTANCE, 26);
            bubble.tick();
            ctx.assertTrue(bubble.isRemoved(), "popped by the sonic boom");
            ctx.assertTrue(Bubbles.bubbleOf(warden) == null, "warden let go");
            p.discard();
            warden.discard();
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 80, batchId = "bubble_tooBigToFitPopsIt")
    public void tooBigToFitPopsIt(TestContext ctx) {
        clean(ctx, () -> {
            forceChunks(ctx);
            floor(ctx);
            GhastEntity ghast = ctx.spawnEntity(EntityType.GHAST, new BlockPos(3, 1, 7));
            ghast.setAiDisabled(true);
            ghast.setNoGravity(true);
            ServerPlayerEntity p = caster(ctx, new BlockPos(3, 1, 0));
            BubbleEntity bubble = castOne(ctx, p);
            tickUntil(bubble, 80, () -> false);
            ctx.assertTrue(bubble.isRemoved(), "a ghast is too big: it pops; " + state(bubble, ghast));
            ctx.assertTrue(Bubbles.bubbleOf(ghast) == null, "nothing sealed");
            p.discard();
            ghast.discard();
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 80, batchId = "bubble_hotBlocksPopIt")
    public void hotBlocksPopIt(TestContext ctx) {
        clean(ctx, () -> {
            forceChunks(ctx);
            floor(ctx);
            for (int x = 0; x < 8; x++) for (int y = 1; y < 7; y++) ctx.setBlockState(new BlockPos(x, y, 5), Blocks.MAGMA_BLOCK);
            ServerPlayerEntity p = caster(ctx, new BlockPos(3, 1, 0));
            BubbleEntity bubble = castOne(ctx, p);
            tickUntil(bubble, 120, () -> false);
            ctx.assertTrue(bubble.isRemoved(), "popped on the magma");
            p.discard();
            ctx.complete();
        });
    }
}
