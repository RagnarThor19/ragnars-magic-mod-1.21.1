package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
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
import net.ragnar.ragnarsmagicmod.lightningpath.LightningPath;
import net.ragnar.ragnarsmagicmod.lightningpath.LightningPathRun;
import net.ragnar.ragnarsmagicmod.lightningpath.LightningPathSpell;
import net.ragnar.ragnarsmagicmod.lightningpath.PathBuilder;

import java.util.List;

/** Tome of the Lightning Path: a forgiving painted path, and a run that hits hard and keeps its runner safe. */
public class LightningPathGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void tomeIsAMasterTomeWithTheRightNumbers(TestContext ctx) {
        ctx.assertEquals(TomeTier.MASTER, LightningPath.TOME_OF_THE_LIGHTNING_PATH.getTier(), "tier");
        ctx.assertEquals(25, LightningPath.TOME_OF_THE_LIGHTNING_PATH.getXpCost(), "xp");
        ctx.assertEquals(640, LightningPath.TOME_OF_THE_LIGHTNING_PATH.getCooldown(), "cooldown (32 seconds)");
        ctx.assertTrue(Spells.get(SpellId.LIGHTNING_PATH) instanceof LightningPathSpell, "spell registered");
        ctx.assertEquals(30, LightningPathRun.PAINT_TICKS, "a second and a half to paint");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void pathIsForgiving(TestContext ctx) {
        // Whirling round in circles doesn't tie it in knots
        PathBuilder circles = new PathBuilder(Vec3d.ZERO);
        for (int i = 0; i < 400; i++) {
            double a = i * 0.35;
            circles.add(new Vec3d(5 + Math.cos(a) * 4, 0, Math.sin(a) * 4));
        }
        ctx.assertTrue(circles.length() < 40, "circling keeps it short, length " + circles.length());

        // A straight line stops at 150 blocks
        PathBuilder line = new PathBuilder(Vec3d.ZERO);
        for (int i = 1; i <= 300; i++) line.add(new Vec3d(i, 0, 0));
        ctx.assertTrue(Math.abs(line.length() - PathBuilder.MAX_LENGTH) < 1e-6 && line.full(), "capped at 150, length " + line.length());
        ctx.assertTrue(line.points().get(line.points().size() - 1).x <= PathBuilder.MAX_LENGTH + 1e-6, "ends 150 out");

        // A zigzag stays a zigzag once it's smoothed (near each corner, not pixel perfect)
        PathBuilder zig = new PathBuilder(Vec3d.ZERO);
        Vec3d[] corners = {new Vec3d(10, 0, 8), new Vec3d(20, 0, -8), new Vec3d(30, 0, 8), new Vec3d(40, 0, -8)};
        Vec3d from = Vec3d.ZERO;
        for (Vec3d corner : corners) {
            for (int k = 1; k <= 12; k++) zig.add(from.lerp(corner, k / 12.0));
            from = corner;
        }
        PathBuilder.Route route = PathBuilder.route(zig.points());
        for (Vec3d corner : corners) {
            double best = Double.MAX_VALUE;
            for (Vec3d p : route.points) best = Math.min(best, p.distanceTo(corner));
            ctx.assertTrue(best < 2.5, "the route passes near " + corner + " (closest " + best + ")");
        }
        ctx.assertTrue(route.points.size() > 2 && route.length() > 60, "follows the zigzag, length " + route.length());
        ctx.complete();
    }

    private static ServerPlayerEntity caster(TestContext ctx, float pitch) {
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(3, 1, 0)));
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, pitch);
        p.setHeadYaw(0f);
        return p;
    }

    private static boolean canHurt(ServerPlayerEntity p, DamageSource source) {
        return ServerLivingEntityEvents.ALLOW_DAMAGE.invoker().allowDamage(p, source, 4f);
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "lightning_path")
    public void paintsGoesEarlyHitsHardAndKeepsYouSafe(TestContext ctx) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
        IronGolemEntity inTheWay = ctx.spawnEntity(EntityType.IRON_GOLEM, new BlockPos(3, 1, 4));
        IronGolemEntity aside = ctx.spawnEntity(EntityType.IRON_GOLEM, new BlockPos(7, 1, 1));
        ServerPlayerEntity p = caster(ctx, 35f); // looking at the floor a couple of blocks ahead

        ctx.assertTrue(Spells.get(SpellId.LIGHTNING_PATH).cast(ctx.getWorld(), p, ItemStack.EMPTY), "cast");
        LightningPathRun run = LightningPathRun.of(p);
        ctx.assertTrue(run != null && run.painting(), "painting");
        for (int i = 0; i < 5; i++) run.tick();
        p.setPitch(15f); // further out along the floor
        for (int i = 0; i < 5; i++) run.tick();
        ctx.assertTrue(run.path().length() > 3, "painted a path ahead, length " + run.path().length());
        ctx.assertTrue(run.path().points().get(run.path().points().size() - 1).z > p.getZ() + 2, "out in front");

        // Right-click again: off we go, free
        ctx.assertEquals(0, Spells.get(SpellId.LIGHTNING_PATH).xpCost(p, 25), "going early costs nothing");
        ctx.assertTrue(new LightningPathSpell().ignoresCooldown(p), "and works through the cooldown");
        ctx.assertTrue(Spells.get(SpellId.LIGHTNING_PATH).cast(ctx.getWorld(), p, ItemStack.EMPTY), "go now");
        ctx.assertTrue(run.running() && LightningPath.isRunning(p), "running");
        ctx.assertFalse(canHurt(p, ctx.getWorld().getDamageSources().generic()), "untouchable while running");

        // Tearing through what's in the way (the client moves the runner; here we do it by hand)
        Vec3d from = p.getPos(), to = from.add(0, 0, 6);
        List<LivingEntity> hit = run.strike(p, from, to);
        ctx.assertTrue(hit.contains(inTheWay) && !hit.contains(aside), "hits what's in the way and nothing else");
        ctx.assertTrue(inTheWay.getMaxHealth() - inTheWay.getHealth() >= LightningPathRun.HIT_DAMAGE - 0.01f, "for the full hit");
        ctx.assertTrue(run.strike(p, from, to).isEmpty(), "only once");

        p.refreshPositionAndAngles(to.x, to.y, to.z, 0f, 0f);
        run.arrive(p);
        ctx.assertFalse(run.running(), "arrived");
        ctx.assertFalse(canHurt(p, ctx.getWorld().getDamageSources().fall()), "no fall damage just after landing");
        ctx.assertTrue(canHurt(p, ctx.getWorld().getDamageSources().generic()), "but otherwise hurtable again");
        while (run.tick()) { /* the after-landing grace runs out */ }
        p.discard();
        inTheWay.discard();
        aside.discard();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "lightning_path")
    public void nothingPaintedFizzles(TestContext ctx) {
        ServerPlayerEntity p = caster(ctx, -90f); // straight up at the sky: nothing to paint on
        ctx.assertTrue(Spells.get(SpellId.LIGHTNING_PATH).cast(ctx.getWorld(), p, ItemStack.EMPTY), "cast");
        LightningPathRun run = LightningPathRun.of(p);
        for (int i = 0; i < LightningPathRun.PAINT_TICKS; i++) run.tick();
        ctx.assertFalse(run.running(), "no run without a path");
        ctx.assertFalse(LightningPath.isRunning(p), "not running");
        p.discard();
        ctx.complete();
    }
}
