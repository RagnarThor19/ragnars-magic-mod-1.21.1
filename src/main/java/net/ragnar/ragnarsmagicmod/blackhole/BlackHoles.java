package net.ragnar.ragnarsmagicmod.blackhole;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.basalt.Basalt;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;

import java.util.EnumMap;

/**
 * Tome of Black Holes: a master tome. For two and a half seconds the caster tears a hole in the world in front of them:
 * a pinpoint of nothing that swells into a black sphere, with a disk of white-hot blocks spinning up around it, jets
 * of amethyst light firing from its poles and the ground ripping loose and falling in. Then it drifts forward, dragging
 * everything near into it and crushing what it catches, until it caves in on itself and bursts (see BlackHoleEntity).
 * <p>
 * To remove it:
 * <ol>
 *   <li>delete this package (blackhole/)</li>
 *   <li>delete {@code BlackHoles.register()} in RagnarsMagicMod and {@code BlackHoleClient.init()} in RagnarsMagicModClient</li>
 *   <li>delete {@code BLACK_HOLES} in SpellId, and put {@code Basalt.TOME_OF_BASALT} back as the anchor in PassiveTomes</li>
 *   <li>delete {@code models/item/tome_of_black_holes.json} and the {@code black_hole} lines in en_us.json</li>
 * </ol>
 */
public final class BlackHoles {
    private BlackHoles() {}

    public static final int XP_COST = 60;
    public static final int COOLDOWN = 90 * 20;

    public static final EntityType<BlackHoleEntity> BLACK_HOLE = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(RagnarsMagicMod.MOD_ID, "black_hole"),
            FabricEntityTypeBuilder.<BlackHoleEntity>create(SpawnGroup.MISC, BlackHoleEntity::new)
                    .dimensions(EntityDimensions.fixed(1.2f, 1.2f))
                    .trackRangeBlocks(160)
                    .trackedUpdateRate(1)
                    .disableSaving()
                    .fireImmune()
                    .build()
    );

    public static final TomeItem TOME_OF_BLACK_HOLES = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_black_holes"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC), // Master
                    TomeTier.MASTER, SpellId.BLACK_HOLES, XP_COST
            ).setCooldown(COOLDOWN) // 90 seconds
    );

    public static void register() {
        // Lets staffs hand the tome back and puts it in the master tome loot pool
        ModItems.TOMES.computeIfAbsent(SpellId.BLACK_HOLES, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.MASTER, TOME_OF_BLACK_HOLES);
        Spells.register(SpellId.BLACK_HOLES, new BlackHoleSpell());
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.addAfter(Basalt.TOME_OF_BASALT, TOME_OF_BLACK_HOLES));
    }
}
