package net.ragnar.ragnarsmagicmod.boulders;

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
 * Tome of Boulders: the big brother of the Tome of Rocks. A huge boulder forms over your head and you hurl it where
 * you look. It ploughs through whatever's in its way, comes down in a ground-shaking slam that throws everything
 * around it, then rolls on, bowling things over, until it breaks apart (see BoulderEntity). Everything for it lives in
 * this package, apart from the rock model and Tumble, which the Tome of Rocks shares.
 * <p>
 * To remove it:
 * <ol>
 *   <li>delete BoulderEntity, BoulderSpell, Boulders and client/BouldersClient + client/BoulderRenderer</li>
 *   <li>delete {@code Boulders.register()} in RagnarsMagicMod and {@code BouldersClient.init()} in
 *       RagnarsMagicModClient</li>
 *   <li>delete {@code BOULDERS} in SpellId</li>
 *   <li>delete {@code models/item/tome_of_boulders.json} and the {@code boulder} lines in en_us.json</li>
 * </ol>
 */
public final class Boulders {
    private Boulders() {}

    public static final EntityType<BoulderEntity> BOULDER = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(RagnarsMagicMod.MOD_ID, "boulder"),
            FabricEntityTypeBuilder.<BoulderEntity>create(SpawnGroup.MISC, BoulderEntity::new)
                    .dimensions(EntityDimensions.fixed(1.8f, 1.8f))
                    .trackRangeBlocks(128)
                    .trackedUpdateRate(1)
                    .disableSaving()
                    .build()
    );

    public static final TomeItem TOME_OF_BOULDERS = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_boulders"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Advanced
                    TomeTier.ADVANCED, SpellId.BOULDERS, 14
            ).setCooldown(20 * 14) // 14 seconds
    );

    public static void register() {
        // Lets staffs hand the tome back and puts it in the advanced tome loot pool
        ModItems.TOMES.computeIfAbsent(SpellId.BOULDERS, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.ADVANCED, TOME_OF_BOULDERS);
        Spells.register(SpellId.BOULDERS, new BoulderSpell());
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.addAfter(ModItems.TOME_OF_THE_STONE_CANNON, TOME_OF_BOULDERS));
    }
}
