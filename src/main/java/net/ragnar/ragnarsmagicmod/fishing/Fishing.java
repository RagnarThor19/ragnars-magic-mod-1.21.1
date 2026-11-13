package net.ragnar.ragnarsmagicmod.fishing;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.loot.LootTable;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.PassiveTomes;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.PassiveSpell;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Tome of Fishing: a passive tome. While it's in your inventory (or on a staff you carry):
 * <ul>
 *   <li>you fish with {@link #LUCK} extra luck, as if your rod had another Luck of the Sea III;</li>
 *   <li>things bite twice as fast;</li>
 *   <li>{@link #DOUBLE_CHANCE} of reels bring up two catches, and {@link #TRIPLE_CHANCE} three;</li>
 *   <li>a catch from lava is now and then alive: a lava fish ({@link #LAVA_FISH_CHANCE}, see LavaFishEntity), a
 *       wither skeleton or a magma cube ({@link #WITHER_SKELETON_CHANCE} each), hauled out and flung ashore;</li>
 *   <li>you can fish in lava. The bobber floats on it instead of sinking and never burns, the bites come with lava
 *       pops and embers instead of splashes, and the catch comes from the lava's own loot
 *       ({@link #LAVA_FISHING}: Nether catches, Nether junk and Nether treasure, where tomes are
 *       {@link #LAVA_TOME_BOOST}x as common as in water). What's reeled out of the lava can't burn on its way to
 *       you.</li>
 * </ul>
 * The bobber side of it is in FishingBobberMixin; the lava loot is plain data under
 * {@code data/ragnarsmagicmod/loot_table/gameplay/lava_fishing*}, with the tomes and staff books added to its
 * treasure in ModLootTableModifiers.
 * <p>
 * To remove it: delete this package, FishingBobberMixin and FishingBobberAccessor (and their lines in
 * ragnarsmagicmod.mixins.json), the lava_fishing loot tables and their lines in ModLootTableModifiers,
 * {@code Fishing.register()} in RagnarsMagicMod, {@code FISHING} in SpellId, it in PassiveTomes.register,
 * {@code models/item/tome_of_fishing.json} and its en_us.json line.
 */
public final class Fishing {
    private Fishing() {}

    public static final int LUCK = 3;
    /** Chances a reel brings up three catches, or two (the rest: one). */
    public static final float TRIPLE_CHANCE = 0.05f, DOUBLE_CHANCE = 0.2f;
    public static final float LAVA_TOME_BOOST = 1.1f;
    /** Chances a catch from lava is a creature instead of an item. */
    public static final float LAVA_FISH_CHANCE = 0.01f, WITHER_SKELETON_CHANCE = 0.001f, MAGMA_CUBE_CHANCE = 0.001f;
    /** How long a catch pulled out of the lava is kept from burning: plenty to reach the angler. */
    static final int PROTECT_TICKS = 100;

    public static final RegistryKey<LootTable> LAVA_FISHING = key("gameplay/lava_fishing");
    public static final RegistryKey<LootTable> LAVA_TREASURE = key("gameplay/lava_fishing/treasure");

    public static final TomeItem TOME_OF_FISHING = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_fishing"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Passive
                    TomeTier.PASSIVE, SpellId.FISHING, 0
            ).setCooldown(0)
    );

    /** Catches still on their way out of the lava, and the server tick their protection ends. */
    private static final Map<ItemEntity, Integer> PROTECTED = new HashMap<>();

    private static RegistryKey<LootTable> key(String path) {
        return RegistryKey.of(RegistryKeys.LOOT_TABLE, Identifier.of(RagnarsMagicMod.MOD_ID, path));
    }

    public static void register() {
        ModItems.TOMES.computeIfAbsent(SpellId.FISHING, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.PASSIVE, TOME_OF_FISHING);
        Spells.register(SpellId.FISHING, new PassiveSpell("Luckier, faster fishing, the odd double catch - and you can fish in lava - while this tome is in your inventory or on a staff you carry."));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (PROTECTED.isEmpty()) return;
            int now = server.getTicks();
            PROTECTED.entrySet().removeIf(e -> {
                ItemEntity item = e.getKey();
                if (item.isRemoved()) return true;
                item.setFireTicks(0);
                if (now < e.getValue()) return false;
                item.setInvulnerable(false);
                return true;
            });
        });
    }

    /** True if {@code player} fishes with the tome. */
    public static boolean has(PlayerEntity player) {
        return player != null && PassiveTomes.has(player, TOME_OF_FISHING);
    }

    /** How many catches one reel brings up for someone with the tome: 3, 2 or 1. */
    public static int pulls(net.minecraft.util.math.random.Random random) {
        float roll = random.nextFloat();
        return roll < TRIPLE_CHANCE ? 3 : roll < TRIPLE_CHANCE + DOUBLE_CHANCE ? 2 : 1;
    }

    /** The creature a catch from lava turns out to be, or null for an ordinary catch. */
    public static net.minecraft.entity.EntityType<?> lavaCreature(net.minecraft.util.math.random.Random random) {
        float roll = random.nextFloat();
        if (roll < LAVA_FISH_CHANCE) return net.ragnar.ragnarsmagicmod.lavafish.LavaFish.LAVA_FISH;
        if (roll < LAVA_FISH_CHANCE + WITHER_SKELETON_CHANCE) return net.minecraft.entity.EntityType.WITHER_SKELETON;
        if (roll < LAVA_FISH_CHANCE + WITHER_SKELETON_CHANCE + MAGMA_CUBE_CHANCE) return net.minecraft.entity.EntityType.MAGMA_CUBE;
        return null;
    }

    /**
     * Hauls a creature up out of the lava at {@code from} and flings it toward the angler, as a caught item is. A
     * wither skeleton comes up armed, a magma cube middling-sized, and a lava fish to keep (it never despawns).
     */
    public static net.minecraft.entity.Entity pullCreature(net.minecraft.server.world.ServerWorld world, net.minecraft.util.math.Vec3d from,
                                                           PlayerEntity angler, net.minecraft.entity.EntityType<?> type) {
        net.minecraft.entity.Entity e = type.create(world);
        if (e == null) return null;
        e.refreshPositionAndAngles(from.x, from.y + 0.5, from.z, world.getRandom().nextFloat() * 360f, 0f);
        if (e instanceof net.minecraft.entity.mob.MobEntity mob) {
            mob.initialize(world, world.getLocalDifficulty(mob.getBlockPos()), net.minecraft.entity.SpawnReason.TRIGGERED, null);
        }
        if (e instanceof net.minecraft.entity.mob.MagmaCubeEntity cube) cube.setSize(2, true);
        if (e instanceof net.ragnar.ragnarsmagicmod.lavafish.LavaFishEntity fish) fish.setFromBucket(true);
        // The same arc a caught item takes, a little stronger as a creature drags more
        double dx = angler.getX() - from.x, dy = angler.getY() - from.y, dz = angler.getZ() - from.z;
        double arc = Math.sqrt(Math.sqrt(dx * dx + dy * dy + dz * dz)) * 0.08;
        e.setVelocity(dx * 0.12, dy * 0.1 + arc + 0.1, dz * 0.12);
        e.setFireTicks(0);
        world.spawnEntity(e);

        net.minecraft.sound.SoundEvent cry = type == net.minecraft.entity.EntityType.WITHER_SKELETON ? net.minecraft.sound.SoundEvents.ENTITY_WITHER_SKELETON_AMBIENT
                : type == net.minecraft.entity.EntityType.MAGMA_CUBE ? net.minecraft.sound.SoundEvents.ENTITY_MAGMA_CUBE_JUMP
                : net.minecraft.sound.SoundEvents.BLOCK_LAVA_POP;
        world.playSound(null, from.x, from.y, from.z, cry, net.minecraft.sound.SoundCategory.NEUTRAL, 1.0f, 1.0f);
        world.playSound(null, from.x, from.y, from.z, net.minecraft.sound.SoundEvents.ITEM_BUCKET_EMPTY_LAVA, net.minecraft.sound.SoundCategory.NEUTRAL, 0.8f, 1.2f);
        world.spawnParticles(net.minecraft.particle.ParticleTypes.LAVA, from.x, from.y + 0.3, from.z, 8, 0.3, 0.1, 0.3, 0);
        return e;
    }

    /** Keeps a catch reeled out of the lava from burning up before it reaches the angler. */
    public static void protect(ItemEntity item) {
        item.setInvulnerable(true);
        item.setFireTicks(0);
        if (item.getServer() != null) PROTECTED.put(item, item.getServer().getTicks() + PROTECT_TICKS);
    }
}
