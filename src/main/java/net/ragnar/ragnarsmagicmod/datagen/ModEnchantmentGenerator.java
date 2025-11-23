package net.ragnar.ragnarsmagicmod.datagen;

import net.fabricmc.fabric.api.datagen.v1.FabricDataOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricDynamicRegistryProvider;
import net.minecraft.component.type.AttributeModifierSlot;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.registry.Registerable;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.ragnar.ragnarsmagicmod.enchantment.ModEnchantments;
import net.ragnar.ragnarsmagicmod.util.ModTags;

import java.util.concurrent.CompletableFuture;

public class ModEnchantmentGenerator extends FabricDynamicRegistryProvider {
    public ModEnchantmentGenerator(FabricDataOutput output, CompletableFuture<RegistryWrapper.WrapperLookup> registriesFuture) {
        super(output, registriesFuture);
    }

    @Override
    protected void configure(RegistryWrapper.WrapperLookup registries, Entries entries) {
        // This method is now handled by the bootstrap method below mostly,
        // but FabricDynamicRegistryProvider uses this to actually write the files.
        // It pulls from the registries populated in buildRegistry.
        // So usually, we don't need to manually 'add' here if we used buildRegistry correctly,
        // BUT for simplicity in Fabric 1.21, we can keep using this entries.add pattern
        // OR use the bootstrap pattern.
        // The ERROR you got suggests the TagProvider couldn't see the entries.

        // Let's use the entries.addAll approach which copies from the registry we built.
        entries.addAll(registries.getWrapperOrThrow(RegistryKeys.ENCHANTMENT));
    }

    // This method populates the registry in memory so Tags can see it
    public static void bootstrap(Registerable<Enchantment> registerable) {
        var itemLookup = registerable.getRegistryLookup(RegistryKeys.ITEM);

        register(registerable, ModEnchantments.RESERVE, Enchantment.builder(
                Enchantment.definition(
                        itemLookup.getOrThrow(ModTags.Items.STAFFS),
                        10,
                        3,
                        Enchantment.leveledCost(10, 20),
                        Enchantment.leveledCost(60, 20),
                        2,
                        AttributeModifierSlot.MAINHAND
                ))
        );

        register(registerable, ModEnchantments.QUICKCAST, Enchantment.builder(
                Enchantment.definition(
                        itemLookup.getOrThrow(ModTags.Items.STAFFS),
                        5,
                        3,
                        Enchantment.leveledCost(15, 20),
                        Enchantment.leveledCost(65, 20),
                        4,
                        AttributeModifierSlot.MAINHAND
                ))
        );
    }

    private static void register(Registerable<Enchantment> registry, net.minecraft.registry.RegistryKey<Enchantment> key, Enchantment.Builder builder) {
        registry.register(key, builder.build(key.getValue()));
    }

    @Override
    public String getName() {
        return "Mod Enchantments";
    }
}