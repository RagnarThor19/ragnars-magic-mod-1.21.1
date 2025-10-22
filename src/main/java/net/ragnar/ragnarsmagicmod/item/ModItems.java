package net.ragnar.ragnarsmagicmod.item;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import java.util.EnumSet;
import static net.ragnar.ragnarsmagicmod.RagnarsMagicMod.LOGGER;


public class ModItems {
    public static final Item FALSE_TOME = registerItem("false_tome", new Item(new Item.Settings().maxCount(1)));

    // Tome(s)
    public static final TomeItem TOME_OF_FIREBALLS = (TomeItem) registerItem(
            "tome_of_fireballs",
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.UNCOMMON), TomeTier.BEGINNER, SpellId.FIREBALLS, 8)
    );

    // Staffs (use StaffItem now)
    public static final Item GOLDEN_STAFF = registerItem(
            "golden_staff",
            new StaffItem(new Item.Settings().maxDamage(128).rarity(Rarity.UNCOMMON),
                    EnumSet.of(TomeTier.BEGINNER))
    );
    public static final Item DIAMOND_STAFF = registerItem(
            "diamond_staff",
            new StaffItem(new Item.Settings().maxDamage(384).rarity(Rarity.RARE),
                    EnumSet.of(TomeTier.BEGINNER, TomeTier.ADVANCED))
    );
    public static final Item NETHERITE_STAFF = registerItem(
            "netherite_staff",
            new StaffItem(new Item.Settings().maxDamage(2031).rarity(Rarity.EPIC).fireproof(),
                    EnumSet.of(TomeTier.BEGINNER, TomeTier.ADVANCED, TomeTier.MASTER))
    );

    private static Item registerItem(String name, Item item) {
        return Registry.register(Registries.ITEM, Identifier.of(RagnarsMagicMod.MOD_ID, name), item);
    }

    public static void registerModItems() {
        RagnarsMagicMod.LOGGER.info("Registering Mod Items for " + RagnarsMagicMod.MOD_ID);

        // add to creative tabs
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.COMBAT).register(entries -> {
            entries.add(GOLDEN_STAFF);
            entries.add(DIAMOND_STAFF);
            entries.add(NETHERITE_STAFF);
        });
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> {
            entries.add(TOME_OF_FIREBALLS);
            entries.add(FALSE_TOME);
        });
    }
}
