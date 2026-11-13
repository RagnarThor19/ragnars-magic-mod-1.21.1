package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.CowEntity;
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
import net.ragnar.ragnarsmagicmod.basalt.Basalt;
import net.ragnar.ragnarsmagicmod.blackhole.BlackHoleEntity;
import net.ragnar.ragnarsmagicmod.blackhole.BlackHoleSpell;
import net.ragnar.ragnarsmagicmod.blackhole.BlackHoles;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;

import java.util.List;

/**
 * Tome of Black Holes: the numbers, the 2.5 second forming, drifting forward, the pull and the crush, the collapse and
 * burst, and never touching the caster. Each test gets a batch of its own: the hole's pull reaches well past the test's
 * own plot and would drag in the neighbours' mobs.
 */
public class BlackHoleGameTests implements FabricGameTest {

    private static void forceChunks(TestContext ctx) {
        BlockPos a = ctx.getAbsolutePos(new BlockPos(-16, 0, -16)), b = ctx.getAbsolutePos(new BlockPos(24, 0, 48));
        for (int cx = Math.min(a.getX(), b.getX()) >> 4; cx <= Math.max(a.getX(), b.getX()) >> 4; cx++) {
            for (int cz = Math.min(a.getZ(), b.getZ()) >> 4; cz <= Math.max(a.getZ(), b.getZ()) >> 4; cz++) {
                ctx.getWorld().setChunkForced(cx, cz, true);
            }
        }
    }

