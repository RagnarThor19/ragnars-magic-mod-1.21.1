package net.ragnar.ragnarsmagicmod.enchantment;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;

public class ModEnchantments {
    // We register KEYS, not classes, in 1.21
    public static final RegistryKey<Enchantment> RESERVE = RegistryKey.of(RegistryKeys.ENCHANTMENT, Identifier.of(RagnarsMagicMod.MOD_ID, "reserve"));
    public static final RegistryKey<Enchantment> QUICKCAST = RegistryKey.of(RegistryKeys.ENCHANTMENT, Identifier.of(RagnarsMagicMod.MOD_ID, "quickcast"));

    public static void registerModEnchantments() {
        RagnarsMagicMod.LOGGER.info("Registering Enchantment Keys for " + RagnarsMagicMod.MOD_ID);
    }
}