package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.spinattack.SpinAttack;
import net.ragnar.ragnarsmagicmod.spinattack.SpinAttackSpell;

/** Tome of Spin Attack: three full-strength swings all round, harder and wider with a better sword. */
public class SpinAttackGameTests implements FabricGameTest {

    private static void floor(TestContext ctx) {
        for (int x = 0; x <= 12; x++) for (int z = 0; z <= 12; z++) {
            ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y <= 4; y++) ctx.setBlockState(new BlockPos(x, y, z), Blocks.AIR);
        }
    }

    private static ServerPlayerEntity fighter(TestContext ctx, ItemStack sword) {
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        p.getInventory().clear();
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(6, 1, 6)));
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
        p.setStackInHand(Hand.MAIN_HAND, sword);
        p.playerTick(); // puts the sword's attack damage on the player, as holding it does
        return p;
    }

    /** An iron golem (100 health, no armour) {@code distance} blocks east of the fighter, standing still. */
    private static IronGolemEntity golem(TestContext ctx, ServerPlayerEntity p, double distance) {
        IronGolemEntity g = EntityType.IRON_GOLEM.create(ctx.getWorld());
        g.refreshPositionAndAngles(p.getX() + distance, p.getY(), p.getZ(), 0f, 0f);
        g.setAiDisabled(true);
        ctx.getWorld().spawnEntity(g);
        return g;
    }

    private static float lost(IronGolemEntity g) {
        return g.getMaxHealth() - g.getHealth();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void tomeIsAnAdvancedTomeWithTheRightNumbers(TestContext ctx) {
        ctx.assertEquals(TomeTier.ADVANCED, SpinAttack.TOME_OF_SPIN_ATTACK.getTier(), "tier");
        ctx.assertEquals(5, SpinAttack.TOME_OF_SPIN_ATTACK.getXpCost(), "xp");
        ctx.assertEquals(100, SpinAttack.TOME_OF_SPIN_ATTACK.getCooldown(), "5 second cooldown");
        ctx.assertTrue(Spells.get(SpellId.SPIN_ATTACK) instanceof SpinAttackSpell, "spell registered");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "spin_attack_nosword")
    public void noSwordNoSpin(TestContext ctx) {
        floor(ctx);
        ServerPlayerEntity p = fighter(ctx, new ItemStack(Items.STICK));
        ctx.assertFalse(Spells.get(SpellId.SPIN_ATTACK).cast(ctx.getWorld(), p, ItemStack.EMPTY), "nothing to spin with");
        p.discard();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "spin_attack_diamond", tickLimit = 60)
    public void threeFullSwingsAllRound(TestContext ctx) {
        floor(ctx);
        ServerPlayerEntity p = fighter(ctx, new ItemStack(Items.DIAMOND_SWORD));
        IronGolemEntity near = golem(ctx, p, 2.0), far = golem(ctx, p, 5.0);
        ctx.assertTrue(Spells.get(SpellId.SPIN_ATTACK).cast(ctx.getWorld(), p, ItemStack.EMPTY), "spins");
        SpinAttack.forceCrit(p, false);
        ctx.assertTrue(Math.abs(lost(near) - 7f) < 0.01f, "the first spin lands at once, a full diamond sword hit: " + lost(near));
        ctx.waitAndRun(3 * SpinAttack.SPIN_TICKS, () -> {
            ctx.assertTrue(Math.abs(lost(near) - 21f) < 0.01f, "three full hits of 7: " + lost(near));
            ctx.assertEquals(0f, lost(far), "out of a diamond sword's reach (" + SpinAttack.BASE_RADIUS + " + a little)");
            ctx.assertTrue(p.getMainHandStack().getDamage() == 3, "a point of wear per spin that hit: " + p.getMainHandStack().getDamage());
            near.discard();
            far.discard();
            p.discard();
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "spin_attack_last", tickLimit = 60)
    public void theLastSpinReachesFurther(TestContext ctx) {
        floor(ctx);
        ServerPlayerEntity p = fighter(ctx, new ItemStack(Items.DIAMOND_SWORD));
        double radius = SpinAttack.radius(ctx.getWorld(), p, p.getMainHandStack(), Hand.MAIN_HAND);
        // Its near side just past a diamond sword's usual ring (a golem is 1.4 wide), but inside the last spin's longer one
        IronGolemEntity edge = golem(ctx, p, radius + 0.7 + 0.25);
        ctx.assertTrue(Spells.get(SpellId.SPIN_ATTACK).cast(ctx.getWorld(), p, ItemStack.EMPTY), "spins");
        SpinAttack.forceCrit(p, false);
        ctx.assertEquals(lost(edge), 0f, "the first spin doesn't reach it");
        ctx.waitAndRun(3 * SpinAttack.SPIN_TICKS, () -> {
            ctx.assertTrue(Math.abs(lost(edge) - 7f) < 0.01f, "only the last spin reaches it: " + lost(edge));
            edge.discard();
            p.discard();
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "spin_attack_netherite", tickLimit = 60)
    public void aBetterSwordHitsHarderAndWiderAndCritsOnTheLast(TestContext ctx) {
        floor(ctx);
        ItemStack sword = new ItemStack(Items.NETHERITE_SWORD);
        sword.addEnchantment(ctx.getWorld().getRegistryManager().get(RegistryKeys.ENCHANTMENT).getEntry(Enchantments.SHARPNESS).orElseThrow(), 5);
        ServerPlayerEntity p = fighter(ctx, sword);
        double radius = SpinAttack.radius(ctx.getWorld(), p, sword, Hand.MAIN_HAND);
        ctx.assertTrue(Math.abs(radius - 4.5) < 0.01, "Sharpness V netherite reaches 4.5 blocks: " + radius);
        IronGolemEntity wide = golem(ctx, p, 4.0);
        ctx.assertTrue(Spells.get(SpellId.SPIN_ATTACK).cast(ctx.getWorld(), p, ItemStack.EMPTY), "spins");
        SpinAttack.forceCrit(p, true);
        ctx.waitAndRun(3 * SpinAttack.SPIN_TICKS, () -> {
            // 8 for netherite + 3 for Sharpness V = 11 a hit; the critical last one is half again
            float expected = 11f + 11f + 11f * SpinAttack.CRIT_DAMAGE;
            ctx.assertTrue(Math.abs(lost(wide) - expected) < 0.01f, "11 + 11 + a critical 16.5: " + lost(wide));
            wide.discard();
            p.discard();
            ctx.complete();
        });
    }
}
