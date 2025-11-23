package net.ragnar.ragnarsmagicmod.datagen;

import net.fabricmc.fabric.api.datagen.v1.FabricDataOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricTagProvider;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.tag.EnchantmentTags;
import net.ragnar.ragnarsmagicmod.enchantment.ModEnchantments;

import java.util.concurrent.CompletableFuture;

public class ModEnchantmentTagProvider extends FabricTagProvider.EnchantmentTagProvider {
    public ModEnchantmentTagProvider(FabricDataOutput output, CompletableFuture<RegistryWrapper.WrapperLookup> registriesFuture) {
        super(output, registriesFuture);
    }

    @Override
    protected void configure(RegistryWrapper.WrapperLookup arg) {
        // We explicitly add our custom keys to the IN_ENCHANTING_TABLE tag
        getOrCreateTagBuilder(EnchantmentTags.IN_ENCHANTING_TABLE)
                .add(ModEnchantments.RESERVE)
                .add(ModEnchantments.QUICKCAST);

        getOrCreateTagBuilder(EnchantmentTags.TRADEABLE)
                .add(ModEnchantments.RESERVE)
                .add(ModEnchantments.QUICKCAST);

        // Also add to CURSE tag if you wanted curses, but we don't.
        // Adding to NON_TREASURE helps ensure they show up in tables too (vs strictly chest loot)
        getOrCreateTagBuilder(EnchantmentTags.NON_TREASURE)
                .add(ModEnchantments.RESERVE)
                .add(ModEnchantments.QUICKCAST);
    }
}