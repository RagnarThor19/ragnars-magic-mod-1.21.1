package net.ragnar.ragnarsmagicmod.oak;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.blackhole.BlackHoles;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * Tome of Oak: a master tome. A colossal oak bursts up out of the ground just behind the caster, towering some thirty
 * blocks over them, groans, leans back for a heartbeat - then tears its roots out of the earth and topples forward
 * over the caster's shoulder like a falling monument, landing with an echoing crash. Everything under it is crushed;
 * everything around it is thrown by the shockwave. It lies there a few seconds, then crumbles away (see OakTree).
 * <p>
 * The tree is built from block displays, not blocks, so it never touches the world; and every piece sits on the same
 * spot at the foot of the trunk, turned and placed only by its transformation, so the whole tree swings as one rigid
 * body without a single entity ever moving (the clients interpolate the turn smoothly).
 * <p>
 * Everything for it lives in this package. To remove it:
 * <ol>
 *   <li>delete this package</li>
 *   <li>delete {@code Oak.register()} in RagnarsMagicMod and {@code OAK} in SpellId</li>
 *   <li>point PassiveTomes.register back at BlackHoles.TOME_OF_BLACK_HOLES</li>
 *   <li>delete {@code models/item/tome_of_oak.json} and its en_us.json line</li>
 * </ol>
 */
public final class Oak {
    private Oak() {}

    public static final int XP_COST = 55;
    public static final int COOLDOWN = 20 * 120;

    public static final TomeItem TOME_OF_OAK = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_oak"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC), // Master
                    TomeTier.MASTER, SpellId.OAK, XP_COST
            ).setCooldown(COOLDOWN)
    );

    static final List<OakTree> TREES = new ArrayList<>();

    public static void register() {
        // Lets staffs hand the tome back and puts it in the master tome loot pool
        ModItems.TOMES.computeIfAbsent(SpellId.OAK, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.MASTER, TOME_OF_OAK);
        Spells.register(SpellId.OAK, new OakSpell());
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.addAfter(BlackHoles.TOME_OF_BLACK_HOLES, TOME_OF_OAK));

        ServerTickEvents.END_WORLD_TICK.register(world -> {
            if (TREES.isEmpty()) return;
            TREES.removeIf(tree -> tree.world == world && !tree.tick());
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            TREES.forEach(OakTree::discard);
            TREES.clear();
        });
    }

    /** The oaks standing, falling or lying right now (for tests). */
    public static List<OakTree> trees() {
        return List.copyOf(TREES);
    }
}
