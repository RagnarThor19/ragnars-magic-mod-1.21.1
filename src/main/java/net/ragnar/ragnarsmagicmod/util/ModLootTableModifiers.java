package net.ragnar.ragnarsmagicmod.util;

import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.minecraft.loot.LootPool;
import net.minecraft.loot.LootTables;
import net.minecraft.loot.condition.RandomChanceLootCondition;
import net.minecraft.loot.entry.ItemEntry;
import net.minecraft.loot.provider.number.ConstantLootNumberProvider;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.loot.LootTable;
import net.minecraft.util.Identifier;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ModLootTableModifiers {

    // Define keys manually to match vanilla IDs (fixes mapping mismatches)
    private static final RegistryKey<LootTable> TRIAL_REWARD_COMMON = key("chests/trial_chambers/reward_common");
    private static final RegistryKey<LootTable> TRIAL_REWARD_RARE = key("chests/trial_chambers/reward_rare");
    private static final RegistryKey<LootTable> TRIAL_REWARD_UNIQUE = key("chests/trial_chambers/reward_unique");

    private static final RegistryKey<LootTable> TRIAL_OMINOUS_COMMON = key("chests/trial_chambers/reward_ominous_common");
    private static final RegistryKey<LootTable> TRIAL_OMINOUS_RARE = key("chests/trial_chambers/reward_ominous_rare");
    private static final RegistryKey<LootTable> TRIAL_OMINOUS_UNIQUE = key("chests/trial_chambers/reward_ominous_unique");

    private static RegistryKey<LootTable> key(String path) {
        return RegistryKey.of(RegistryKeys.LOOT_TABLE, Identifier.ofVanilla(path));
    }

    public static void modifyLootTables() {
        LootTableEvents.MODIFY.register((key, tableBuilder, source, registries) -> {

            // ============================================================
            // FISHING (Treasure category is best for "good loot")
            // ============================================================
            if (key.equals(LootTables.FISHING_TREASURE_GAMEPLAY)) {
                // B: 2%
                buildTomePool(tableBuilder, TomeTier.BEGINNER, 0.02f);
                // A: 0.5%
                buildTomePool(tableBuilder, TomeTier.ADVANCED, 0.005f);
                // M: 0.1%
                buildTomePool(tableBuilder, TomeTier.MASTER, 0.001f);
            }

            // ============================================================
            // TRIAL CHAMBERS (Standard Vaults)
            // ============================================================
            if (key.equals(TRIAL_REWARD_COMMON)) {
                buildTomePool(tableBuilder, TomeTier.BEGINNER, 0.05f); // B: 5%
            }
            if (key.equals(TRIAL_REWARD_RARE)) {
                buildTomePool(tableBuilder, TomeTier.ADVANCED, 0.05f); // A: 5%
            }
            if (key.equals(TRIAL_REWARD_UNIQUE)) {
                buildTomePool(tableBuilder, TomeTier.MASTER, 0.02f);   // M: 2%
            }

            // ============================================================
            // TRIAL CHAMBERS (Ominous Vaults)
            // ============================================================
            if (key.equals(TRIAL_OMINOUS_COMMON)) {
                buildTomePool(tableBuilder, TomeTier.BEGINNER, 0.10f); // B: 10%
            }
            if (key.equals(TRIAL_OMINOUS_RARE)) {
                buildTomePool(tableBuilder, TomeTier.ADVANCED, 0.10f); // A: 10%
            }
            if (key.equals(TRIAL_OMINOUS_UNIQUE)) {
                buildTomePool(tableBuilder, TomeTier.MASTER, 0.05f);   // M: 5%
            }

            // ============================================================
            // VILLAGE CHESTS (Houses)
            // ============================================================
            if (key.equals(LootTables.VILLAGE_PLAINS_CHEST) ||
                    key.equals(LootTables.VILLAGE_DESERT_HOUSE_CHEST) ||
                    key.equals(LootTables.VILLAGE_SAVANNA_HOUSE_CHEST) ||
                    key.equals(LootTables.VILLAGE_TAIGA_HOUSE_CHEST) ||
                    key.equals(LootTables.VILLAGE_SNOWY_HOUSE_CHEST)) {

                buildTomePool(tableBuilder, TomeTier.BEGINNER, 0.02f); // B: 2%
            }

            // ============================================================
            // VILLAGE BLACKSMITH (Weaponsmith)
            // ============================================================
            if (key.equals(LootTables.VILLAGE_WEAPONSMITH_CHEST)) {
                buildTomePool(tableBuilder, TomeTier.ADVANCED, 0.02f); // A: 2%
            }

            // ============================================================
            // DESERT PYRAMID & JUNGLE TEMPLE
            // ============================================================
            if (key.equals(LootTables.DESERT_PYRAMID_CHEST) ||
                    key.equals(LootTables.JUNGLE_TEMPLE_CHEST)) {

                buildTomePool(tableBuilder, TomeTier.BEGINNER, 0.05f); // B: 5%
                buildTomePool(tableBuilder, TomeTier.ADVANCED, 0.02f); // A: 2%
            }

            // ============================================================
            // IGLOO
            // ============================================================
            if (key.equals(LootTables.IGLOO_CHEST_CHEST)) {
                buildTomePool(tableBuilder, TomeTier.BEGINNER, 0.05f); // B: 5%
                buildTomePool(tableBuilder, TomeTier.ADVANCED, 0.03f); // A: 3%
            }

            // ============================================================
            // SHIPWRECK (Treasure Chest)
            // ============================================================
            if (key.equals(LootTables.SHIPWRECK_TREASURE_CHEST)) {
                buildTomePool(tableBuilder, TomeTier.BEGINNER, 0.03f); // B: 3%
                buildTomePool(tableBuilder, TomeTier.ADVANCED, 0.01f); // A: 1%
            }

            // ============================================================
            // BURIED TREASURE
            // ============================================================
            if (key.equals(LootTables.BURIED_TREASURE_CHEST)) {
                buildTomePool(tableBuilder, TomeTier.BEGINNER, 0.10f); // B: 10%
                buildTomePool(tableBuilder, TomeTier.ADVANCED, 0.04f); // A: 4%
                buildTomePool(tableBuilder, TomeTier.MASTER, 0.01f);   // M: 1%
            }

            // ============================================================
            // ABANDONED MINESHAFT & DUNGEON
            // ============================================================
            if (key.equals(LootTables.ABANDONED_MINESHAFT_CHEST) ||
                    key.equals(LootTables.SIMPLE_DUNGEON_CHEST)) {

                buildTomePool(tableBuilder, TomeTier.BEGINNER, 0.08f); // B: 8%
                buildTomePool(tableBuilder, TomeTier.ADVANCED, 0.02f); // A: 2%
            }

            // ============================================================
            // WOODLAND MANSION
            // ============================================================
            if (key.equals(LootTables.WOODLAND_MANSION_CHEST)) {
                buildTomePool(tableBuilder, TomeTier.ADVANCED, 0.05f); // A: 5%
                buildTomePool(tableBuilder, TomeTier.MASTER, 0.02f);   // M: 2%
            }

            // ============================================================
            // ANCIENT CITY
            // ============================================================
            if (key.equals(LootTables.ANCIENT_CITY_CHEST)) {
                buildTomePool(tableBuilder, TomeTier.BEGINNER, 0.05f); // B: 5%
                buildTomePool(tableBuilder, TomeTier.ADVANCED, 0.10f); // A: 10%
                buildTomePool(tableBuilder, TomeTier.MASTER, 0.03f);   // M: 3%
            }

            // ============================================================
            // STRONGHOLD
            // ============================================================
            // Library
            if (key.equals(LootTables.STRONGHOLD_LIBRARY_CHEST)) {
                buildTomePool(tableBuilder, TomeTier.ADVANCED, 0.25f); // A: 25%
                buildTomePool(tableBuilder, TomeTier.MASTER, 0.05f);   // M: 5%
            }
            // Corridor & Crossing
            if (key.equals(LootTables.STRONGHOLD_CORRIDOR_CHEST) ||
                    key.equals(LootTables.STRONGHOLD_CROSSING_CHEST)) {

                buildTomePool(tableBuilder, TomeTier.BEGINNER, 0.10f); // B: 10%
                buildTomePool(tableBuilder, TomeTier.ADVANCED, 0.05f); // A: 5%
            }

            // ============================================================
            // UNDERWATER RUINS (Big & Small)
            // ============================================================
            if (key.equals(LootTables.UNDERWATER_RUIN_BIG_CHEST) ||
                    key.equals(LootTables.UNDERWATER_RUIN_SMALL_CHEST)) {

                buildTomePool(tableBuilder, TomeTier.BEGINNER, 0.03f); // B: 3%
                buildTomePool(tableBuilder, TomeTier.ADVANCED, 0.02f); // A: 2%
            }

            // ============================================================
            // NETHER FORTRESS
            // ============================================================
            if (key.equals(LootTables.NETHER_BRIDGE_CHEST)) {
                buildTomePool(tableBuilder, TomeTier.BEGINNER, 0.05f); // B: 5%
                buildTomePool(tableBuilder, TomeTier.ADVANCED, 0.05f); // A: 5%
                buildTomePool(tableBuilder, TomeTier.MASTER, 0.01f);   // M: 1%
            }

            // ============================================================
            // BASTION REMNANT
            // ============================================================
            // Treasure Chest
            if (key.equals(LootTables.BASTION_TREASURE_CHEST)) {
                buildTomePool(tableBuilder, TomeTier.ADVANCED, 0.15f); // A: 15%
                buildTomePool(tableBuilder, TomeTier.MASTER, 0.03f);   // M: 3%
            }
            // Bridge, Other, Hoglin Stable
            if (key.equals(LootTables.BASTION_BRIDGE_CHEST) ||
                    key.equals(LootTables.BASTION_OTHER_CHEST) ||
                    key.equals(LootTables.BASTION_HOGLIN_STABLE_CHEST)) {

                buildTomePool(tableBuilder, TomeTier.ADVANCED, 0.06f); // A: 6%
                buildTomePool(tableBuilder, TomeTier.MASTER, 0.005f);  // M: 0.5%
            }

            // ============================================================
            // RUINED PORTAL
            // ============================================================
            if (key.equals(LootTables.RUINED_PORTAL_CHEST)) {
                buildTomePool(tableBuilder, TomeTier.BEGINNER, 0.04f); // B: 4%
                buildTomePool(tableBuilder, TomeTier.ADVANCED, 0.01f); // A: 1%
            }

            // ============================================================
            // END CITY
            // ============================================================
            if (key.equals(LootTables.END_CITY_TREASURE_CHEST)) {
                buildTomePool(tableBuilder, TomeTier.ADVANCED, 0.06f); // A: 6%
                buildTomePool(tableBuilder, TomeTier.MASTER, 0.04f);   // M: 4%
            }
        });
    }

    /**
     * Helper to inject a pool of ALL tomes of a specific tier into a table with a specific chance.
     */
    private static void buildTomePool(net.minecraft.loot.LootTable.Builder tableBuilder, TomeTier tier, float chance) {
        List<TomeItem> tomes = new ArrayList<>();
        for (Map<TomeTier, TomeItem> tierMap : ModItems.TOMES.values()) {
            if (tierMap.containsKey(tier)) {
                tomes.add(tierMap.get(tier));
            }
        }

        if (tomes.isEmpty()) return;

        LootPool.Builder pool = LootPool.builder()
                .rolls(ConstantLootNumberProvider.create(1)) // Roll once...
                .conditionally(RandomChanceLootCondition.builder(chance)); // ...if the chance passes

        for (TomeItem tome : tomes) {
            pool.with(ItemEntry.builder(tome));
        }

        tableBuilder.pool(pool);
    }
}