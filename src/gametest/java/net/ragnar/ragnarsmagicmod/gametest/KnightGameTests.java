package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.HuskEntity;
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
import net.ragnar.ragnarsmagicmod.item.spell.Spell;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.knight.Knight;
import net.ragnar.ragnarsmagicmod.knight.KnightEntity;
import net.ragnar.ragnarsmagicmod.knight.KnightSpell;

import java.util.List;

/** In-world tests for the Tome of Knight. Each runs in its own batch so a charging knight can't wander into another test. */
public class KnightGameTests implements FabricGameTest {
    private static final BlockPos STAND = new BlockPos(3, 1, 1);
    private static final int RISEN = 34; // past the climb out of the ground

    private static void floor(TestContext ctx, int depth) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < depth; z++) ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
    }

    /** A caster standing at STAND, looking down +z (into the test area). */
    private static ServerPlayerEntity caster(TestContext ctx) {
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(STAND));
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
        p.setHeadYaw(0f);
        p.setOnGround(true);
        return p;
    }

    private static boolean cast(TestContext ctx, ServerPlayerEntity p) {
        return Spells.get(SpellId.KNIGHT).cast(ctx.getWorld(), p, ItemStack.EMPTY);
    }

    private static KnightEntity knightOf(TestContext ctx, ServerPlayerEntity p) {
        List<KnightEntity> all = ctx.getWorld().getEntitiesByClass(KnightEntity.class, new Box(p.getBlockPos()).expand(32), k -> k.isOwnedBy(p));
        return all.isEmpty() ? null : all.get(0);
    }

    private static void cleanUp(TestContext ctx, ServerPlayerEntity p) {
        KnightEntity k = knightOf(ctx, p);
        if (k != null) k.discard();
        p.discard();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "knight_registration")
    public void tomeIsAMasterTomeWithTheRightNumbers(TestContext ctx) {
        ctx.assertEquals(TomeTier.MASTER, Knight.TOME_OF_KNIGHT.getTier(), "tier");
        ctx.assertEquals(80, Knight.TOME_OF_KNIGHT.getXpCost(), "xp");
        ctx.assertEquals(700, Knight.TOME_OF_KNIGHT.getCooldown(), "cooldown");
        ctx.assertTrue(Spells.get(SpellId.KNIGHT) instanceof KnightSpell, "spell registered");
        ctx.assertTrue(ModItems.getTomeFor(SpellId.KNIGHT, TomeTier.MASTER) == Knight.TOME_OF_KNIGHT, "staff lookup");
        ctx.assertEquals(1200, KnightSpell.LIFETIME_TICKS, "lasts a minute");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "knight_guard", skyAccess = true, tickLimit = 200)
    public void knightRisesAndCutsDownANearbyMonster(TestContext ctx) {
        floor(ctx, 8);
        ServerPlayerEntity p = caster(ctx);
        HuskEntity husk = ctx.spawnEntity(EntityType.HUSK, new BlockPos(3, 1, 7));
        husk.setAiDisabled(true);

        Spell spell = Spells.get(SpellId.KNIGHT);
        ctx.assertEquals(50, spell.xpCost(p, 50), "summoning costs the tome's xp");
        ctx.assertTrue(cast(ctx, p), "summon");
        KnightEntity knight = knightOf(ctx, p);
        ctx.assertTrue(knight != null, "a knight appeared");
        ctx.assertEquals(250f, knight.getMaxHealth(), "health");
        ctx.assertTrue(Math.abs(knight.getHeight() - 3.0f) < 0.01f, "three blocks tall");
        ctx.assertFalse(knight.damage(ctx.getWorld().getDamageSources().generic(), 10f), "can't be hurt while climbing out");
        ctx.assertEquals(0, spell.xpCost(p, 50), "orders are free while it's out");
        ctx.assertTrue(spell.ignoresCooldown(p), "and skip the cooldown");
        ctx.assertEquals(0, spell.cooldownAfterCast(p, 500), "cooldown waits until it's gone");

        ctx.runAtEveryTick(() -> {
            if (ctx.getTick() > RISEN + 5 && !husk.isAlive()) {
                ctx.assertTrue(knight.isAlive(), "knight still standing");
                cleanUp(ctx, p);
                ctx.complete();
            }
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "knight_command", skyAccess = true, tickLimit = 200)
    public void orderedTargetGetsChargedAndHitForTwenty(TestContext ctx) {
        floor(ctx, 8);
        ServerPlayerEntity p = caster(ctx);
        // An iron golem: not a monster, so the knight leaves it alone until told otherwise. No armor, 100 health.
        // (Everything stays inside the 8x8 test area - it's walled in with barriers.)
        IronGolemEntity golem = ctx.spawnEntity(EntityType.IRON_GOLEM, new BlockPos(6, 1, 6));
        golem.setAiDisabled(true);

        ctx.assertTrue(cast(ctx, p), "summon");
        KnightEntity knight = knightOf(ctx, p);

        ctx.waitAndRun(RISEN, () -> {
            ctx.assertEquals(100f, golem.getHealth(), "left the golem alone on its own");
            // Stand it in the far corner, so the golem is too far to just walk up to
            Vec3d corner = Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(1, 1, 1)));
            knight.refreshPositionAndAngles(corner.x, corner.y, corner.z, 0f, 0f);
            knight.getNavigation().stop();
            double startDistance = knight.distanceTo(golem);

            p.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, golem.getBoundingBox().getCenter());
            ctx.assertTrue(cast(ctx, p), "point it at the golem");
            ctx.waitAndRun(2, () -> ctx.assertTrue(knight.getTarget() == golem, "took the order, target is " + knight.getTarget()));
            ctx.waitAndRun(30, () -> {
                float lost = 100f - golem.getHealth();
                ctx.assertTrue(lost >= 19.99f, "the golem was hit, lost " + lost);
                ctx.assertTrue(Math.abs(lost / 20f - Math.round(lost / 20f)) < 0.01f, "in blows of 20, lost " + lost);
                ctx.assertTrue(startDistance > 6.5, "started too far to just walk up (" + startDistance + ")");
                cleanUp(ctx, p);
                golem.discard();
                ctx.complete();
            });
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "knight_dismiss", skyAccess = true, tickLimit = 120)
    public void sneakCastSendsItBackAndStartsTheCooldown(TestContext ctx) {
        floor(ctx, 8);
        ServerPlayerEntity p = caster(ctx);
        ctx.assertTrue(cast(ctx, p), "summon");
        KnightEntity knight = knightOf(ctx, p);

        ctx.waitAndRun(RISEN, () -> {
            ctx.assertFalse(p.getItemCooldownManager().isCoolingDown(Knight.TOME_OF_KNIGHT), "no cooldown while it's out");
            p.setSneaking(true);
            ctx.assertTrue(cast(ctx, p), "dismiss");
            ctx.assertFalse(cast(ctx, p), "already going");
            ctx.waitAndRun(35, () -> {
                ctx.assertTrue(knight.isRemoved(), "sank back into the ground");
                ctx.assertTrue(p.getItemCooldownManager().isCoolingDown(Knight.TOME_OF_KNIGHT), "cooldown started once it was gone");
                ctx.assertEquals(50, Spells.get(SpellId.KNIGHT).xpCost(p, 50), "the next summon costs xp again");
                p.discard();
                ctx.complete();
            });
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "knight_room", skyAccess = true)
    public void needsThreeBlocksOfHeadroom(TestContext ctx) {
        floor(ctx, 8);
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) ctx.setBlockState(new BlockPos(x, 3, z), Blocks.STONE);
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(3, 1, 3)));
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
        ctx.assertFalse(cast(ctx, p), "a two-high room is too low");
        ctx.assertTrue(knightOf(ctx, p) == null, "nothing spawned");
        p.discard();
        ctx.complete();
    }
}
