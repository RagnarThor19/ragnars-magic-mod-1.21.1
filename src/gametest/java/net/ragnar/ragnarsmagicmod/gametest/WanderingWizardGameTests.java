package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.passive.HorseEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.wizard.WanderingWizard;
import net.ragnar.ragnarsmagicmod.wizard.WanderingWizardEntity;
import net.ragnar.ragnarsmagicmod.wizard.WizardSpawner;
import net.ragnar.ragnarsmagicmod.wizard.WizardSpell;
import net.ragnar.ragnarsmagicmod.wizard.WizardTrades;

import java.util.Arrays;
import java.util.List;

/** The Wandering Wizard: his trades, his tome and horse, how he fights back, and that his spells hit softer. */
public class WanderingWizardGameTests implements FabricGameTest {

    private static void floor(TestContext ctx) {
        for (int x = 0; x <= 7; x++) {
            for (int z = 0; z <= 7; z++) ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
        }
    }

    private static WanderingWizardEntity wizard(TestContext ctx, BlockPos at) {
        return WanderingWizard.WANDERING_WIZARD.spawn(ctx.getWorld(), ctx.getAbsolutePos(at), SpawnReason.SPAWN_EGG);
    }

    private static int rank(TomeTier tier) {
        return switch (tier) { case BEGINNER -> 0; case ADVANCED -> 1; case MASTER -> 2; default -> -10; };
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void tradesAreTomeSwapsByTheRules(TestContext ctx) {
        Random random = Random.create(42);
        int ups = 0, swaps = 0, passives = 0;
        for (int round = 0; round < 300; round++) {
            TradeOfferList offers = new TradeOfferList();
            WizardTrades.fill(offers, random);
            ctx.assertEquals(WizardTrades.TRADES, offers.size(), "always 10 trades");
            int masters = 0;
            for (TradeOffer offer : offers) {
                ctx.assertTrue(offer.getFirstBuyItem().itemStack().getItem() instanceof TomeItem, "pay with a tome");
                ctx.assertTrue(offer.getSecondBuyItem().isEmpty(), "and nothing else");
                ctx.assertTrue(offer.getSellItem().getItem() instanceof TomeItem, "get a tome");
                ctx.assertEquals(1, offer.getMaxUses(), "once each");
                TomeItem give = (TomeItem) offer.getFirstBuyItem().itemStack().getItem();
                TomeItem get = (TomeItem) offer.getSellItem().getItem();
                ctx.assertFalse(give == get, "never the same tome back");
                if (give.getTier() == TomeTier.PASSIVE || get.getTier() == TomeTier.PASSIVE) {
                    ctx.assertTrue(give.getTier() == get.getTier(), "passive only ever for passive");
                    passives++;
                    continue;
                }
                int step = rank(get.getTier()) - rank(give.getTier());
                ctx.assertTrue(step == 0 || step == 1, "same tier or one up, got " + give.getTier() + " -> " + get.getTier());
                if (give.getTier() == TomeTier.MASTER) {
                    masters++;
                    ctx.assertEquals(TomeTier.MASTER, get.getTier(), "master for master");
                }
                if (give.getTier() != TomeTier.MASTER) {
                    swaps++;
                    if (step == 1) ups++;
                }
            }
            ctx.assertTrue(masters <= WizardTrades.MAX_MASTER_TRADES, "at most 2 master trades, got " + masters);
        }
        double share = ups / (double) swaps;
        ctx.assertTrue(share > 0.06 && share < 0.14, "about 1 in 10 trade up a tier, got " + share);
        ctx.assertTrue(passives > 0, "passive tomes are traded too");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 40)
    public void comesWithATomeAndAnArmouredHorse(TestContext ctx) {
        floor(ctx);
        WanderingWizardEntity w = wizard(ctx, new BlockPos(3, 1, 3));
        ctx.assertTrue(w != null, "spawned");
        ctx.assertEquals(WizardTrades.TRADES, w.getOffers().size(), "10 trades");
        List<?> tomes = Arrays.stream(WizardSpell.values()).map(WizardSpell::tome).toList();
        ctx.assertTrue(tomes.contains(w.getMainHandStack().getItem()), "holds one of his tomes");
        ctx.assertEquals(w.getSpell().tome(), w.getMainHandStack().getItem(), "the one he casts");
        ctx.runAtTick(5, () -> {
            List<HorseEntity> horses = ctx.getWorld().getEntitiesByClass(HorseEntity.class, w.getBoundingBox().expand(8), h -> h.getLeashHolder() == w);
            ctx.assertEquals(1, horses.size(), "one horse on his lead");
            HorseEntity h = horses.get(0);
            ctx.assertTrue(h.isTame(), "tame");
            ctx.assertTrue(h.isSaddled(), "saddled");
            var armor = h.getBodyArmor();
            ctx.assertTrue(armor.isOf(Items.LEATHER_HORSE_ARMOR), "leather armour");
            ctx.assertTrue(armor.contains(net.minecraft.component.DataComponentTypes.DYED_COLOR), "dyed");
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 40)
    public void hisHorseLeavesWithHim(TestContext ctx) {
        floor(ctx);
        WanderingWizardEntity w = wizard(ctx, new BlockPos(3, 1, 3));
        ctx.runAtTick(5, () -> w.setDespawnDelay(2));
        ctx.runAtTick(15, () -> {
            ctx.assertTrue(w.isRemoved(), "his time was up");
            ctx.assertTrue(ctx.getWorld().getEntitiesByClass(HorseEntity.class, w.getBoundingBox().expand(8), h -> true).isEmpty(), "the horse went too");
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void hisSpellsDoHalfDamage(TestContext ctx) {
        floor(ctx);
        WanderingWizardEntity w = wizard(ctx, new BlockPos(1, 1, 1));
        VillagerEntity v = ctx.spawnEntity(EntityType.VILLAGER, new BlockPos(5, 1, 5));
        v.setAiDisabled(true);
        float before = v.getHealth();
        v.damage(ctx.getWorld().getDamageSources().mobAttack(w), 6f);
        ctx.assertTrue(Math.abs(before - v.getHealth() - 6f * WanderingWizardEntity.SPELL_DAMAGE_SCALE) < 0.01f,
                "6 damage becomes 3, took " + (before - v.getHealth()));
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 200)
    public void fightsBackWhenAMonsterHitsHim(TestContext ctx) {
        floor(ctx);
        WanderingWizardEntity w = wizard(ctx, new BlockPos(0, 1, 0));
        w.setSpell(WizardSpell.ZAP);
        ZombieEntity z = ctx.spawnEntity(EntityType.HUSK, // a husk: a zombie that doesn't burn in daylight
                new BlockPos(7, 1, 7));
        z.setAiDisabled(true);
        float before = z.getHealth();
        w.damage(ctx.getWorld().getDamageSources().mobAttack(z), 1f);
        ctx.assertTrue(w.getTarget() == z, "he's fighting it");
        // Long enough for two zaps (a zap can now and then go wide)
        ctx.waitAndRun(150, () -> {
            float lost = before - z.getHealth();
            ctx.assertTrue(lost > 0f, "the zombie got zapped");
            ctx.assertTrue(lost <= 2 * 9f * WanderingWizardEntity.SPELL_DAMAGE_SCALE + 0.01f, "soft zaps, lost " + lost);
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void twiceAsRareAsTheTrader(TestContext ctx) {
        ctx.assertEquals(20, WizardSpawner.ONE_IN, "the trader's last roll is 1 in 10, his is 1 in 20");
        ctx.assertEquals(48000, WizardSpawner.STAY, "stays two days, like the trader");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void carriesThreeDifferentTomesAndThunderstrikeIsRare(TestContext ctx) {
        Random random = Random.create(7);
        int thunder = 0, rolls = 4000;
        for (int i = 0; i < rolls; i++) {
            List<WizardSpell> l = WanderingWizardEntity.rollLoadout(random);
            ctx.assertEquals(3, l.size(), "three tomes");
            ctx.assertEquals(3L, l.stream().distinct().count(), "all different: " + l);
            ctx.assertTrue(l.stream().filter(WizardSpell::sleeps).count() <= 1, "at most one sleep spell: " + l);
            if (l.contains(WizardSpell.THUNDERSTRIKE)) thunder++;
        }
        double share = thunder / (double) rolls;
        ctx.assertTrue(share > 0.02 && share < 0.065, "about one wizard in 25 has Thunderstrike, got " + share);
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void neverCastsTheSameSpellTwiceRunning(TestContext ctx) {
        floor(ctx);
        WanderingWizardEntity w = wizard(ctx, new BlockPos(3, 1, 3));
        w.setSpells(List.of(WizardSpell.FIREBALLS, WizardSpell.ZAP, WizardSpell.COBWEBS));
        WizardSpell last = null;
        java.util.Set<WizardSpell> seen = java.util.EnumSet.noneOf(WizardSpell.class);
        for (int i = 0; i < 300; i++) {
            WizardSpell pick = w.chooseNext(null);
            ctx.assertTrue(pick != null && pick != last, "a different spell each time, got " + last + " then " + pick);
            seen.add(pick);
            w.markCast(pick);
            last = pick;
        }
        ctx.assertEquals(3, seen.size(), "uses all three");

        // The big ones need a rest as well: with both still recovering and the third just cast, he waits
        w.setSpells(List.of(WizardSpell.STONE_CANNON, WizardSpell.IMPALING, WizardSpell.ROCKS));
        w.markCast(WizardSpell.STONE_CANNON);
        w.markCast(WizardSpell.IMPALING);
        ctx.assertEquals(WizardSpell.ROCKS, w.chooseNext(null), "only the rocks are ready");
        w.markCast(WizardSpell.ROCKS);
        ctx.assertTrue(w.chooseNext(null) == null, "rather than repeat a spell, he waits");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void wontKeepPuttingTheSameOneToSleep(TestContext ctx) {
        floor(ctx);
        WanderingWizardEntity w = wizard(ctx, new BlockPos(1, 1, 1));
        VillagerEntity v = ctx.spawnEntity(EntityType.VILLAGER, new BlockPos(5, 1, 5));
        v.setAiDisabled(true);
        w.setSpells(List.of(WizardSpell.SLEEP_DARTS, WizardSpell.FIREBALLS, WizardSpell.ZAP));
        boolean sleepOffered = false;
        for (int i = 0; i < 100; i++) {
            WizardSpell pick = w.chooseNext(v);
            if (pick != null && pick.sleeps()) sleepOffered = true;
            if (pick != null) w.markCast(pick);
        }
        ctx.assertTrue(sleepOffered, "normally he does use his sleep spell");
        w.markSlept(v);
        ctx.assertTrue(w.sleepGuarded(v), "guarded for a minute after it fell asleep");
        for (int i = 0; i < 100; i++) {
            WizardSpell pick = w.chooseNext(v);
            ctx.assertFalse(pick != null && pick.sleeps(), "no sleep spell on it again so soon");
            if (pick != null) w.markCast(pick);
        }
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 80)
    public void leavesASleeperAlone(TestContext ctx) {
        floor(ctx);
        WanderingWizardEntity w = wizard(ctx, new BlockPos(0, 1, 0));
        w.setSpell(WizardSpell.ZAP);
        ZombieEntity z = ctx.spawnEntity(EntityType.HUSK, new BlockPos(7, 1, 7));
        z.setAiDisabled(true);
        w.damage(ctx.getWorld().getDamageSources().mobAttack(z), 0.01f);
        ctx.assertTrue(w.getTarget() == z, "fighting it");
        net.ragnar.ragnarsmagicmod.sleep.Sleep.putToSleep(ctx.getWorld(), z);
        ctx.waitAndRun(3, () -> {
            ctx.assertTrue(w.getTarget() == null, "he stops as soon as it nods off");
            ctx.assertTrue(w.sleepGuarded(z), "and won't put it to sleep again for a while");
            ctx.complete();
        });
    }
}
