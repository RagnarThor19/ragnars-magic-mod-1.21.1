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
                    8 // XP cost
            ).setCooldown(100)
    );

    public static final net.minecraft.item.Item TOME_TRACKING = registerItem("tome_tracking",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new net.minecraft.item.Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.UNCOMMON), // Beginner
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.BEGINNER,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.TRACKING,
                    8 // XP
            ).setCooldown(17)
    );

    public static final net.minecraft.item.Item TOME_SUN = registerItem("tome_sun",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new net.minecraft.item.Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.EPIC), // Master
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.MASTER,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.SUN,
                    40 // XP cost
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
                    32 //
            ).setCooldown(160) // 8s; the breath itself lasts ~3.6s
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
                    40 // XP cost;
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
                    35 // XP cost
            ).setCooldown(110) // 6 seconds
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
            ).setCooldown(120) // 6 seconds
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
                    30 // High XP Cost
            ).setCooldown(260) // 13s Cooldown
    );
    public static final Item TOME_FREEZING = registerItem("tome_freezing",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.EPIC), // Master
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.MASTER,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.FREEZING,
                    50 // XP Cost
            ).setCooldown(360) // 18s Cooldown
    );
    public static final Item TOME_RAINING_ARROWS = registerItem("tome_raining_arrows",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.RARE), // Advanced
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.ADVANCED,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.RAINING_ARROWS,
                    12 // XP Cost
            ).setCooldown(120) // 6s Cooldown
    );
    public static final Item TOME_RANDOMNESS = registerItem("tome_randomness",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.RARE), // Advanced
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.ADVANCED,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.RANDOMNESS,
                    10 // Cheap XP
            ).setCooldown(50)
    );
    public static final Item TOME_OF_BOULDERS = registerItem("tome_of_boulders",
            new TomeItem(
                    new Item.Settings().maxCount(1).rarity(Rarity.UNCOMMON), // begginner
                    TomeTier.BEGINNER,
                    SpellId.BOULDER,
                    12 // XP Cost
            ).setCooldown(80) // 4 seconds
    );
    public static final Item TOME_SWAPPING = registerItem("tome_swapping",
            new TomeItem(
                    new Item.Settings().maxCount(1).rarity(Rarity.UNCOMMON), // Beginner/Uncommon
                    TomeTier.BEGINNER,
                    SpellId.SWAP,
                    10 // XP Cost
            ).setCooldown(200) // 10 seconds
    );
    public static final Item TOME_OF_CLOUDS = registerItem("tome_of_clouds",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new net.minecraft.item.Item.Settings().maxCount(1).rarity(Rarity.EPIC), // Master Tier
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.MASTER,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.CLOUDS,
                    30 // XP cost
            ).setCooldown(660) // 33 sec
    );
    public static final Item TOME_OF_SMASHING = registerItem("tome_of_smashing",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new net.minecraft.item.Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.RARE), // Advanced
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.ADVANCED,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.SMASH,
                    16 // XP Cost
            ).setCooldown(100) // 5 seconds
    );
    public static final TomeItem TOME_OF_FELLING = (TomeItem) registerItem("tome_of_felling",
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Advanced
                    TomeTier.ADVANCED, SpellId.FELLING, 10
            ).setCooldown(30) // 1.5 seconds
    );
    public static final TomeItem TOME_OF_IGNITION = (TomeItem) registerItem("tome_of_ignition",
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Advanced
                    TomeTier.ADVANCED, SpellId.IGNITION, 14
            ).setCooldown(100) // 5 seconds
    );
    public static final TomeItem TOME_OF_THE_DEAD = (TomeItem) registerItem("tome_of_the_dead",
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Advanced
                    TomeTier.ADVANCED, SpellId.DEAD, 18
            ).setCooldown(200) // 10 seconds, summons last 8
    );
    public static final TomeItem TOME_OF_SWORDS = (TomeItem) registerItem("tome_of_swords",
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC), // Master
                    TomeTier.MASTER, SpellId.SWORDS, 45 // paid once, when the swords are summoned
            ).setCooldown(20 * 50) // 50s, starts after the fifth sword is fired
    );
    public static final TomeItem TOME_OF_LEVITATION = (TomeItem) registerItem("tome_of_levitation",
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.UNCOMMON), // Beginner
                    TomeTier.BEGINNER, SpellId.LEVITATION, 6
            ).setCooldown(60) // 3 seconds
    );
    public static final TomeItem TOME_OF_TELEKINESIS = (TomeItem) registerItem("tome_of_telekinesis",
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC), // Master
                    TomeTier.MASTER, SpellId.TELEKINESIS, 14
            ).setCooldown(20 * 25) // 25s, starts when you let go
    );
    public static final TomeItem TOME_OF_CONTROLLING = (TomeItem) registerItem("tome_of_controlling",
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Advanced
                    TomeTier.ADVANCED, SpellId.CONTROLLING, 40
            ).setCooldown(20 * 100) // 100s, starts when you leave the mob
    );
    public static final TomeItem TOME_OF_THE_QUIVER = (TomeItem) registerItem("tome_of_the_quiver",
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Advanced
                    TomeTier.ADVANCED, SpellId.QUIVER, 20 // paid once, when the arrows are summoned
            ).setCooldown(20 * 30) // 30s, starts after the seventh arrow is fired
    );
    public static final TomeItem TOME_OF_THE_GLACIER = (TomeItem) registerItem("tome_of_the_glacier",
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Advanced
                    TomeTier.ADVANCED, SpellId.GLACIER, 16
            ).setCooldown(20 * 12) // 12 seconds
    );
    public static final TomeItem TOME_OF_THE_STONE_CANNON = (TomeItem) registerItem("tome_of_the_stone_cannon",
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Advanced
                    TomeTier.ADVANCED, SpellId.STONE_CANNON, 15
            ).setCooldown(20 * 12) // 12 seconds
    );
    public static final TomeItem TOME_OF_SLASHING = (TomeItem) registerItem("tome_of_slashing",
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Advanced
                    TomeTier.ADVANCED, SpellId.SLASHING, 13
            ).setCooldown(20 * 5) // 5 seconds
    );
    public static final TomeItem TOME_OF_DEFLECTION = (TomeItem) registerItem("tome_of_deflection",
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.UNCOMMON), // Beginner
                    TomeTier.BEGINNER, SpellId.DEFLECTION, 4
            ).setCooldown(20 * 3) // 3 seconds
    );
    public static final TomeItem TOME_OF_SHARP_LEAVES = (TomeItem) registerItem("tome_of_sharp_leaves",
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.UNCOMMON), // Beginner
                    TomeTier.BEGINNER, SpellId.SHARP_LEAVES, 3
            ).setCooldown(40) // 2 seconds
    );
    public static final TomeItem TOME_OF_THE_AIR_CUT = (TomeItem) registerItem("tome_of_the_air_cut",
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Advanced
                    TomeTier.ADVANCED, SpellId.AIR_CUT, 6
            ).setCooldown(46) // 2.3 seconds
    );
    public static final TomeItem TOME_OF_ILLUSION = (TomeItem) registerItem("tome_of_illusion",
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Advanced
                    TomeTier.ADVANCED, SpellId.ILLUSION, 8
            ).setCooldown(20 * 25) // 25 seconds
    );
    public static final TomeItem TOME_OF_DIMENSION_SPLIT = (TomeItem) registerItem("tome_of_dimension_split",
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC), // Master
                    TomeTier.MASTER, SpellId.DIMENSION_SPLIT, 50
            ).setCooldown(20 * 60) // 60 seconds
    );
    public static final TomeItem TOME_OF_VINES = (TomeItem) registerItem("tome_of_vines",
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Advanced
                    TomeTier.ADVANCED, SpellId.VINES, 14
            ).setCooldown(20 * 14) // 14 seconds
    );
    public static final TomeItem TOME_OF_HOME = (TomeItem) registerItem("tome_of_home",
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.UNCOMMON), // Beginner
                    TomeTier.BEGINNER, SpellId.HOME, 3
            ).setCooldown(60) // 3 seconds
    );
    public static final TomeItem TOME_OF_BREATHING = (TomeItem) registerItem("tome_of_breathing",
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.UNCOMMON), // Beginner
                    TomeTier.BEGINNER, SpellId.BREATHING, 8
            ).setCooldown(60) // 3 seconds
    );
    public static final net.minecraft.item.Item TOME_OF_PULLING = registerItem("tome_of_pulling",
            new net.ragnar.ragnarsmagicmod.item.custom.TomeItem(
                    new net.minecraft.item.Item.Settings().maxCount(1).rarity(net.minecraft.util.Rarity.UNCOMMON), // Beginner
                    net.ragnar.ragnarsmagicmod.item.spell.TomeTier.BEGINNER,
                    net.ragnar.ragnarsmagicmod.item.spell.SpellId.PULL,
                    6 // Low XP Cost
            ).setCooldown(50) // 2.5 seconds
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
        putTome(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.FREEZING,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.MASTER,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_FREEZING
        );
        putTome(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.RAINING_ARROWS,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.ADVANCED,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_RAINING_ARROWS
        );
        putTome(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.RANDOMNESS,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.ADVANCED,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_RANDOMNESS
        );
        putTome(
                SpellId.BOULDER,
                TomeTier.BEGINNER,
                (TomeItem) TOME_OF_BOULDERS
        );
        putTome(SpellId.SWAP, TomeTier.BEGINNER, (TomeItem) TOME_SWAPPING);
        putTome(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.CLOUDS,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.MASTER,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_OF_CLOUDS
        );
        putTome(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.SMASH,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.ADVANCED,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_OF_SMASHING
        );
        putTome(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.PULL,
                net.ragnar.ragnarsmagicmod.item.spell.TomeTier.BEGINNER,
                (net.ragnar.ragnarsmagicmod.item.custom.TomeItem) TOME_OF_PULLING
        );
        putTome(SpellId.FELLING, TomeTier.ADVANCED, TOME_OF_FELLING);
        putTome(SpellId.BREATHING, TomeTier.BEGINNER, TOME_OF_BREATHING);
        putTome(SpellId.IGNITION, TomeTier.ADVANCED, TOME_OF_IGNITION);
        putTome(SpellId.DEAD, TomeTier.ADVANCED, TOME_OF_THE_DEAD);
        putTome(SpellId.SWORDS, TomeTier.MASTER, TOME_OF_SWORDS);
        putTome(SpellId.LEVITATION, TomeTier.BEGINNER, TOME_OF_LEVITATION);
        putTome(SpellId.TELEKINESIS, TomeTier.MASTER, TOME_OF_TELEKINESIS);
        putTome(SpellId.CONTROLLING, TomeTier.ADVANCED, TOME_OF_CONTROLLING);
        putTome(SpellId.QUIVER, TomeTier.ADVANCED, TOME_OF_THE_QUIVER);
        putTome(SpellId.GLACIER, TomeTier.ADVANCED, TOME_OF_THE_GLACIER);
        putTome(SpellId.HOME, TomeTier.BEGINNER, TOME_OF_HOME);
        putTome(SpellId.STONE_CANNON, TomeTier.ADVANCED, TOME_OF_THE_STONE_CANNON);
        putTome(SpellId.SLASHING, TomeTier.ADVANCED, TOME_OF_SLASHING);
        putTome(SpellId.DEFLECTION, TomeTier.BEGINNER, TOME_OF_DEFLECTION);
        putTome(SpellId.SHARP_LEAVES, TomeTier.BEGINNER, TOME_OF_SHARP_LEAVES);
        putTome(SpellId.AIR_CUT, TomeTier.ADVANCED, TOME_OF_THE_AIR_CUT);
        putTome(SpellId.ILLUSION, TomeTier.ADVANCED, TOME_OF_ILLUSION);
        putTome(SpellId.DIMENSION_SPLIT, TomeTier.MASTER, TOME_OF_DIMENSION_SPLIT);
        putTome(SpellId.VINES, TomeTier.ADVANCED, TOME_OF_VINES);



    }

    // Staffs (use StaffItem now)
    public static final Item GOLDEN_STAFF = registerItem(
            "golden_staff",
            new StaffItem(new Item.Settings().maxDamage(128).rarity(Rarity.UNCOMMON),
                    EnumSet.of(TomeTier.BEGINNER), 3)
    );
    public static final Item DIAMOND_STAFF = registerItem(
            "diamond_staff",
            new StaffItem(new Item.Settings().maxDamage(384).rarity(Rarity.RARE),
                    EnumSet.of(TomeTier.BEGINNER, TomeTier.ADVANCED), 4)
    );
    public static final Item NETHERITE_STAFF = registerItem(
            "netherite_staff",
            new StaffItem(new Item.Settings().maxDamage(2031).rarity(Rarity.EPIC).fireproof(),
                    EnumSet.of(TomeTier.BEGINNER, TomeTier.ADVANCED, TomeTier.MASTER), 5)
    );

    //other stuff
    public static final Item ICE_SHARD_ITEM = registerItem("ice_shard",
            new Item(new Item.Settings().maxCount(16)));
    public static final Item BOULDER_ITEM = registerItem("boulder",
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

            //BEGINNER
            entries.add(TOME_OF_FIREBALLS);
            entries.add(new ItemStack(TOME_ICE_SHARDS));
            entries.add(new net.minecraft.item.ItemStack(TOME_FALLING_STALACTITE));
            entries.add(new net.minecraft.item.ItemStack(TOME_CHARGED_WIND));
            entries.add(new net.minecraft.item.ItemStack(TOME_SKULLS));
            entries.add(new net.minecraft.item.ItemStack(TOME_LIGHT));
            entries.add(new net.minecraft.item.ItemStack(TOME_AEGIS));
            entries.add(new net.minecraft.item.ItemStack(TOME_INSIGHT));
            entries.add(new net.minecraft.item.ItemStack(TOME_TRACKING));
            entries.add(new net.minecraft.item.ItemStack(TOME_VORTEX));
            entries.add(new net.minecraft.item.ItemStack(TOME_PUSHBACK));
            entries.add(new net.minecraft.item.ItemStack(TOME_FANGS));
            entries.add(new net.minecraft.item.ItemStack(TOME_GROWTH));
            entries.add(new net.minecraft.item.ItemStack(TOME_ARROW_VOLLEY));
            entries.add(new net.minecraft.item.ItemStack(TOME_OF_TORCHES));
            entries.add(new net.minecraft.item.ItemStack(TOME_INVISIBILITY));
            entries.add(new ItemStack(TOME_OF_BOULDERS));
            entries.add(new ItemStack(TOME_SWAPPING));
            entries.add(new net.minecraft.item.ItemStack(TOME_OF_PULLING));
            entries.add(new ItemStack(TOME_OF_BREATHING));
            entries.add(new ItemStack(TOME_OF_LEVITATION));
            entries.add(new ItemStack(TOME_OF_HOME));
            entries.add(new ItemStack(TOME_OF_DEFLECTION));
            entries.add(new ItemStack(TOME_OF_SHARP_LEAVES));
            //ADVANCED
            entries.add(TOME_GHASTFIRE);
            entries.add(new net.minecraft.item.ItemStack(TOME_FALLING_ANVILS));
            entries.add(new net.minecraft.item.ItemStack(TOME_PUSHING));
            entries.add(new net.minecraft.item.ItemStack(TOME_RISING_SPIKES));
            entries.add(new net.minecraft.item.ItemStack(TOME_DASHING));
            entries.add(new net.minecraft.item.ItemStack(TOME_ZAP));
            entries.add(new net.minecraft.item.ItemStack(TOME_LIGHTNING));
            entries.add(new net.minecraft.item.ItemStack(TOME_ENERGY));
            entries.add(new net.minecraft.item.ItemStack(TOME_REJUVENATION));
            entries.add(new net.minecraft.item.ItemStack(TOME_MINING));
            entries.add(new net.minecraft.item.ItemStack(TOME_GRAVITY));
            entries.add(new net.minecraft.item.ItemStack(TOME_RECALLING));
            entries.add(new net.minecraft.item.ItemStack(TOME_GHOSTSTEP));
            entries.add(new net.minecraft.item.ItemStack(TOME_OF_STEVE));
            entries.add(new net.minecraft.item.ItemStack(TOME_IMPALING));
            entries.add(new net.minecraft.item.ItemStack(TOME_RAINING_ARROWS));
            entries.add(new net.minecraft.item.ItemStack(TOME_RANDOMNESS));
            entries.add(new net.minecraft.item.ItemStack(TOME_OF_SMASHING));
            entries.add(new ItemStack(TOME_OF_FELLING));
            entries.add(new ItemStack(TOME_OF_IGNITION));
            entries.add(new ItemStack(TOME_OF_THE_DEAD));
            entries.add(new ItemStack(TOME_OF_CONTROLLING));
            entries.add(new ItemStack(TOME_OF_THE_QUIVER));
            entries.add(new ItemStack(TOME_OF_THE_GLACIER));
            entries.add(new ItemStack(TOME_OF_THE_STONE_CANNON));
            entries.add(new ItemStack(TOME_OF_SLASHING));
            entries.add(new ItemStack(TOME_OF_THE_AIR_CUT));
            entries.add(new ItemStack(TOME_OF_ILLUSION));
            entries.add(new ItemStack(TOME_OF_VINES));
            //MASTER
            entries.add(new net.minecraft.item.ItemStack(TOME_METEOR));
            entries.add(new net.minecraft.item.ItemStack(TOME_BLINKING));
            entries.add(new net.minecraft.item.ItemStack(TOME_LIGHTNING_CASCADE));
            entries.add(new net.minecraft.item.ItemStack(TOME_SONIC_BOOM));
            entries.add(new net.minecraft.item.ItemStack(TOME_SUN));
            entries.add(new net.minecraft.item.ItemStack(TOME_DRAGON_BREATH));
            entries.add(new net.minecraft.item.ItemStack(TOME_ICE_BEAM));
            entries.add(new net.minecraft.item.ItemStack(TOME_BOOMING));
            entries.add(new net.minecraft.item.ItemStack(TOME_OF_THE_VOID));
            entries.add(new net.minecraft.item.ItemStack(TOME_FREEZING));
            entries.add(new net.minecraft.item.ItemStack(TOME_OF_CLOUDS));
            entries.add(new ItemStack(TOME_OF_SWORDS));
            entries.add(new ItemStack(TOME_OF_TELEKINESIS));
            entries.add(new ItemStack(TOME_OF_DIMENSION_SPLIT));

            //entries.add(TOME_OF_FIREBALLS);
            //entries.add(TOME_GHASTFIRE);
            //entries.add(new ItemStack(TOME_ICE_SHARDS));
            //entries.add(new net.minecraft.item.ItemStack(TOME_FALLING_ANVILS));
            //entries.add(new net.minecraft.item.ItemStack(TOME_METEOR));
            //entries.add(new net.minecraft.item.ItemStack(TOME_FALLING_STALACTITE));
            //entries.add(new net.minecraft.item.ItemStack(TOME_PUSHING));
            //entries.add(new net.minecraft.item.ItemStack(TOME_BLINKING));
            //entries.add(new net.minecraft.item.ItemStack(TOME_RISING_SPIKES));
            //entries.add(new net.minecraft.item.ItemStack(TOME_DASHING));
            //entries.add(new net.minecraft.item.ItemStack(TOME_CHARGED_WIND));
            //entries.add(new net.minecraft.item.ItemStack(TOME_SKULLS));
            //entries.add(new net.minecraft.item.ItemStack(TOME_ZAP));
            //entries.add(new net.minecraft.item.ItemStack(TOME_LIGHTNING));
            //entries.add(new net.minecraft.item.ItemStack(TOME_LIGHTNING_CASCADE));
            //entries.add(new net.minecraft.item.ItemStack(TOME_ENERGY));
            //entries.add(new net.minecraft.item.ItemStack(TOME_SONIC_BOOM));
            //entries.add(new net.minecraft.item.ItemStack(TOME_REJUVENATION));
            //entries.add(new net.minecraft.item.ItemStack(TOME_LIGHT));
            //entries.add(new net.minecraft.item.ItemStack(TOME_AEGIS));
            //entries.add(new net.minecraft.item.ItemStack(TOME_INSIGHT));
            //entries.add(new net.minecraft.item.ItemStack(TOME_TRACKING));
            //entries.add(new net.minecraft.item.ItemStack(TOME_SUN));
            //entries.add(new net.minecraft.item.ItemStack(TOME_MINING));
            //entries.add(new net.minecraft.item.ItemStack(TOME_DRAGON_BREATH));
            //entries.add(new net.minecraft.item.ItemStack(TOME_GRAVITY));
            //entries.add(new net.minecraft.item.ItemStack(TOME_ICE_BEAM));
            //entries.add(new net.minecraft.item.ItemStack(TOME_VORTEX));
            //entries.add(new net.minecraft.item.ItemStack(TOME_PUSHBACK));
            //entries.add(new net.minecraft.item.ItemStack(TOME_RECALLING));
            //entries.add(new net.minecraft.item.ItemStack(TOME_FANGS));
            //entries.add(new net.minecraft.item.ItemStack(TOME_GHOSTSTEP));
            //entries.add(new net.minecraft.item.ItemStack(TOME_OF_STEVE));
            //entries.add(new net.minecraft.item.ItemStack(TOME_GROWTH));
            //entries.add(new net.minecraft.item.ItemStack(TOME_ARROW_VOLLEY));
            //entries.add(new net.minecraft.item.ItemStack(TOME_BOOMING));
            //entries.add(new net.minecraft.item.ItemStack(TOME_OF_TORCHES));
            //entries.add(new net.minecraft.item.ItemStack(TOME_IMPALING));
            //entries.add(new net.minecraft.item.ItemStack(TOME_INVISIBILITY));
            //entries.add(new net.minecraft.item.ItemStack(TOME_OF_THE_VOID));
            //entries.add(new net.minecraft.item.ItemStack(TOME_FREEZING));
            //entries.add(new net.minecraft.item.ItemStack(TOME_RAINING_ARROWS));
            //entries.add(new net.minecraft.item.ItemStack(TOME_RANDOMNESS));
        });
    }
}
