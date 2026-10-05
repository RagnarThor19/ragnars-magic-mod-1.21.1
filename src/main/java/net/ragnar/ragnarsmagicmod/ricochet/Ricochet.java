package net.ragnar.ragnarsmagicmod.ricochet;

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
import net.ragnar.ragnarsmagicmod.jaunting.Jaunting;

import java.util.EnumMap;

/**
 * Tome of Ricochet: a white arrow hangs over your head for a moment - rattling to be let go - then shoots off where you
 * aim, and every time it hits something it leaps on to the next mob within a few blocks. Everything for it lives in
 * this package.
 * <p>
 * To remove it:
 * <ol>
 *   <li>delete this package ({@code ricochet} and {@code ricochet/client})</li>
 *   <li>delete {@code Ricochet.register()} in RagnarsMagicMod and {@code RicochetClient.init()} in RagnarsMagicModClient</li>
 *   <li>delete {@code RICOCHET} in SpellId</li>
 *   <li>delete {@code textures/entity/ricochet_arrow.png}, {@code models/item/tome_of_ricochet.json} and the
 *       {@code ricochet} lines in en_us.json</li>
 * </ol>
 */
public final class Ricochet {
    private Ricochet() {}

    public static final EntityType<RicochetArrowEntity> ARROW = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(RagnarsMagicMod.MOD_ID, "ricochet_arrow"),
            FabricEntityTypeBuilder.<RicochetArrowEntity>create(SpawnGroup.MISC, RicochetArrowEntity::new)
                    .dimensions(EntityDimensions.fixed(0.3f, 0.3f))
                    .trackRangeBlocks(128)
                    .trackedUpdateRate(1)
                    .disableSaving()
                    .build()
    );

    public static final TomeItem TOME_OF_RICOCHET = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_ricochet"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Advanced
                    TomeTier.ADVANCED, SpellId.RICOCHET, 10
            ).setCooldown(20 * 10) // 10 seconds
    );

    public static void register() {
        // Lets staffs hand the tome back and puts it in the advanced tome loot pool
        ModItems.TOMES.computeIfAbsent(SpellId.RICOCHET, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.ADVANCED, TOME_OF_RICOCHET);
        Spells.register(SpellId.RICOCHET, new RicochetSpell());
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.addAfter(Jaunting.TOME_OF_JAUNTING, TOME_OF_RICOCHET));
    }
}
