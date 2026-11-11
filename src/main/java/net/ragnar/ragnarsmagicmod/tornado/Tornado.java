package net.ragnar.ragnarsmagicmod.tornado;

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
import net.ragnar.ragnarsmagicmod.sleep.Sleep;

import java.util.EnumMap;

/**
 * Tome of Tornados: whips up a small tornado, about {@link TornadoEntity#HEIGHT} blocks tall, a few steps in front of
 * you. It wanders off roughly the way you were looking, but never in a straight line - it veers, lurches and doubles
 * back - for {@link TornadoEntity#LIFE} ticks. Creatures nearby get dragged in, spun round and lifted up the funnel,
 * battered a little on the way, and then flung out of it. Everything for it lives in this package.
 * <p>
 * To remove it:
 * <ol>
 *   <li>delete this package ({@code tornado} and {@code tornado/client})</li>
 *   <li>delete {@code Tornado.register()} in RagnarsMagicMod and {@code TornadoClient.init()} in
 *       RagnarsMagicModClient</li>
 *   <li>delete {@code TORNADO} in SpellId</li>
 *   <li>delete {@code models/item/tome_of_tornados.json} and the {@code tornado} lines in en_us.json</li>
 * </ol>
 */
public final class Tornado {
    private Tornado() {}

    public static final int XP_COST = 12;
    public static final int COOLDOWN = 20 * 20;

    public static final EntityType<TornadoEntity> TORNADO = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tornado"),
            FabricEntityTypeBuilder.<TornadoEntity>create(SpawnGroup.MISC, TornadoEntity::new)
                    .dimensions(EntityDimensions.fixed(1.2f, TornadoEntity.HEIGHT))
                    .trackRangeBlocks(96)
                    .trackedUpdateRate(1)
                    .disableSaving()
                    .build()
    );

    public static final TomeItem TOME_OF_TORNADOS = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_tornados"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.UNCOMMON), // Beginner
                    TomeTier.BEGINNER, SpellId.TORNADO, XP_COST
            ).setCooldown(COOLDOWN) // 20 seconds
    );

    public static void register() {
        // Lets staffs hand the tome back and puts it in the beginner tome loot pool
        ModItems.TOMES.computeIfAbsent(SpellId.TORNADO, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.BEGINNER, TOME_OF_TORNADOS);
        Spells.register(SpellId.TORNADO, new TornadoSpell());
        // At the end of the beginner tomes in the tab
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.addAfter(Sleep.TOME_OF_SLEEP_DARTS, TOME_OF_TORNADOS));
    }
}
