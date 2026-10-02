package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.item.spell.ReckoningSpell;

/** Tome of Reckoning: the wave catches what's on the ground, misses what's in the air, then slams its catch. */
public class ReckoningGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "reckoning_registration")
    public void tomeIsAMasterTomeWithTheRightNumbers(TestContext ctx) {
        ctx.assertEquals(TomeTier.MASTER, ModItems.TOME_OF_RECKONING.getTier(), "tier");
        ctx.assertEquals(35, ModItems.TOME_OF_RECKONING.getXpCost(), "xp");
        ctx.assertEquals(560, ModItems.TOME_OF_RECKONING.getCooldown(), "cooldown");
        ctx.assertTrue(Spells.get(SpellId.RECKONING) instanceof ReckoningSpell, "spell registered");
        ctx.assertTrue(ModItems.getTomeFor(SpellId.RECKONING, TomeTier.MASTER) == ModItems.TOME_OF_RECKONING, "staff lookup");
        ctx.assertEquals(26f, ReckoningSpell.damageAt(0), "26 at the caster's feet");
        ctx.assertEquals(16f, ReckoningSpell.damageAt(18), "16 at the edge");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "reckoning_wave", skyAccess = true, tickLimit = 160)
    public void groundedIsSlammedAirborneIsSpared(TestContext ctx) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(1, 1, 1)));
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
        p.setOnGround(true);
        float casterHealth = p.getHealth();

        // On the ground, ~5 blocks out: caught
        IronGolemEntity grounded = ctx.spawnEntity(EntityType.IRON_GOLEM, new BlockPos(1, 1, 6));
        // Not NoAI: NoAI mobs ignore velocity, so it couldn't be lifted. Rooted with slowness instead.
        grounded.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                net.minecraft.entity.effect.StatusEffects.SLOWNESS, 400, 255, false, false));
        // Held in the air while the wave passes under it: spared
        IronGolemEntity airborne = ctx.spawnEntity(EntityType.IRON_GOLEM, new BlockPos(6, 1, 1));
        airborne.setAiDisabled(true);
        airborne.setNoGravity(true);
        airborne.setPosition(airborne.getX(), airborne.getY() + 1.5, airborne.getZ());
        airborne.setOnGround(false);

        // Everything scheduled up front: adding test tasks from inside another task trips up the test runner
        double distance = Math.sqrt(grounded.squaredDistanceTo(at.x, grounded.getY(), at.z));
        float expected = ReckoningSpell.damageAt(distance);
        double groundY = grounded.getY();
        double[] highest = {groundY};
        ctx.assertTrue(Spells.get(SpellId.RECKONING).cast(ctx.getWorld(), p, ItemStack.EMPTY), "cast");
        ctx.runAtEveryTick(() -> highest[0] = Math.max(highest[0], grounded.getY()));
        // Wave out to 18 (40 ticks), rise and shake (28), slam (a few)
        ctx.waitAndRun(90, () -> {
            ctx.assertTrue(highest[0] > groundY + 3.0, "it was lifted up (rose " + (highest[0] - groundY) + ")");
            float lost = 100f - grounded.getHealth();
            ctx.assertTrue(Math.abs(lost - expected) < 0.01f, "slammed for " + expected + " at " + distance + " blocks, lost " + lost);
            ctx.assertTrue(grounded.isOnGround() && !grounded.hasNoGravity(), "back on the ground, gravity restored");
            ctx.assertEquals(100f, airborne.getHealth(), "the airborne one dodged");
            ctx.assertEquals(casterHealth, p.getHealth(), "the caster is untouched");
            grounded.discard();
            airborne.discard();
            p.discard();
            ctx.complete();
        });
    }
}
