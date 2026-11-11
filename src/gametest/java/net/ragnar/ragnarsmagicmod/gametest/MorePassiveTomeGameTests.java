package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.FleeEntityGoal;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.PhantomEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.world.GameMode;
import net.ragnar.ragnarsmagicmod.fireresistance.FireResistance;
import net.ragnar.ragnarsmagicmod.gourmet.Gourmet;
import net.ragnar.ragnarsmagicmod.grace.Grace;
import net.ragnar.ragnarsmagicmod.lifesteal.Lifesteal;
import net.ragnar.ragnarsmagicmod.mixin.MobEntityAccessor;
import net.ragnar.ragnarsmagicmod.swimming.Swimming;

/** Swimming, Fire Resistance, Grace, Lifesteal and the Gourmet: each works while carried, and only then. */
public class MorePassiveTomeGameTests implements FabricGameTest {

    private static ServerPlayerEntity survivor(TestContext ctx) {
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        p.getInventory().clear();
        return p;
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "passive_swimming")
    public void swimmingBreathesAndDoublesSpeed(TestContext ctx) {
        ServerPlayerEntity p = survivor(ctx);
        Swimming.update(p);
        ctx.assertFalse(p.hasStatusEffect(StatusEffects.WATER_BREATHING), "nothing without the tome");
        ctx.assertEquals(0.02f, Swimming.waterSpeed(p, 0.02f), "normal swimming without it");

        p.getInventory().setStack(30, new ItemStack(Swimming.TOME_OF_SWIMMING));
        Swimming.update(p);
        ctx.assertTrue(p.hasStatusEffect(StatusEffects.WATER_BREATHING), "breathes underwater");
        ctx.assertEquals(0.04f, Swimming.waterSpeed(p, 0.02f), "twice the push through water");
        ctx.assertTrue(Swimming.extraUp(p) > 0, "and swims up faster");

        p.getInventory().setStack(30, ItemStack.EMPTY);
        Swimming.update(p);
        ctx.assertFalse(p.hasStatusEffect(StatusEffects.WATER_BREATHING), "gone with the tome");
        p.discard();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "passive_fire", tickLimit = 120)
    public void fireNeverHurtsAndLavaIsSwimmable(TestContext ctx) {
        ServerPlayerEntity p = survivor(ctx);
        // A player who's just joined can't be hurt for 3 seconds: wait that out first
        ctx.waitAndRun(70, () -> fireChecks(ctx, p));
    }

