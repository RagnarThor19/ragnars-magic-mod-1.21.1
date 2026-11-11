package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.VillagerEntity;
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
import net.ragnar.ragnarsmagicmod.poisondarts.PoisonDartEntity;
import net.ragnar.ragnarsmagicmod.poisondarts.PoisonDartSpell;
import net.ragnar.ragnarsmagicmod.poisondarts.PoisonDarts;

import java.util.List;

/** Tome of Poison Darts: the numbers, that a dart hurts and poisons what it hits, and that it sticks into walls. */
public class PoisonDartGameTests implements FabricGameTest {

    private static ServerPlayerEntity caster(TestContext ctx) {
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 7; z++) ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
        }
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(3, 1, 0)));
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f); // looking along +z, level
        return p;
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void tomeIsABeginnerTomeWithTheRightNumbers(TestContext ctx) {
        ctx.assertEquals(TomeTier.BEGINNER, PoisonDarts.TOME_OF_POISON_DARTS.getTier(), "tier");
        ctx.assertEquals(6, PoisonDarts.TOME_OF_POISON_DARTS.getXpCost(), "xp");
        ctx.assertEquals(100, PoisonDarts.TOME_OF_POISON_DARTS.getCooldown(), "cooldown (5 seconds)");
        ctx.assertEquals(10f, PoisonDartEntity.DAMAGE, "10 damage");
        ctx.assertTrue(Spells.get(SpellId.POISON_DARTS) instanceof PoisonDartSpell, "spell registered");
        net.minecraft.item.ItemGroups.updateDisplayContext(ctx.getWorld().getEnabledFeatures(), true, ctx.getWorld().getRegistryManager());
        var tools = net.minecraft.registry.Registries.ITEM_GROUP.get(net.minecraft.item.ItemGroups.TOOLS);
        ctx.assertTrue(tools.getDisplayStacks().stream().anyMatch(s -> s.isOf(PoisonDarts.TOME_OF_POISON_DARTS)), "in Tools & Utilities");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 60)
    public void hurtsAndPoisonsWhatItHits(TestContext ctx) {
        ServerPlayerEntity p = caster(ctx);
        VillagerEntity target = ctx.spawnEntity(EntityType.VILLAGER, new BlockPos(3, 1, 5));
        target.setAiDisabled(true);
        float before = target.getHealth();
        ctx.assertTrue(Spells.get(SpellId.POISON_DARTS).cast(p.getServerWorld(), p, p.getMainHandStack()), "cast");
        ctx.runAtTick(10, () -> {
            ctx.assertTrue(target.hasStatusEffect(StatusEffects.POISON), "poisoned");
            ctx.assertTrue(before - target.getHealth() >= PoisonDartEntity.DAMAGE - 0.01f,
                    "took the hit (" + (before - target.getHealth()) + ")");
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 100)
    public void sticksInAWallThenGoes(TestContext ctx) {
        ServerPlayerEntity p = caster(ctx);
        for (int x = 0; x <= 6; x++) {
            for (int y = 1; y <= 3; y++) ctx.setBlockState(new BlockPos(x, y, 6), Blocks.OAK_PLANKS);
        }
        ctx.assertTrue(Spells.get(SpellId.POISON_DARTS).cast(p.getServerWorld(), p, p.getMainHandStack()), "cast");
        Box area = new Box(ctx.getAbsolutePos(BlockPos.ORIGIN)).stretch(7, 4, 8);
        ctx.runAtTick(10, () -> {
            List<PoisonDartEntity> darts = p.getServerWorld().getEntitiesByClass(PoisonDartEntity.class, area, e -> true);
            ctx.assertTrue(darts.size() == 1 && darts.get(0).isStuck(), "stuck in the wall");
            double wallZ = ctx.getAbsolutePos(new BlockPos(3, 1, 6)).getZ();
            ctx.assertTrue(darts.get(0).getZ() < wallZ + 0.01 && darts.get(0).getZ() > wallZ - 0.5, "at the wall, not " + darts.get(0).getZ());
        });
        ctx.runAtTick(70, () -> {
            ctx.assertTrue(p.getServerWorld().getEntitiesByClass(PoisonDartEntity.class, area, e -> true).isEmpty(), "gone again");
            ctx.complete();
        });
    }
}
