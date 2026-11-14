package net.ragnar.ragnarsmagicmod.wizard;

import net.minecraft.item.ItemStack;
import net.minecraft.util.math.random.Random;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;
import net.minecraft.village.TradedItem;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Wandering Wizard's trades: always {@link #TRADES} tome swaps - give him a tome, get a different tome of the same
 * tier back. Each swap has a {@link #TIER_UP_CHANCE} chance of handing back a tome one tier up instead (a Master tome
 * never goes higher, and a passive tome is only ever swapped for another passive one), and at most
 * {@link #MAX_MASTER_TRADES} of them take a Master tome. Each can be made once. The tomes are the same ones that turn
 * up as loot.
 */
public final class WizardTrades {
    private WizardTrades() {}

    public static final int TRADES = 10;
    public static final float TIER_UP_CHANCE = 0.10f;
    public static final int MAX_MASTER_TRADES = 2;
    /** How the tier of each swap is picked: mostly Beginner, some Advanced, now and then Master or passive. */
    private static final int BEGINNER_WEIGHT = 40, ADVANCED_WEIGHT = 30, MASTER_WEIGHT = 15, PASSIVE_WEIGHT = 15;

    public static void fill(TradeOfferList offers, Random random) {
        Map<TomeTier, List<TomeItem>> pools = pools();
        Set<String> seen = new HashSet<>();
        int masters = 0;
        for (int i = 0; i < TRADES; i++) {
            for (int attempt = 0; attempt < 30; attempt++) {
                TomeTier tier = rollTier(random, masters < MAX_MASTER_TRADES);
                List<TomeItem> pool = pools.get(tier);
                if (pool.size() < 2) continue;
                TomeItem give = pool.get(random.nextInt(pool.size()));

                boolean canGoUp = tier == TomeTier.BEGINNER || tier == TomeTier.ADVANCED;
                TomeTier outTier = canGoUp && random.nextFloat() < TIER_UP_CHANCE ? up(tier) : tier;
                List<TomeItem> outPool = new ArrayList<>(pools.get(outTier));
                outPool.remove(give);
                if (outPool.isEmpty()) continue;
                TomeItem get = outPool.get(random.nextInt(outPool.size()));

                // The same swap twice would be a wasted slot
                if (!seen.add(give + ">" + get)) continue;
                offers.add(new TradeOffer(new TradedItem(give), new ItemStack(get), 1, 1, 0f));
                if (tier == TomeTier.MASTER) masters++;
                break;
            }
        }
    }

    private static TomeTier rollTier(Random random, boolean masterAllowed) {
        int master = masterAllowed ? MASTER_WEIGHT : 0;
        int r = random.nextInt(BEGINNER_WEIGHT + ADVANCED_WEIGHT + PASSIVE_WEIGHT + master);
        if (r < BEGINNER_WEIGHT) return TomeTier.BEGINNER;
        r -= BEGINNER_WEIGHT;
        if (r < ADVANCED_WEIGHT) return TomeTier.ADVANCED;
        r -= ADVANCED_WEIGHT;
        if (r < PASSIVE_WEIGHT) return TomeTier.PASSIVE;
        return TomeTier.MASTER;
    }

    private static TomeTier up(TomeTier tier) {
        return tier == TomeTier.BEGINNER ? TomeTier.ADVANCED : TomeTier.MASTER;
    }

    /** Every tome, by tier. */
    public static Map<TomeTier, List<TomeItem>> pools() {
        Map<TomeTier, List<TomeItem>> pools = new EnumMap<>(TomeTier.class);
        for (TomeTier tier : TomeTier.values()) pools.put(tier, new ArrayList<>());
        for (Map<TomeTier, TomeItem> byTier : ModItems.TOMES.values()) {
            byTier.forEach((tier, tome) -> {
                List<TomeItem> pool = pools.get(tier);
                if (pool != null && !pool.contains(tome)) pool.add(tome);
            });
        }
        return pools;
    }
}
