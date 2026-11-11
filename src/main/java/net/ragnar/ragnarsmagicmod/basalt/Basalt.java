package net.ragnar.ragnarsmagicmod.basalt;

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
import net.ragnar.ragnarsmagicmod.phantom.Phantom;

import java.util.EnumMap;

/**
 * Tome of Basalt: the master tier Tome of Boulders. For a second the caster gathers heat and stone: chunks of the
 * ground tear loose and spiral up into a glowing core over their head, which grows into a huge volcanic rock of basalt,
 * blackstone and magma. Then it's hurled like a boulder: it ploughs through things for {@link BasaltEntity#HIT_DAMAGE},
 * comes down in a burning slam, and rolls on, scorching what it rolls over, cooling as it goes, until it bursts apart
 * (see BasaltEntity).
 * <p>
 * To remove it:
 * <ol>
 *   <li>delete this package (basalt/)</li>
 *   <li>delete {@code Basalt.register()} in RagnarsMagicMod and {@code BasaltClient.init()} in RagnarsMagicModClient</li>
 *   <li>delete {@code BASALT} in SpellId</li>
 *   <li>delete {@code models/item/tome_of_basalt.json} and the {@code basalt} lines in en_us.json</li>
 * </ol>
 */
public final class Basalt {
    private Basalt() {}

    public static final int XP_COST = 30;
    public static final int COOLDOWN = 25 * 20;

    public static final EntityType<BasaltEntity> BASALT_BOULDER = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(RagnarsMagicMod.MOD_ID, "basalt_boulder"),
            FabricEntityTypeBuilder.<BasaltEntity>create(SpawnGroup.MISC, BasaltEntity::new)
                    .dimensions(EntityDimensions.fixed(3.9f, 3.9f))
                    .trackRangeBlocks(128)
                    .trackedUpdateRate(1)
                    .disableSaving()
                    .fireImmune()
                    .build()
    );

    public static final TomeItem TOME_OF_BASALT = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_basalt"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC), // Master
                    TomeTier.MASTER, SpellId.BASALT, XP_COST
            ).setCooldown(COOLDOWN)
    );

    public static void register() {
        // Lets staffs hand the tome back and puts it in the master tome loot pool
        ModItems.TOMES.computeIfAbsent(SpellId.BASALT, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.MASTER, TOME_OF_BASALT);
        Spells.register(SpellId.BASALT, new BasaltSpell());
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.addAfter(Phantom.TOME_OF_THE_PHANTOM, TOME_OF_BASALT));
    }
}
