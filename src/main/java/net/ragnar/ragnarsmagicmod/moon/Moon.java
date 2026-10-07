package net.ragnar.ragnarsmagicmod.moon;

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
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;

import java.util.EnumMap;

/**
 * Tome of the Moon: the Tome of the Sun's cold twin. A pale, cratered moon forms high over wherever you're aiming
 * and starts to sink, slowly at first and then faster, its shadow spreading over the ground beneath. Its pull lifts
 * every creature near the landing spot off its feet and sweeps them round in a slow ring under it. When it lands,
 * everything it lifted is slammed back into the ground, a silver shockwave tears outward, and the crater glows with
 * moonlight for a few seconds after (see MoonEntity). Everything for it lives in this package.
 * <p>
 * To remove it:
 * <ol>
 *   <li>delete this package ({@code moon} and {@code moon/client})</li>
 *   <li>delete {@code Moon.register()} in RagnarsMagicMod and {@code MoonClient.init()} in RagnarsMagicModClient</li>
 *   <li>delete {@code MOON} in SpellId</li>
 *   <li>delete {@code models/item/tome_of_the_moon.json} and the {@code moon} lines in en_us.json</li>
 * </ol>
 */
public final class Moon {
    private Moon() {}

    public static final EntityType<MoonEntity> MOON = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(RagnarsMagicMod.MOD_ID, "moon"),
            FabricEntityTypeBuilder.<MoonEntity>create(SpawnGroup.MISC, MoonEntity::new)
                    .dimensions(EntityDimensions.fixed(1f, 1f))
                    .trackRangeBlocks(160)
                    .trackedUpdateRate(1)
                    .disableSaving()
                    .build()
    );

    public static final TomeItem TOME_OF_THE_MOON = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_the_moon"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC), // Master
                    TomeTier.MASTER, SpellId.MOON, 40
            ).setCooldown(20 * 20) // 20 seconds
    );

    public static void register() {
        // Lets staffs hand the tome back and puts it in the master tome loot pool
        ModItems.TOMES.computeIfAbsent(SpellId.MOON, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.MASTER, TOME_OF_THE_MOON);
        Spells.register(SpellId.MOON, new MoonSpell());
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.addAfter(ModItems.TOME_SUN, TOME_OF_THE_MOON));
    }
}
