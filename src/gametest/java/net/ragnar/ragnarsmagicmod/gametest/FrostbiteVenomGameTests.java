package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.ElderGuardianEntity;
import net.minecraft.entity.mob.WitherSkeletonEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.frostbite.Frostbite;
import net.ragnar.ragnarsmagicmod.venom.Venom;

/** Tomes of Frostbite and Venom: who can be frozen or poisoned, and how long it lasts. */
public class FrostbiteVenomGameTests implements FabricGameTest {

    private static ServerPlayerEntity survivor(TestContext ctx) {
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        p.getInventory().clear();
        return p;
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "passive_frostbite", tickLimit = 120)
    public void frostbiteFreezesOrdinaryMobsForABit(TestContext ctx) {
        ServerPlayerEntity p = survivor(ctx);
        var zombie = ctx.spawnEntity(EntityType.HUSK, 2, 1, 2);
        var stray = ctx.spawnEntity(EntityType.STRAY, 3, 1, 3);
        ElderGuardianEntity elder = ctx.spawnEntity(EntityType.ELDER_GUARDIAN, 1, 1, 3);
        elder.setAiDisabled(true);

        ctx.assertTrue(Frostbite.isHit(p.getDamageSources().playerAttack(p)), "a punch counts as a hit");
        ctx.assertFalse(Frostbite.isHit(p.getDamageSources().magic()), "poison doesn't");
        ctx.assertFalse(Frostbite.isHit(p.getDamageSources().freeze()), "nor does frost");

        ctx.assertFalse(Frostbite.freeze(stray, p), "strays don't freeze");
        ctx.assertFalse(Frostbite.freeze(elder, p), "bosses don't freeze");
        float health = zombie.getHealth();
        ctx.assertTrue(Frostbite.freeze(zombie, p), "a zombie freezes");
        ctx.assertTrue(Frostbite.isFrozen(zombie), "and stays frozen");
        ctx.assertFalse(Frostbite.freeze(zombie, p), "can't be frozen twice at once");

        // Nothing freezes in the Nether
        ServerWorld nether = ctx.getWorld().getServer().getWorld(World.NETHER);
        ZombieEntity hot = EntityType.ZOMBIE.create(nether);
        hot.refreshPositionAndAngles(0.5, 70, 0.5, 0f, 0f);
        nether.spawnEntity(hot);
        ctx.assertFalse(Frostbite.freeze(hot, p), "no freezing in the Nether");
        hot.discard();

        ctx.runAtTick(Frostbite.FROZEN_TICKS + 3, () -> {
            ctx.assertFalse(Frostbite.isFrozen(zombie), "thawed after a second and a half");
            ctx.assertTrue(zombie.getHealth() < health, "the frost bit it: " + zombie.getHealth());
            ctx.assertFalse(Frostbite.freeze(zombie, p), "can't be refrozen straight away");
        });
        ctx.runAtTick(Frostbite.FROZEN_TICKS + Frostbite.REFREEZE_TICKS + 6, () -> {
            ctx.assertTrue(Frostbite.freeze(zombie, p), "but can again a little later");
            zombie.discard();
            stray.discard();
            elder.discard();
            p.discard();
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "passive_venom", tickLimit = 120)
    public void venomPoisonsEvenTheUndeadButNeverKills(TestContext ctx) {
        PigEntity pig = ctx.spawnEntity(EntityType.PIG, 2, 1, 2);
        pig.setAiDisabled(true);
        WitherSkeletonEntity skeleton = ctx.spawnEntity(EntityType.WITHER_SKELETON, 3, 1, 3);
        skeleton.setAiDisabled(true);
        WitherSkeletonEntity weak = ctx.spawnEntity(EntityType.WITHER_SKELETON, 1, 1, 3);
        weak.setAiDisabled(true);
        weak.setHealth(1.0f);

        ctx.assertTrue(Venom.poison(pig), "a pig is poisoned");
        ctx.assertTrue(pig.hasStatusEffect(StatusEffects.POISON), "with vanilla poison");
        ctx.assertTrue(Venom.poison(skeleton), "so is a wither skeleton");
        ctx.assertFalse(skeleton.hasStatusEffect(StatusEffects.POISON), "which can't take vanilla poison");
        ctx.assertTrue(Venom.poison(weak), "so is a weak one");
        float health = skeleton.getHealth();

        ctx.runAtTick(Venom.DURATION / 2, () -> {
            ctx.assertTrue(Venom.isPoisoned(skeleton), "still poisoned");
            ctx.assertTrue(skeleton.getHealth() < health, "the venom hurts the undead: " + skeleton.getHealth());
            ctx.assertTrue(weak.isAlive() && weak.getHealth() >= 1.0f, "but never kills");
        });
        ctx.runAtTick(Venom.DURATION + 3, () -> {
            ctx.assertFalse(Venom.isPoisoned(skeleton), "it wears off");
            pig.discard();
            skeleton.discard();
            weak.discard();
            ctx.complete();
        });
    }
}
