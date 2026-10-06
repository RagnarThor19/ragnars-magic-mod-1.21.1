package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.entity.ModEntities;
import net.ragnar.ragnarsmagicmod.entity.SteveEntity;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.SummonSteveSpell;

import java.util.List;

/** Tome of Steve: five Steves, all the caster's, never against them, after everything else; a new squad replaces the old. */
public class SteveGameTests implements FabricGameTest {

    private static List<SteveEntity> squad(TestContext ctx, ServerPlayerEntity p) {
        return ctx.getWorld().getEntitiesByType(ModEntities.STEVE, new Box(p.getBlockPos()).expand(40), s -> p.getUuid().equals(s.getOwnerId()));
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 160, batchId = "steve")
    public void summonsAFaithfulSquad(TestContext ctx) {
        for (int x = 0; x < 9; x++) for (int z = 0; z < 9; z++) ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(4, 1, 1)));
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 35f); // looking at the floor a few blocks ahead
        ctx.assertTrue(Spells.get(SpellId.SUMMON_STEVE).cast(ctx.getWorld(), p, ItemStack.EMPTY), "cast");

        ctx.waitAndRun(50, () -> {
            List<SteveEntity> squad = squad(ctx, p);
            ctx.assertEquals(SummonSteveSpell.SQUAD, squad.size(), "squad size");
            ctx.assertEquals(1L, squad.stream().filter(s -> s.getMainHandStack().isOf(Items.DIAMOND_SWORD)).count(), "one leader");
            for (SteveEntity s : squad) {
                ctx.assertTrue(s.isFriend(p), "the caster is a friend");
                ctx.assertTrue(!s.canTarget(p), "never targets the caster");
                ctx.assertTrue(!s.damage(p.getDamageSources().playerAttack(p), 5f), "the caster can't hurt them");
                s.setTarget(p);
                ctx.assertTrue(s.getTarget() == null, "can't be pointed at the caster");
            }
            ZombieEntity zombie = ctx.spawnEntity(EntityType.ZOMBIE, new BlockPos(4, 1, 6));
            zombie.setAiDisabled(true);
            ctx.assertTrue(squad.get(0).canTarget(zombie), "anything else is fair game");
            ctx.waitAndRun(25, () -> {
                ctx.assertTrue(squad(ctx, p).stream().anyMatch(s -> s.getTarget() == zombie) || !zombie.isAlive(), "they go for the zombie");
                // A new squad sends the old one home
                ctx.assertTrue(Spells.get(SpellId.SUMMON_STEVE).cast(ctx.getWorld(), p, ItemStack.EMPTY), "recast");
                ctx.waitAndRun(50, () -> {
                    ctx.assertEquals(SummonSteveSpell.SQUAD, squad(ctx, p).size(), "still just one squad");
                    squad(ctx, p).forEach(SteveEntity::discard);
                    zombie.discard();
                    p.discard();
                    ctx.complete();
                });
            });
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 200, batchId = "steve_distance")
    public void keepsItsDistance(TestContext ctx) {
        for (int x = -6; x < 15; x++) for (int z = -6; z < 15; z++) ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(4, 1, 1)));
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 35f);
        ctx.assertTrue(Spells.get(SpellId.SUMMON_STEVE).cast(ctx.getWorld(), p, ItemStack.EMPTY), "cast");
        ctx.waitAndRun(50, () -> {
            // Shove the whole squad right up against the summoner
            for (SteveEntity s : squad(ctx, p)) {
                s.refreshPositionAndAngles(p.getX() + (s.getRandom().nextDouble() - 0.5), p.getY(), p.getZ() + 1.0, 0f, 0f);
                s.getNavigation().stop();
            }
        });
        ctx.waitAndRun(150, () -> {
            for (SteveEntity s : squad(ctx, p)) {
                double d = Math.sqrt(s.squaredDistanceTo(p));
                ctx.assertTrue(d >= 3.0, "backed off from the summoner, at " + d);
            }
            squad(ctx, p).forEach(SteveEntity::discard);
            p.discard();
            ctx.complete();
        });
    }
}
