package net.ragnar.ragnarsmagicmod.riptide;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
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
import net.ragnar.ragnarsmagicmod.water.Water;

import java.util.EnumMap;

/**
 * Tome of Riptide: a Riptide III trident launch anywhere, rain or shine, dry land or not. You shoot off where you look
 * in the trident's own spin, wrapped in its swirl of water, hitting whatever you crash into like a riptide does. A ring
 * of water bursts out where you leave and spray trails behind you. The fall from the launch never hurts. Everything
 * for it lives in this package; the spin, the swirl and the hit are vanilla's own riptide.
 * <p>
 * To remove it:
 * <ol>
 *   <li>delete this package ({@code riptide})</li>
 *   <li>delete {@code Riptide.register()} in RagnarsMagicMod</li>
 *   <li>delete {@code RIPTIDE} in SpellId</li>
 *   <li>delete {@code models/item/tome_of_riptide.json} and the {@code tome_of_riptide} line in en_us.json</li>
 * </ol>
 */
public final class Riptide {
    private Riptide() {}

    public static final int XP_COST = 5;
    public static final int COOLDOWN = 20 * 4;

    public static final TomeItem TOME_OF_RIPTIDE = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_riptide"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Advanced
                    TomeTier.ADVANCED, SpellId.RIPTIDE, XP_COST
            ).setCooldown(COOLDOWN)
    );

    public static void register() {
        // Lets staffs hand the tome back and puts it in the advanced tome loot pool
        ModItems.TOMES.computeIfAbsent(SpellId.RIPTIDE, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.ADVANCED, TOME_OF_RIPTIDE);
        Spells.register(SpellId.RIPTIDE, new RiptideSpell());
        // Next to the Tome of Water
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.addAfter(Water.TOME_OF_WATER, TOME_OF_RIPTIDE));
        ServerTickEvents.END_SERVER_TICK.register(RiptideSpell::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> RiptideSpell.drop(handler.getPlayer()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> RiptideSpell.clear());
    }
}
