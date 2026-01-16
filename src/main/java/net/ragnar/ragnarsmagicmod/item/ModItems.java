package net.ragnar.ragnarsmagicmod.item;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import java.util.EnumSet;
import java.util.EnumMap;
import java.util.Map;
import static net.ragnar.ragnarsmagicmod.RagnarsMagicMod.LOGGER;


public class ModItems {
    // Map: SpellId -> (Tier -> TomeItem)
    // Used for refunding the correct tome when unsocketing
    public static final Map<SpellId, Map<TomeTier, TomeItem>> TOMES = new EnumMap<>(SpellId.class);

    private static void putTome(SpellId id, TomeTier tier, TomeItem item) {
        TOMES.computeIfAbsent(id, k -> new EnumMap<>(TomeTier.class)).put(tier, item);
    }

    public static TomeItem getTomeFor(SpellId id, TomeTier tier) {
        Map<TomeTier, TomeItem> byTier = TOMES.get(id);
        return (byTier == null) ? null : byTier.get(tier);
    }

    public static final Item FALSE_TOME = registerItem("false_tome", new Item(new Item.Settings().maxCount(1)));

    // Tome(s)
    public static final TomeItem TOME_OF_FIREBALLS = (TomeItem) registerItem(
            "tome_of_fireballs",
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.UNCOMMON), TomeTier.BEGINNER, SpellId.FIREBALLS, 7)
    );
    public static final Item TOME_GHASTFIRE = registerItem("tome_ghastfire",
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE),
            TomeTier.ADVANCED, SpellId.GHAST_FIREBALL, 15
            ).setCooldown(40)
    );
    public static final Item TOME_ICE_SHARDS = registerItem("tome_ice_shards",
            new TomeItem(
                    new Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.UNCOMMON),
                    TomeTier.BEGINNER,
                    SpellId.ICE_SHARDS,
                    10 // XP cost
            ).setCooldown(30)
    );
    public static final Item TOME_FALLING_ANVILS = registerItem("tome_falling_anvils",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.RARE), // Advanced
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.ADVANCED,
                    SpellId.FALLING_ANVILS,
                    22 // XP cost (a bit pricier than others)
            ).setCooldown(60)
    );
    public static final Item TOME_METEOR = registerItem("tome_meteor",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.EPIC), // Master tier color
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.MASTER,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.METEOR,
                    80 // XP cost (big boom should cost more)
            ).setCooldown(200)
    );

    public static final Item TOME_FALLING_STALACTITE = registerItem("tome_falling_stalactite",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.UNCOMMON), // Beginner
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.BEGINNER,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.FALLING_STALACTITE,
                    6 // XP cost (cheap beginner)
            ).setCooldown(30)
    );

    public static final Item TOME_PUSHING = registerItem("tome_pushing",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.RARE), // Advanced
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.ADVANCED,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.WIND_PUSH,
                    12 // XP cost
            ).setCooldown(50)
    );

    public static final Item TOME_BLINKING = registerItem("tome_blinking",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.EPIC), // Master
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.MASTER,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.BLINK,
                    9 // XP cost
            ).setCooldown(30)
    );

    public static final Item TOME_RISING_SPIKES = registerItem("tome_rising_spikes",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.RARE), // Advanced
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.ADVANCED,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.RISING_SPIKES,
                    25 // XP cost
            ).setCooldown(60)
    );

    public static final Item TOME_DASHING = registerItem("tome_dashing",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.RARE), // Advanced
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.ADVANCED,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.DASH,
                    6
            ).setCooldown(25)
    );

    public static final Item TOME_CHARGED_WIND = registerItem("tome_charged_wind",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.UNCOMMON), // Beginner
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.BEGINNER,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.WIND_CHARGE,
                    10 // XP cost
            ).setCooldown(35)
    );

    public static final Item TOME_SKULLS = registerItem("tome_skulls",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.UNCOMMON), // Beginner tier
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.BEGINNER,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.WITHER_SKULL,
                    12 // a bit expensive for beginner
            ).setCooldown(40)
    );

    public static final Item TOME_ZAP = registerItem("tome_zap",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.RARE), // Advanced
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.ADVANCED,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.ZAP,
                    12 // XP cost
            ).setCooldown(25)
    );

    public static final net.minecraft.item.Item TOME_LIGHTNING = registerItem("tome_lightning",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new net.minecraft.item.Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.RARE), // Advanced
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.ADVANCED,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.LIGHTNING,
                    14 // XP cost
            ).setCooldown(60)
    );

    public static final net.minecraft.item.Item TOME_LIGHTNING_CASCADE = registerItem("tome_lightning_cascade",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new net.minecraft.item.Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.EPIC), // Master
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.MASTER,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.LIGHTNING_CASCADE,
                    50 // XP cost
            ).setCooldown(140)
    );

    public static final net.minecraft.item.Item TOME_ENERGY = registerItem("tome_energy",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new net.minecraft.item.Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.RARE), // Advanced
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.ADVANCED,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.ENERGY_ORB,
                    15 // XP cost
            ).setCooldown(50)
    );

    public static final net.minecraft.item.Item TOME_SONIC_BOOM = registerItem("tome_sonic_boom",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new net.minecraft.item.Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.EPIC), // Master
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.MASTER,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.SONIC_BOOM,
                    40 // XP cost (Master-tier)
            ).setCooldown(120)
    );

    public static final net.minecraft.item.Item TOME_REJUVENATION = registerItem("tome_rejuvenation",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new net.minecraft.item.Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.RARE), // Advanced
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.ADVANCED,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.REJUVENATION,
                    18 // XP cost
            ).setCooldown(100)
    );

    public static final net.minecraft.item.Item TOME_LIGHT = registerItem("tome_light",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new net.minecraft.item.Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.UNCOMMON), // Beginner
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.BEGINNER,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.LIGHT,
                    6 // cheap XP cost
            ).setCooldown(150)
    );

    public static final Item TOME_AEGIS = registerItem("tome_aegis",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new net.minecraft.item.Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.UNCOMMON), // Beginner
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.BEGINNER,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.AEGIS,
                    10 // XP cost
            ).setCooldown(120)
    );

    public static final net.minecraft.item.Item TOME_INSIGHT = registerItem("tome_insight",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new net.minecraft.item.Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.UNCOMMON), // Beginner
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.BEGINNER,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.INSIGHT,
                    8 // cheap XP cost
            ).setCooldown(100)
    );

    public static final net.minecraft.item.Item TOME_TRACKING = registerItem("tome_tracking",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new net.minecraft.item.Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.UNCOMMON), // Beginner
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.BEGINNER,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.TRACKING,
                    8 // cheap-mid XP
            ).setCooldown(17)
    );

    public static final net.minecraft.item.Item TOME_SUN = registerItem("tome_sun",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new net.minecraft.item.Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.EPIC), // Master
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.MASTER,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.SUN,
                    40 // high XP cost
            ).setCooldown(175)
    );

    public static final net.minecraft.item.Item TOME_MINING = registerItem("tome_mining",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new net.minecraft.item.Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.RARE), // Advanced
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.ADVANCED,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.MINING,
                    10 // XP cost
            ).setCooldown(20)
    );

    public static final net.minecraft.item.Item TOME_DRAGON_BREATH = registerItem("tome_dragon_breath",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new net.minecraft.item.Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.EPIC), // Master
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.MASTER,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.DRAGON_BREATH,
                    32 // strong & costly
            ).setCooldown(60)
    );

    public static final net.minecraft.item.Item TOME_GRAVITY = registerItem("tome_gravity",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new net.minecraft.item.Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.RARE), // Advanced
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.ADVANCED,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.GRAVITY,
                    10 // XP cost
            ).setCooldown(150)
    );

    public static final net.minecraft.item.Item TOME_ICE_BEAM = registerItem("tome_ice_beam",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new net.minecraft.item.Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.EPIC), // Master
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.MASTER,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.ICE_BEAM,
                    40 // XP cost; tune if you want
            ).setCooldown(150)
    );

    public static final net.minecraft.item.Item TOME_VORTEX = registerItem("tome_vortex",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new net.minecraft.item.Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.UNCOMMON), // Beginner
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.BEGINNER,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.VORTEX,
                    10 // XP cost
            ).setCooldown(120)
    );

    public static final Item TOME_PUSHBACK = registerItem("tome_pushback",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.UNCOMMON), // Beginner rarity
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.BEGINNER,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.PUSHBACK,
                    3 // XP cost
            ).setCooldown(20)
    );

    public static final Item TOME_RECALLING = registerItem("tome_recalling",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.RARE), // Advanced
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.ADVANCED,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.RECALLING,
                    40 // XP cost
            ).setCooldown(40)
    );

    public static final net.minecraft.item.Item TOME_FANGS = registerItem("tome_fangs",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new net.minecraft.item.Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.UNCOMMON), // Beginner
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.BEGINNER,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.FANGS,
                    8 // XP cost
            ).setCooldown(12)
    );

    public static final net.minecraft.item.Item TOME_GHOSTSTEP = registerItem("tome_ghoststep",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new net.minecraft.item.Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.RARE), // Advanced
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.ADVANCED,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.GHOSTSTEP,
                    4 // cheap XP cost
            ).setCooldown(40)
    );

    public static final net.minecraft.item.Item TOME_OF_STEVE = registerItem("tome_steve",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new net.minecraft.item.Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.RARE), // Advanced
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.ADVANCED,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.SUMMON_STEVE,
                    50 // Quite expensive
            ).setCooldown(120)
    );

    public static final net.minecraft.item.Item TOME_GROWTH = registerItem("tome_growth",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new net.minecraft.item.Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.UNCOMMON),
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.BEGINNER,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.GROWTH,
                    4
            ).setCooldown(10)
    );
    public static final net.minecraft.item.Item TOME_ARROW_VOLLEY = registerItem("tome_arrow_volley",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new net.minecraft.item.Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.UNCOMMON),
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.BEGINNER,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.ARROW_VOLLEY,
                    7
            ).setCooldown(50)
    );
    public static final net.minecraft.item.Item TOME_BOOMING = registerItem("tome_booming",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new net.minecraft.item.Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.EPIC), // Master Tier
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.MASTER,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.BOOMING,
                    40 // XP cost
            ).setCooldown(100) // 5 seconds
    );
    public static final Item TOME_OF_TORCHES = registerItem("tome_of_torches",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.UNCOMMON), // Beginner
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.BEGINNER,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.TORCHES,
                    3
            ).setCooldown(15) // Fast cast
    );
    public static final Item TOME_IMPALING = registerItem("tome_impaling",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.RARE), // Advanced
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.ADVANCED,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.IMPALING,
                    16 // XP cost
            ).setCooldown(80) // 4 seconds
    );
    public static final Item TOME_INVISIBILITY = registerItem("tome_invisibility",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.UNCOMMON), // Beginner
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.BEGINNER,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.INVISIBILITY,
                    12 // XP Cost
            ).setCooldown(320) // 16 seconds
    );
    public static final Item TOME_OF_THE_VOID = registerItem("tome_of_the_void",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.EPIC), // Master
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.MASTER,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.VOID,
                    50 // High XP Cost
            ).setCooldown(400) // 12s Cooldown
    );

    static {
        putTome(SpellId.FIREBALLS, TomeTier.BEGINNER, (TomeItem) TOME_OF_FIREBALLS);
        putTome(SpellId.GHAST_FIREBALL, TomeTier.ADVANCED, (TomeItem) TOME_GHASTFIRE);
        putTome(SpellId.ICE_SHARDS,    TomeTier.BEGINNER, (TomeItem) TOME_ICE_SHARDS);
        putTome(
                SpellId.FALLING_ANVILS,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.ADVANCED,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_FALLING_ANVILS
        );
        putTome(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.METEOR,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.MASTER,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_METEOR
        );
        putTome(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.FALLING_STALACTITE,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.BEGINNER,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_FALLING_STALACTITE
        );
        putTome(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.WIND_PUSH,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.ADVANCED,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_PUSHING
        );
        putTome(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.BLINK,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.MASTER,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_BLINKING
        );
        putTome(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.RISING_SPIKES,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.ADVANCED,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_RISING_SPIKES
        );
        putTome(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.DASH,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.ADVANCED,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_DASHING
        );
        putTome(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.WIND_CHARGE,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.BEGINNER,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_CHARGED_WIND
        );
        putTome(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.WITHER_SKULL,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.BEGINNER,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_SKULLS
        );
        putTome(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.ZAP,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.ADVANCED,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_ZAP
        );
        putTome(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.LIGHTNING,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.ADVANCED,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_LIGHTNING
        );
        putTome(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.LIGHTNING_CASCADE,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.MASTER,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_LIGHTNING_CASCADE
        );
        putTome(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.ENERGY_ORB,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.ADVANCED,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_ENERGY
        );
        putTome(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.SONIC_BOOM,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.MASTER,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_SONIC_BOOM
        );
        putTome(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.REJUVENATION,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.ADVANCED,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_REJUVENATION
        );
        putTome(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.LIGHT,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.BEGINNER,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_LIGHT
        );
        putTome(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.AEGIS,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.BEGINNER,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_AEGIS
        );
        putTome(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.INSIGHT,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.BEGINNER,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_INSIGHT
        );
        putTome(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.TRACKING,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.BEGINNER,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_TRACKING
        );
        putTome(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.SUN,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.MASTER,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_SUN
        );
        putTome(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.MINING,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.ADVANCED,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_MINING
        );
        putTome(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.DRAGON_BREATH,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.MASTER,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_DRAGON_BREATH
        );
        putTome(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.GRAVITY,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.ADVANCED,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_GRAVITY
        );
        putTome(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.ICE_BEAM,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.MASTER,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_ICE_BEAM
        );
        putTome(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.VORTEX,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.BEGINNER,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_VORTEX
        );
        putTome(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.PUSHBACK,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.BEGINNER,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_PUSHBACK
        );
        putTome(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.RECALLING,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.ADVANCED,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_RECALLING
        );
        putTome(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.FANGS,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.BEGINNER,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_FANGS
        );
        putTome(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.GHOSTSTEP,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.ADVANCED,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_GHOSTSTEP
        );
        putTome(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.SUMMON_STEVE,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.ADVANCED,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_OF_STEVE
        );
        putTome(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.GROWTH,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.BEGINNER,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_GROWTH
        );
        putTome(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.ARROW_VOLLEY,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.BEGINNER,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_ARROW_VOLLEY
        );
        putTome(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.BOOMING,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.MASTER,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_BOOMING
        );
        putTome(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.TORCHES,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.BEGINNER,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_OF_TORCHES
        );
        putTome(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.IMPALING,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.ADVANCED,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_IMPALING
        );
        putTome(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.INVISIBILITY,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.BEGINNER,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_INVISIBILITY
        );
        putTome(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.VOID,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.MASTER,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_OF_THE_VOID
        );



    }

    // Staffs (use StaffItem now)
    public static final Item GOLDEN_STAFF = registerItem(
            "golden_staff",
            new StaffItem(new Item.Settings().maxDamage(128).rarity(Rarity.UNCOMMON),
                    EnumSet.of(TomeTier.BEGINNER))
    );
    public static final Item DIAMOND_STAFF = registerItem(
            "diamond_staff",
            new StaffItem(new Item.Settings().maxDamage(384).rarity(Rarity.RARE),
                    EnumSet.of(TomeTier.BEGINNER, TomeTier.ADVANCED))
    );
    public static final Item NETHERITE_STAFF = registerItem(
            "netherite_staff",
            new StaffItem(new Item.Settings().maxDamage(2031).rarity(Rarity.EPIC).fireproof(),
                    EnumSet.of(TomeTier.BEGINNER, TomeTier.ADVANCED, TomeTier.MASTER))
    );

    //other stuff
    public static final Item ICE_SHARD_ITEM = registerItem("ice_shard",
            new Item(new Item.Settings().maxCount(16)));


    private static Item registerItem(String name, Item item) {
        return Registry.register(Registries.ITEM, Identifier.of(RagnarsMagicMod.MOD_ID, name), item);
    }

    public static void registerModItems() {
        RagnarsMagicMod.LOGGER.info("Registering Mod Items for " + RagnarsMagicMod.MOD_ID);

        // add to creative tabs
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.COMBAT).register(entries -> {
            entries.add(GOLDEN_STAFF);
            entries.add(DIAMOND_STAFF);
            entries.add(NETHERITE_STAFF);
        });
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> {
            entries.add(FALSE_TOME);
            entries.add(TOME_OF_FIREBALLS);
            entries.add(TOME_GHASTFIRE);
            entries.add(new ItemStack(TOME_ICE_SHARDS));
            entries.add(new net.minecraft.item.ItemStack(TOME_FALLING_ANVILS));
            entries.add(new net.minecraft.item.ItemStack(TOME_METEOR));
            entries.add(new net.minecraft.item.ItemStack(TOME_FALLING_STALACTITE));
            entries.add(new net.minecraft.item.ItemStack(TOME_PUSHING));
            entries.add(new net.minecraft.item.ItemStack(TOME_BLINKING));
            entries.add(new net.minecraft.item.ItemStack(TOME_RISING_SPIKES));
            entries.add(new net.minecraft.item.ItemStack(TOME_DASHING));
            entries.add(new net.minecraft.item.ItemStack(TOME_CHARGED_WIND));
            entries.add(new net.minecraft.item.ItemStack(TOME_SKULLS));
            entries.add(new net.minecraft.item.ItemStack(TOME_ZAP));
            entries.add(new net.minecraft.item.ItemStack(TOME_LIGHTNING));
            entries.add(new net.minecraft.item.ItemStack(TOME_LIGHTNING_CASCADE));
            entries.add(new net.minecraft.item.ItemStack(TOME_ENERGY));
            entries.add(new net.minecraft.item.ItemStack(TOME_SONIC_BOOM));
            entries.add(new net.minecraft.item.ItemStack(TOME_REJUVENATION));
            entries.add(new net.minecraft.item.ItemStack(TOME_LIGHT));
            entries.add(new net.minecraft.item.ItemStack(TOME_AEGIS));
            entries.add(new net.minecraft.item.ItemStack(TOME_INSIGHT));
            entries.add(new net.minecraft.item.ItemStack(TOME_TRACKING));
            entries.add(new net.minecraft.item.ItemStack(TOME_SUN));
            entries.add(new net.minecraft.item.ItemStack(TOME_MINING));
            entries.add(new net.minecraft.item.ItemStack(TOME_DRAGON_BREATH));
            entries.add(new net.minecraft.item.ItemStack(TOME_GRAVITY));
            entries.add(new net.minecraft.item.ItemStack(TOME_ICE_BEAM));
            entries.add(new net.minecraft.item.ItemStack(TOME_VORTEX));
            entries.add(new net.minecraft.item.ItemStack(TOME_PUSHBACK));
            entries.add(new net.minecraft.item.ItemStack(TOME_RECALLING));
            entries.add(new net.minecraft.item.ItemStack(TOME_FANGS));
            entries.add(new net.minecraft.item.ItemStack(TOME_GHOSTSTEP));
            entries.add(new net.minecraft.item.ItemStack(TOME_OF_STEVE));
            entries.add(new net.minecraft.item.ItemStack(TOME_GROWTH));
            entries.add(new net.minecraft.item.ItemStack(TOME_ARROW_VOLLEY));
            entries.add(new net.minecraft.item.ItemStack(TOME_BOOMING));
            entries.add(new net.minecraft.item.ItemStack(TOME_OF_TORCHES));
            entries.add(new net.minecraft.item.ItemStack(TOME_IMPALING));
            entries.add(new net.minecraft.item.ItemStack(TOME_INVISIBILITY));
            entries.add(new net.minecraft.item.ItemStack(TOME_OF_THE_VOID));
        });
    }
}
