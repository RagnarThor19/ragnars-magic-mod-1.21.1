package net.ragnar.ragnarsmagicmod.jaunting;

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
 * Tome of Jaunting: throw a lightning-charged kunai, then right-click again to flash to wherever it is - stuck in a
 * wall, stuck in a mob, or still in the air. Shift-right-click dispels it. Everything for it lives in this package.
 * <p>
 * To remove it:
 * <ol>
 *   <li>delete this package ({@code jaunting} and {@code jaunting/client})</li>
 *   <li>delete {@code Jaunting.register()} in RagnarsMagicMod and {@code JauntingClient.init()} in RagnarsMagicModClient</li>
 *   <li>delete {@code JAUNTING} in SpellId</li>
 *   <li>delete {@code textures/item/jaunting_kunai.png}, {@code models/item/jaunting_kunai.json},
 *       {@code models/item/tome_of_jaunting.json} and the {@code jaunting} lines in en_us.json</li>
 *   <li>tests: delete JauntingGameTests and JauntingClientSmoke (and their lines in the gametest fabric.mod.json),
 *       and the {@code jauntingSmoke} run in build.gradle</li>
 * </ol>
 */
public final class Jaunting {
    private Jaunting() {}

    public static final EntityType<JauntingKunaiEntity> KUNAI = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(RagnarsMagicMod.MOD_ID, "jaunting_kunai"),
            FabricEntityTypeBuilder.<JauntingKunaiEntity>create(SpawnGroup.MISC, JauntingKunaiEntity::new)
                    .dimensions(EntityDimensions.fixed(0.25f, 0.25f))
                    .trackRangeBlocks(128)
                    .trackedUpdateRate(20)
                    .build()
    );

    /** Only here so the kunai has a sprite to draw (and a stack to carry); not in any creative tab. */
    public static final Item KUNAI_ITEM = Registry.register(Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "jaunting_kunai"), new Item(new Item.Settings().maxCount(1)));

    public static final TomeItem TOME_OF_JAUNTING = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_jaunting"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Advanced
                    TomeTier.ADVANCED, SpellId.JAUNTING, 8
            ).setCooldown(20 * 12) // 12 seconds, from the throw
    );

    public static void register() {
        // Lets staffs hand the tome back and puts it in the advanced tome loot pool
        ModItems.TOMES.computeIfAbsent(SpellId.JAUNTING, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.ADVANCED, TOME_OF_JAUNTING);
        Spells.register(SpellId.JAUNTING, new JauntingSpell());
        JauntingMarks.register();
        JauntPayload.register();
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.COMBAT).register(entries -> entries.addAfter(ModItems.TOME_OF_GRAPPLING, TOME_OF_JAUNTING));
    }
}
