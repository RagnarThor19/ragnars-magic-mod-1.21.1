package net.ragnar.ragnarsmagicmod.knight;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
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
 * Tome of Knight: everything for it lives in this package, so it can be taken out cleanly.
 * <p>
 * To remove it:
 * <ol>
 *   <li>delete this package ({@code knight} and {@code knight/client})</li>
 *   <li>delete {@code Knight.register()} in RagnarsMagicMod and {@code KnightClient.init()} in RagnarsMagicModClient</li>
 *   <li>delete {@code KNIGHT} in SpellId</li>
 *   <li>delete {@code textures/entity/knight.png}, {@code textures/entity/knight_glow.png},
 *       {@code models/item/tome_of_knight.json} and the two {@code knight} lines in en_us.json</li>
 *   <li>tests: delete KnightGameTests and KnightClientSmoke (and their lines in the gametest fabric.mod.json),
 *       and the {@code knightSmoke} run in build.gradle</li>
 * </ol>
 * Staffs that still carry the tome just drop it (StaffItem skips unknown spells).
 */
public final class Knight {
    private Knight() {}

    public static final EntityType<KnightEntity> KNIGHT = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(RagnarsMagicMod.MOD_ID, "knight"),
            FabricEntityTypeBuilder.<KnightEntity>create(SpawnGroup.MISC, KnightEntity::new)
                    .dimensions(EntityDimensions.fixed(1.4f, 3.0f))
                    .trackRangeBlocks(80)
                    .trackedUpdateRate(2)
                    .fireImmune()
                    .disableSaving()
                    .build()
    );

    public static final TomeItem TOME_OF_KNIGHT = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_knight"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC), // Master
                    TomeTier.MASTER, SpellId.KNIGHT, 80
            ).setCooldown(20 * 35) // 25 seconds, starting when the knight is gone
    );

    public static void register() {
        FabricDefaultAttributeRegistry.register(KNIGHT, KnightEntity.createAttributes());
        // Lets staffs hand the tome back and puts it in the master tome loot pool
        ModItems.TOMES.computeIfAbsent(SpellId.KNIGHT, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.MASTER, TOME_OF_KNIGHT);
        Spells.register(SpellId.KNIGHT, new KnightSpell());
        KnightSpell.register();
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.addAfter(ModItems.TOME_OF_BUILDING, TOME_OF_KNIGHT));
    }
}
