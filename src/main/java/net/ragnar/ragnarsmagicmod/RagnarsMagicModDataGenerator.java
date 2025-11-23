package net.ragnar.ragnarsmagicmod;

import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricTagProvider;
import net.minecraft.registry.RegistryBuilder;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.tag.ItemTags;
import net.ragnar.ragnarsmagicmod.datagen.ModEnchantmentGenerator;
import net.ragnar.ragnarsmagicmod.datagen.ModEnchantmentTagProvider;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.util.ModTags;

public class RagnarsMagicModDataGenerator implements DataGeneratorEntrypoint {

    @Override
    public void onInitializeDataGenerator(FabricDataGenerator fabricDataGenerator) {
        FabricDataGenerator.Pack pack = fabricDataGenerator.createPack();

        // 1. Generate Enchantment Definitions
        pack.addProvider(ModEnchantmentGenerator::new);

        // 2. Generate Enchantment TAGS
        pack.addProvider(ModEnchantmentTagProvider::new);

        // 3. Generate Item Tags
        pack.addProvider((output, registriesFuture) -> new FabricTagProvider.ItemTagProvider(output, registriesFuture) {
            @Override
            protected void configure(RegistryWrapper.WrapperLookup arg) {
                getOrCreateTagBuilder(ModTags.Items.STAFFS)
                        .add(ModItems.GOLDEN_STAFF)
                        .add(ModItems.DIAMOND_STAFF)
                        .add(ModItems.NETHERITE_STAFF);

                getOrCreateTagBuilder(ItemTags.WEAPON_ENCHANTABLE)
                        .add(ModItems.GOLDEN_STAFF)
                        .add(ModItems.DIAMOND_STAFF)
                        .add(ModItems.NETHERITE_STAFF);

                getOrCreateTagBuilder(ItemTags.DURABILITY_ENCHANTABLE)
                        .add(ModItems.GOLDEN_STAFF)
                        .add(ModItems.DIAMOND_STAFF)
                        .add(ModItems.NETHERITE_STAFF);

                getOrCreateTagBuilder(ItemTags.VANISHING_ENCHANTABLE)
                        .add(ModItems.GOLDEN_STAFF)
                        .add(ModItems.DIAMOND_STAFF)
                        .add(ModItems.NETHERITE_STAFF);
            }
        });
    }

    // Manually add this method. It is part of the interface in recent Fabric API versions.
    @Override
    public void buildRegistry(RegistryBuilder registryBuilder) {
        registryBuilder.addRegistry(RegistryKeys.ENCHANTMENT, ModEnchantmentGenerator::bootstrap);
    }
}