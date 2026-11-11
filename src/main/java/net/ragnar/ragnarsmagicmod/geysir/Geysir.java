package net.ragnar.ragnarsmagicmod.geysir;

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
import net.ragnar.ragnarsmagicmod.punching.Punching;

import java.util.EnumMap;

/**
 * Tome of Geysir: every creature within {@link GeysirSpell#RADIUS} blocks gets a geysir of its own, right under where
 * it stood when you cast. For a second the ground there shakes and seeps; then a column of water bursts
 * {@link GeysirEntity#MAX_HEIGHT} blocks into the air and throws whatever's over it skyward. Dead centre flies about
 * {@link GeysirEntity#DIRECT_HEIGHT} blocks; anything that edged off the spot in that second is still caught, just
 * thrown lower and off to the side. The water itself only stings - it's the landing that hurts.
 * <p>
 * The world is never changed: the column, its spray and the shaking ground are drawn by GeysirRenderer, and the water
 * is never real water. Everything for it lives in this package.
 * <p>
 * To remove it:
 * <ol>
 *   <li>delete this package ({@code geysir} and {@code geysir/client})</li>
 *   <li>delete {@code Geysir.register()} in RagnarsMagicMod and {@code GeysirClient.init()} in RagnarsMagicModClient</li>
 *   <li>delete {@code GEYSIR} in SpellId</li>
 *   <li>delete {@code models/item/tome_of_geysir.json} and the {@code geysir} lines in en_us.json</li>
 * </ol>
 */
public final class Geysir {
    private Geysir() {}

    public static final int XP_COST = 15;
    public static final int COOLDOWN = 20 * 18;

    public static final EntityType<GeysirEntity> GEYSIR = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(RagnarsMagicMod.MOD_ID, "geysir"),
            FabricEntityTypeBuilder.<GeysirEntity>create(SpawnGroup.MISC, GeysirEntity::new)
                    .dimensions(EntityDimensions.fixed(1.2f, 1.0f))
                    .trackRangeBlocks(96)
                    .trackedUpdateRate(20)
                    .disableSaving()
                    .build()
    );

    public static final TomeItem TOME_OF_GEYSIR = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_geysir"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Advanced
                    TomeTier.ADVANCED, SpellId.GEYSIR, XP_COST
            ).setCooldown(COOLDOWN) // 18 seconds
    );

    public static void register() {
        // Lets staffs hand the tome back and puts it in the advanced tome loot pool
        ModItems.TOMES.computeIfAbsent(SpellId.GEYSIR, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.ADVANCED, TOME_OF_GEYSIR);
        Spells.register(SpellId.GEYSIR, new GeysirSpell());
        // At the end of the advanced tomes in the tab
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.addAfter(Punching.TOME_OF_PUNCHING, TOME_OF_GEYSIR));
    }
}