    private static void fireChecks(TestContext ctx, ServerPlayerEntity p) {
        float full = p.getHealth();
        p.damage(p.getDamageSources().lava(), 4f);
        ctx.assertTrue(p.getHealth() < full, "lava hurts without the tome");
        p.setHealth(full);
        p.timeUntilRegen = 0;

        p.getInventory().setStack(12, new ItemStack(FireResistance.TOME_OF_FIRE_RESISTANCE));
        // Even before the effect is topped up (or right after milk), fire can't touch them
        ctx.assertFalse(p.hasStatusEffect(StatusEffects.FIRE_RESISTANCE), "no effect yet");
        p.damage(p.getDamageSources().lava(), 4f);
        p.damage(p.getDamageSources().inFire(), 4f);
        p.damage(p.getDamageSources().onFire(), 4f);
        p.damage(p.getDamageSources().hotFloor(), 4f);
        ctx.assertEquals(full, p.getHealth(), "no fire damage of any kind");
        p.damage(p.getDamageSources().generic(), 2f);
        ctx.assertTrue(p.getHealth() < full, "other damage still hurts");

        FireResistance.update(p);
        ctx.assertTrue(p.hasStatusEffect(StatusEffects.FIRE_RESISTANCE), "and the icon (and lava view) of Fire Resistance");
        ctx.assertTrue(FireResistance.lavaSpeed(p, 0.02f) > 0.06f, "a real push through lava");
        p.discard();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "passive_grace")
    public void creepersAndPhantomsLeaveYouAlone(TestContext ctx) {
        ServerPlayerEntity p = survivor(ctx);
        CreeperEntity creeper = ctx.spawnEntity(EntityType.CREEPER, 4, 1, 4);
        PhantomEntity phantom = ctx.spawnEntity(EntityType.PHANTOM, 2, 4, 2);
        ZombieEntity zombie = ctx.spawnEntity(EntityType.ZOMBIE, 1, 1, 4);

        creeper.setTarget(p);
        phantom.setTarget(p);
        ctx.assertTrue(creeper.getTarget() == p && phantom.getTarget() == p, "they go for you without the tome");

        p.getInventory().setStack(3, new ItemStack(Grace.TOME_OF_GRACE));
        creeper.setTarget(null);
        phantom.setTarget(null);
        creeper.setTarget(p);
        phantom.setTarget(p);
        zombie.setTarget(p);
        ctx.assertTrue(creeper.getTarget() == null, "a creeper won't pick you");
        ctx.assertTrue(phantom.getTarget() == null, "nor a phantom");
        ctx.assertTrue(zombie.getTarget() == p, "zombies still do");

        boolean flees = ((MobEntityAccessor) creeper).ragnarsmagicmod$getGoalSelector().getGoals().stream()
                .anyMatch(g -> g.getGoal() instanceof FleeEntityGoal<?> && g.getGoal().getClass().getEnclosingClass() == Grace.class);
        ctx.assertTrue(flees, "creepers have the flee goal");
        creeper.discard();
        phantom.discard();
        zombie.discard();
        p.discard();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "passive_lifesteal")
    public void hittingMobsHeals(TestContext ctx) {
        ServerPlayerEntity p = survivor(ctx);
        ZombieEntity zombie = ctx.spawnEntity(EntityType.ZOMBIE, 2, 1, 2);
        p.setHealth(10f);
        zombie.damage(p.getDamageSources().playerAttack(p), 10f);
        ctx.assertEquals(10f, p.getHealth(), "no healing without the tome");

        p.getInventory().setStack(15, new ItemStack(Lifesteal.TOME_OF_LIFESTEAL));
        zombie.timeUntilRegen = 0;
        zombie.setHealth(zombie.getMaxHealth());
        zombie.damage(p.getDamageSources().playerAttack(p), 5f);
        float healed = p.getHealth() - 10f;
        ctx.assertTrue(healed > 0f && healed <= 5f * Lifesteal.SHARE + 0.01f, "a share of the hit comes back, " + healed);

        // Overkill only drains what the mob had
        float before = p.getHealth();
        Lifesteal.drain(p, zombie, 1000f);
        ctx.assertTrue(p.getHealth() - before <= zombie.getMaxHealth() * Lifesteal.SHARE + 0.01f, "overkill is capped");
        zombie.discard();
        p.discard();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "passive_knockback", tickLimit = 120)
    public void knockbackThrowsBackWhateverHurtsYouUpClose(TestContext ctx) {
        ServerPlayerEntity p = survivor(ctx);
        net.minecraft.util.math.Vec3d at = ctx.getAbsolute(new net.minecraft.util.math.Vec3d(2.5, 1, 2.5));
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
        ZombieEntity zombie = ctx.spawnEntity(EntityType.ZOMBIE, 2, 1, 4); // just south of them
        zombie.setAiDisabled(true);
        var golem = ctx.spawnEntity(EntityType.IRON_GOLEM, 4, 1, 2);
        var bite = p.getDamageSources().mobAttack(zombie);

        ctx.assertFalse(net.ragnar.ragnarsmagicmod.knockback.Knockback.retaliate(p, bite), "nothing without the tome");
        p.getInventory().setStack(18, new ItemStack(net.ragnar.ragnarsmagicmod.knockback.Knockback.TOME_OF_KNOCKBACK));

        ctx.assertTrue(net.ragnar.ragnarsmagicmod.knockback.Knockback.retaliate(p, bite), "a zombie's bite is thrown back");
        ctx.assertTrue(zombie.getVelocity().z > 0.5, "straight away from you, hard: " + zombie.getVelocity());
        ctx.assertFalse(net.ragnar.ragnarsmagicmod.knockback.Knockback.retaliate(p, p.getDamageSources().mobAttack(golem)),
                "an iron golem stands its ground");
        var skeleton = ctx.spawnEntity(EntityType.SKELETON, 1, 1, 1);
        var arrow = new net.minecraft.entity.projectile.ArrowEntity(EntityType.ARROW, ctx.getWorld());
        ctx.assertFalse(net.ragnar.ragnarsmagicmod.knockback.Knockback.retaliate(p, p.getDamageSources().arrow(arrow, skeleton)),
                "arrows from a distance don't count");

        ServerPlayerEntity other = ctx.createMockCreativeServerPlayerInWorld();
        other.refreshPositionAndAngles(at.x - 1.5, at.y, at.z, 0f, 0f);
        other.velocityModified = false;
        ctx.assertTrue(net.ragnar.ragnarsmagicmod.knockback.Knockback.retaliate(p, p.getDamageSources().playerAttack(other)),
                "players who hit you are thrown back too");
        ctx.assertTrue(other.velocityModified && other.getVelocity().x < -0.5, "and their client is told: " + other.getVelocity());

        // For real: once they can be hurt (3 seconds after joining), a husk's actual hit throws it back (a zombie would
        // have burnt up in the daylight by then)
        ctx.waitAndRun(70, () -> {
            var husk = ctx.spawnEntity(EntityType.HUSK, 2, 1, 4);
            husk.setAiDisabled(true);
            husk.refreshPositionAndAngles(at.x, at.y, at.z + 1.2, 180f, 0f);
            float before = p.getHealth();
            husk.tryAttack(p);
            ctx.assertTrue(p.getHealth() < before, "the husk's hit landed");
            ctx.assertTrue(husk.getVelocity().z > 0.5, "and it was thrown back: " + husk.getVelocity());
            for (var e : java.util.List.of(husk, zombie, golem, skeleton, other, p)) e.discard();
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "passive_ground_pound", tickLimit = 120)
    public void groundPoundHalvesTheFallAndBurstsUnlessItKills(TestContext ctx) {
        ServerPlayerEntity p = survivor(ctx);
        // A player who's just joined can't be hurt for 3 seconds: wait that out first
        ctx.waitAndRun(70, () -> poundChecks(ctx, p));
    }

    private static void poundChecks(TestContext ctx, ServerPlayerEntity p) {
        net.minecraft.util.math.Vec3d high = ctx.getAbsolute(new net.minecraft.util.math.Vec3d(3.5, 16, 3.5));
        net.minecraft.util.math.Vec3d low = ctx.getAbsolute(new net.minecraft.util.math.Vec3d(3.5, 1, 3.5));

        // Without the tome: nothing
        p.refreshPositionAndAngles(high.x, high.y, high.z, 0f, 0f);
        net.ragnar.ragnarsmagicmod.groundpound.GroundPound.start(p);
        ctx.assertFalse(net.ragnar.ragnarsmagicmod.groundpound.GroundPound.isPounding(p), "no pound without the tome");
        p.timeUntilRegen = 0; // a freshly spawned player shrugs off hits for a moment
        float full = p.getHealth();
        p.handleFallDamage(13f, 1f, p.getDamageSources().fall());
        float normalHurt = full - p.getHealth();
        ctx.assertTrue(normalHurt >= 9f, "a 13 block fall hurts normally: " + normalHurt);

        // With it: half the fall, then the burst catches the husk but not the cow or villager
        p.setHealth(p.getMaxHealth());
        p.timeUntilRegen = 0;
        p.getInventory().setStack(11, new ItemStack(net.ragnar.ragnarsmagicmod.groundpound.GroundPound.TOME_OF_GROUND_POUND));
        var husk = ctx.spawnEntity(EntityType.HUSK, 5, 1, 3);
        var cow = ctx.spawnEntity(EntityType.COW, 2, 1, 4);
        var villager = ctx.spawnEntity(EntityType.VILLAGER, 3, 1, 5);
        for (var m : java.util.List.of(husk, cow, villager)) ((net.minecraft.entity.mob.MobEntity) m).setAiDisabled(true);
        p.refreshPositionAndAngles(high.x, high.y, high.z, 0f, 0f);
        net.ragnar.ragnarsmagicmod.groundpound.GroundPound.start(p);
        ctx.assertTrue(net.ragnar.ragnarsmagicmod.groundpound.GroundPound.isPounding(p), "pounding");
        p.refreshPositionAndAngles(low.x, low.y, low.z, 0f, 0f);
        p.handleFallDamage(13f, 1f, p.getDamageSources().fall());
        float poundHurt = p.getMaxHealth() - p.getHealth();
        ctx.assertTrue(poundHurt > 0f && poundHurt <= normalHurt * 0.5f + 0.01f, "half the fall: " + poundHurt + " vs " + normalHurt);
        net.ragnar.ragnarsmagicmod.groundpound.GroundPound.land(p);
        ctx.assertFalse(net.ragnar.ragnarsmagicmod.groundpound.GroundPound.isPounding(p), "over once landed");
        ctx.assertTrue(husk.getHealth() < husk.getMaxHealth(), "the husk is caught in the burst");
        ctx.assertTrue(husk.getVelocity().x > 0.2 && husk.getVelocity().y > 0.1, "and flung out and up: " + husk.getVelocity());
        ctx.assertEquals(cow.getMaxHealth(), cow.getHealth(), "the cow is left alone");
        ctx.assertEquals(villager.getMaxHealth(), villager.getHealth(), "and the villager");

        // A pound whose fall kills you: no burst
        var husk2 = ctx.spawnEntity(EntityType.HUSK, 5, 1, 3);
        husk2.setAiDisabled(true);
        p.setHealth(2f);
        p.timeUntilRegen = 0;
        p.refreshPositionAndAngles(high.x, high.y, high.z, 0f, 0f);
        net.ragnar.ragnarsmagicmod.groundpound.GroundPound.start(p);
        p.refreshPositionAndAngles(low.x, low.y, low.z, 0f, 0f);
        p.handleFallDamage(13f, 1f, p.getDamageSources().fall());
        ctx.assertFalse(p.isAlive(), "the fall killed them");
        net.ragnar.ragnarsmagicmod.groundpound.GroundPound.land(p);
        ctx.assertEquals(husk2.getMaxHealth(), husk2.getHealth(), "so there's no burst");
        for (var e : java.util.List.of(husk, husk2, cow, villager, p)) e.discard();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "passive_desperation")
    public void desperationAtThreeHearts(TestContext ctx) {
        ServerPlayerEntity p = survivor(ctx);
        var damage = p.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_ATTACK_DAMAGE);
        var speed = p.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_MOVEMENT_SPEED);
        double baseDamage = damage.getValue(), baseSpeed = speed.getValue();
        p.setHealth(5f);
        net.ragnar.ragnarsmagicmod.desperation.Desperation.update(p);
        ctx.assertEquals(baseDamage, damage.getValue(), "nothing without the tome");

        p.getInventory().setStack(14, new ItemStack(net.ragnar.ragnarsmagicmod.desperation.Desperation.TOME_OF_DESPERATION));
        p.setHealth(10f);
        net.ragnar.ragnarsmagicmod.desperation.Desperation.update(p);
        ctx.assertEquals(baseDamage, damage.getValue(), "nothing at 5 hearts");
        p.setHealth(6f);
        net.ragnar.ragnarsmagicmod.desperation.Desperation.update(p);
        ctx.assertTrue(Math.abs(damage.getValue() - baseDamage * 1.5) < 1e-6, "half again the damage at 3 hearts: " + damage.getValue());
        ctx.assertTrue(Math.abs(speed.getValue() - baseSpeed * 1.3) < 1e-6, "and faster: " + speed.getValue());
        p.setHealth(15f);
        net.ragnar.ragnarsmagicmod.desperation.Desperation.update(p);
        ctx.assertEquals(baseDamage, damage.getValue(), "gone once healed");
        ctx.assertEquals(baseSpeed, speed.getValue(), "both");
        p.discard();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "passive_reflection")
    public void reflectionSendsItBackAtTheShooter(TestContext ctx) {
        ServerPlayerEntity p = survivor(ctx);
        net.minecraft.util.math.Vec3d at = ctx.getAbsolute(new net.minecraft.util.math.Vec3d(1.5, 1, 1.5));
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
        var skeleton = ctx.spawnEntity(EntityType.SKELETON, 1, 1, 7); // 6 blocks south
        skeleton.setAiDisabled(true);
        var arrow = new net.minecraft.entity.projectile.ArrowEntity(EntityType.ARROW, ctx.getWorld());
        arrow.setOwner(skeleton);
        arrow.refreshPositionAndAngles(at.x, at.y + 1.2, at.z + 0.6, 0f, 0f);
        arrow.setVelocity(0, 0, -1.5); // flying north into them
        ctx.getWorld().spawnEntity(arrow);

        ctx.assertFalse(net.ragnar.ragnarsmagicmod.reflection.Reflection.tryReflect(arrow, p, true), "nothing without the tome");
        p.getInventory().setStack(16, new ItemStack(net.ragnar.ragnarsmagicmod.reflection.Reflection.TOME_OF_REFLECTION));
        ctx.assertTrue(net.ragnar.ragnarsmagicmod.reflection.Reflection.tryReflect(arrow, p, true), "bounced");
        ctx.assertTrue(arrow.getOwner() == p, "it's theirs now");
        ctx.assertTrue(arrow.getVelocity().z > 1.0, "flying back south at the skeleton, faster: " + arrow.getVelocity());

        // Their own shots and ender pearls pass as normal
        ctx.assertFalse(net.ragnar.ragnarsmagicmod.reflection.Reflection.tryReflect(arrow, p, true), "not your own arrow");
        var pearl = new net.minecraft.entity.projectile.thrown.EnderPearlEntity(EntityType.ENDER_PEARL, ctx.getWorld());
        pearl.setOwner(skeleton);
        ctx.assertFalse(net.ragnar.ragnarsmagicmod.reflection.Reflection.tryReflect(pearl, p, true), "not an ender pearl");
        ctx.assertTrue(net.ragnar.ragnarsmagicmod.reflection.Reflection.CHANCE == 0.25f, "a 1 in 4 chance");
        for (var e : java.util.List.of(arrow, pearl, skeleton, p)) e.discard();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "passive_sidestep")
    public void sidestepNeedsTheTome(TestContext ctx) {
        ServerPlayerEntity p = survivor(ctx);
        ctx.assertFalse(net.ragnar.ragnarsmagicmod.sidestep.Sidestep.canSidestep(p), "not without the tome");
        p.getInventory().setStack(27, new ItemStack(net.ragnar.ragnarsmagicmod.sidestep.Sidestep.TOME_OF_SIDESTEP));
        ctx.assertTrue(net.ragnar.ragnarsmagicmod.sidestep.Sidestep.canSidestep(p), "with it");
        p.discard();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "passive_haste")
    public void hasteTwoWhileCarried(TestContext ctx) {
        ServerPlayerEntity p = survivor(ctx);
        net.ragnar.ragnarsmagicmod.haste.Haste.update(p);
        ctx.assertFalse(p.hasStatusEffect(StatusEffects.HASTE), "nothing without the tome");

        p.getInventory().setStack(33, new ItemStack(net.ragnar.ragnarsmagicmod.haste.Haste.TOME_OF_HASTE));
        net.ragnar.ragnarsmagicmod.haste.Haste.update(p);
        var haste = p.getStatusEffect(StatusEffects.HASTE);
        ctx.assertTrue(haste != null && haste.getAmplifier() == 1, "Haste II");
        float fast = p.getBlockBreakingSpeed(net.minecraft.block.Blocks.STONE.getDefaultState());

        p.getInventory().setStack(33, ItemStack.EMPTY);
        net.ragnar.ragnarsmagicmod.haste.Haste.update(p);
        ctx.assertFalse(p.hasStatusEffect(StatusEffects.HASTE), "gone with the tome");
        float slow = p.getBlockBreakingSpeed(net.minecraft.block.Blocks.STONE.getDefaultState());
        ctx.assertTrue(fast > slow * 1.35f, "mines noticeably faster: " + fast + " vs " + slow);

        // A weaker Haste from a potion is lifted to II; a beacon's own II is left alone
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.HASTE, 1200, 0));
        p.getInventory().setStack(33, new ItemStack(net.ragnar.ragnarsmagicmod.haste.Haste.TOME_OF_HASTE));
        net.ragnar.ragnarsmagicmod.haste.Haste.update(p);
        ctx.assertEquals(1, p.getStatusEffect(StatusEffects.HASTE).getAmplifier(), "Haste I lifted to II");
        p.discard();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "passive_gourmet")
    public void foodIsEatenInstantly(TestContext ctx) {
        ServerPlayerEntity p = survivor(ctx);
        ItemStack beef = new ItemStack(Items.COOKED_BEEF);
        ItemStack potion = new ItemStack(Items.POTION);
        int normal = beef.getMaxUseTime(p), potionTime = potion.getMaxUseTime(p);
        ctx.assertTrue(normal > 1, "takes a while without the tome");

        p.getInventory().setStack(9, new ItemStack(Gourmet.TOME_OF_THE_GOURMET));
        ctx.assertEquals(1, beef.getMaxUseTime(p), "one tick with it");
        ctx.assertEquals(1, new ItemStack(Items.GOLDEN_APPLE).getMaxUseTime(p), "any food");
        ctx.assertEquals(potionTime, potion.getMaxUseTime(p), "drinks are left alone");
        p.discard();
        ctx.complete();
    }
}