    private static ServerPlayerEntity caster(TestContext ctx) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 16; z++) ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(3, 1, 0)));
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
        p.setHeadYaw(0f);
        return p;
    }

    private static BlackHoleEntity theHole(TestContext ctx, ServerPlayerEntity p) {
        List<BlackHoleEntity> found = ctx.getWorld().getEntitiesByClass(BlackHoleEntity.class, new Box(p.getBlockPos()).expand(8), e -> true);
        ctx.assertTrue(found.size() == 1, "one black hole, found " + found.size());
        return found.get(0);
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void tomeIsAMasterTomeWithTheRightNumbers(TestContext ctx) {
        ctx.assertEquals(TomeTier.MASTER, BlackHoles.TOME_OF_BLACK_HOLES.getTier(), "tier");
        ctx.assertEquals(60, BlackHoles.TOME_OF_BLACK_HOLES.getXpCost(), "xp");
        ctx.assertEquals(1800, BlackHoles.TOME_OF_BLACK_HOLES.getCooldown(), "cooldown (90 seconds)");
        ctx.assertEquals(50, BlackHoleEntity.FORM_TICKS, "2.5 seconds to form");
        ctx.assertTrue(Spells.get(SpellId.BLACK_HOLES) instanceof BlackHoleSpell, "spell registered");
        ctx.assertEquals(BlackHoles.TOME_OF_BLACK_HOLES, ModItems.getTomeFor(SpellId.BLACK_HOLES, TomeTier.MASTER), "lookup");
        ItemGroups.updateDisplayContext(ctx.getWorld().getEnabledFeatures(), true, ctx.getWorld().getRegistryManager());
        List<ItemStack> tools = List.copyOf(Registries.ITEM_GROUP.get(ItemGroups.TOOLS).getDisplayStacks());
        int basalt = -1, hole = -1, oak = -1, speed = -1;
        for (int i = 0; i < tools.size(); i++) {
            if (tools.get(i).isOf(Basalt.TOME_OF_BASALT)) basalt = i;
            if (tools.get(i).isOf(BlackHoles.TOME_OF_BLACK_HOLES)) hole = i;
            if (tools.get(i).isOf(net.ragnar.ragnarsmagicmod.oak.Oak.TOME_OF_OAK)) oak = i;
            if (tools.get(i).isOf(ModItems.TOME_OF_SPEED)) speed = i;
        }
        ctx.assertTrue(hole >= 0 && hole == basalt + 1, "with the master tomes, after basalt (" + basalt + ", " + hole + ")");
        ctx.assertTrue(oak == hole + 1, "then the Tome of Oak (" + hole + ", " + oak + ")");
        ctx.assertTrue(speed == oak + 1, "the passive tomes still start right after the last master tome (" + oak + ", " + speed + ")");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "black_hole_lifecycle", tickLimit = 40, skyAccess = true)
    public void formsForTwoAndAHalfSecondsThenDriftsThenBursts(TestContext ctx) {
        forceChunks(ctx);
        ServerPlayerEntity p = caster(ctx);
        ctx.assertTrue(Spells.get(SpellId.BLACK_HOLES).cast(ctx.getWorld(), p, ItemStack.EMPTY), "cast");
        BlackHoleEntity hole = theHole(ctx, p);
        ctx.assertTrue(hole.center().z > p.getZ() + 3.5, "forms out in front of the caster, " + hole.center());

        for (int i = 0; i < BlackHoleEntity.FORM_TICKS - 1; i++) hole.tick();
        ctx.assertEquals(BlackHoleEntity.FORMING, hole.stage(), "still forming just before 2.5 seconds");
        hole.tick();
        hole.tick();
        ctx.assertEquals(BlackHoleEntity.DRIFTING, hole.stage(), "loosed once formed");

        // Up over the barrier walls round the test plot, which it would otherwise slide along
        hole.setPosition(hole.getPos().add(0, 12, 0));
        double z0 = hole.getZ();
        for (int i = 0; i < 40; i++) hole.tick();
        ctx.assertTrue(hole.getZ() > z0 + 5, "drifts forward the way the caster looked, " + z0 + " -> " + hole.getZ());

        // 41 ticks into the drift so far
        for (int i = 0; i < BlackHoleEntity.DRIFT_TICKS - 42; i++) hole.tick();
        ctx.assertEquals(BlackHoleEntity.DRIFTING, hole.stage(), "still drifting just before its time is up");
        hole.tick();
        hole.tick();
        ctx.assertEquals(BlackHoleEntity.COLLAPSING, hole.stage(), "collapses when its time is up");
        for (int i = 0; i < BlackHoleEntity.COLLAPSE_TICKS + 1; i++) hole.tick();
        ctx.assertEquals(BlackHoleEntity.BURST, hole.stage(), "then bursts");
        for (int i = 0; i < BlackHoleEntity.BURST_TICKS + 1 && !hole.isRemoved(); i++) hole.tick();
        ctx.assertTrue(hole.isRemoved(), "and is gone");
        p.discard();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "black_hole_pull", tickLimit = 200)
    public void pullsInCrushesAndSparesTheCaster(TestContext ctx) {
        forceChunks(ctx);
        // Just beyond where it forms (the test plot is walled in, so it can't be much further). A real mob: one with
        // no AI ignores velocity altogether
        CowEntity cow = ctx.spawnEntity(EntityType.COW, new BlockPos(3, 1, 7));
        cow.setPersistent();
        ServerPlayerEntity p = caster(ctx);
        float casterHealth = p.getHealth();
        Vec3d cowStart = cow.getPos();
        ctx.assertTrue(Spells.get(SpellId.BLACK_HOLES).cast(ctx.getWorld(), p, ItemStack.EMPTY), "cast");
        BlackHoleEntity hole = theHole(ctx, p);
        double startDistance = cowStart.distanceTo(hole.center());

        // While it forms: pulled in and lifted, but not hurt yet
        ctx.waitAndRun(BlackHoleEntity.FORM_TICKS - 5, () -> {
            double d = cow.getPos().distanceTo(hole.center());
            ctx.assertTrue(cow.getY() > cowStart.y + 0.3 || d < startDistance - 0.5,
                    "dragged in or lifted, " + cowStart + " -> " + cow.getPos() + ", hole at " + hole.center());
            ctx.assertTrue(cow.getHealth() >= cow.getMaxHealth(), "not hurt while it's still forming, " + cow.getHealth());
        });
        // Once it's loosed: crushed
        ctx.waitAndRun(BlackHoleEntity.FORM_TICKS + 25, () -> {
            ctx.assertTrue(cow.isDead() || cow.getHealth() <= cow.getMaxHealth() - BlackHoleEntity.INNER_DAMAGE + 0.01f,
                    "crushed, health " + cow.getHealth());
            ctx.assertTrue(p.getHealth() >= casterHealth, "caster untouched");
            hole.discard();
            cow.discard();
            p.discard();
            ctx.complete();
        });
    }
}
