package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.ragnar.ragnarsmagicmod.living.Living;

/** Tome of Living: the fatal hit is cancelled and you're put back five seconds; once, then an hour's cooldown. */
public class LivingGameTests implements FabricGameTest {

    private static ServerPlayerEntity survivor(TestContext ctx, Vec3d at) {
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        p.getInventory().clear();
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
        return p;
    }

    private static Vec3d spot(TestContext ctx, double x, double z) {
        return Vec3d.ofBottomCenter(ctx.getAbsolutePos(new net.minecraft.util.math.BlockPos((int) x, 1, (int) z)));
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "passive_living", tickLimit = 300)
    public void livingCheatsDeathAndGoesBackFiveSeconds(TestContext ctx) {
        Vec3d start = spot(ctx, 1, 1);
        Vec3d later = spot(ctx, 4, 4);
        ServerPlayerEntity p = survivor(ctx, start);
        p.getInventory().setStack(20, new ItemStack(Living.TOME_OF_LIVING));

        // Stand at the start long enough to be remembered there (and for the join invulnerability to pass)...
        ctx.waitAndRun(130, () -> {
            // ...then move, take some harm, and pick up a wither
            p.refreshPositionAndAngles(later.x, later.y, later.z, 90f, 0f);
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.WITHER, 400, 1));
            ctx.waitAndRun(20, () -> {
                p.timeUntilRegen = 0;
                p.damage(p.getDamageSources().generic(), 1000f);

                ctx.assertTrue(p.isAlive() && !p.isDead(), "survives the fatal hit");
                ctx.assertTrue(p.getHealth() >= Living.MIN_HEALTH, "with some health: " + p.getHealth());
                ctx.assertTrue(p.getPos().distanceTo(start) < 0.5, "back where they stood five seconds ago: " + p.getPos());
                ctx.assertFalse(p.hasStatusEffect(StatusEffects.WITHER), "the wither is gone");
                ctx.assertTrue(p.getItemCooldownManager().isCoolingDown(Living.TOME_OF_LIVING), "the tome goes on cooldown");
                ctx.assertFalse(Living.isReady(p), "and isn't ready again");

                // The same blow can't land again in that moment
                float health = p.getHealth();
                p.timeUntilRegen = 0;
                p.damage(p.getDamageSources().generic(), 1000f);
                ctx.assertTrue(p.isAlive() && p.getHealth() == health, "a moment of grace");

                // After that, with the tome on cooldown, a fatal hit is fatal
                ctx.waitAndRun(Living.GRACE_TICKS + 5, () -> {
                    p.timeUntilRegen = 0;
                    p.damage(p.getDamageSources().generic(), 1000f);
                    ctx.assertTrue(p.isDead(), "only once an hour");
                    p.discard();
                    ctx.complete();
                });
            });
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "passive_living_rules", tickLimit = 200)
    public void livingNeedsTheTomeLetsTotemsGoFirstAndNotKill(TestContext ctx) {
        ServerPlayerEntity without = survivor(ctx, spot(ctx, 1, 1));
        ServerPlayerEntity totem = survivor(ctx, spot(ctx, 3, 1));
        ServerPlayerEntity killed = survivor(ctx, spot(ctx, 5, 1));
        totem.getInventory().setStack(20, new ItemStack(Living.TOME_OF_LIVING));
        totem.setStackInHand(Hand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
        killed.getInventory().setStack(20, new ItemStack(Living.TOME_OF_LIVING));

        ctx.waitAndRun(70, () -> {
            without.damage(without.getDamageSources().generic(), 1000f);
            ctx.assertTrue(without.isDead(), "no tome, no second chance");

            totem.damage(totem.getDamageSources().generic(), 1000f);
            ctx.assertTrue(totem.isAlive(), "saved");
            ctx.assertTrue(totem.getOffHandStack().isEmpty(), "by the totem");
            ctx.assertTrue(Living.isReady(totem), "so the tome is still ready");

            killed.kill();
            ctx.assertTrue(killed.isDead(), "/kill still kills");
            ctx.assertFalse(killed.getItemCooldownManager().isCoolingDown(Living.TOME_OF_LIVING), "without using the tome");

            without.discard();
            totem.discard();
            killed.discard();
            ctx.complete();
        });
    }
}
