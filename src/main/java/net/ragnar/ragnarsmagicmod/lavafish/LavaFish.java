package net.ragnar.ragnarsmagicmod.lavafish;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.EntityBucketItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.Items;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;

/**
 * The lava fish (see LavaFishEntity): a rare catch from fishing in lava with the Tome of Fishing, and its two
 * buckets. It never spawns on its own. Everything for it lives in this package (the renderer in {@code client}).
 * <p>
 * To remove it: delete this package, {@code LavaFish.register()} in RagnarsMagicMod and {@code LavaFishClient.init()}
 * in RagnarsMagicModClient, the lava fish lines in Fishing, the textures ({@code entity/lava_fish*.png},
 * {@code item/lava_fish_*bucket.png}), the item models, {@code loot_table/entities/lava_fish.json} and its en_us.json
 * lines.
 */
public final class LavaFish {
    private LavaFish() {}

    public static final EntityType<LavaFishEntity> LAVA_FISH = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(RagnarsMagicMod.MOD_ID, "lava_fish"),
            FabricEntityTypeBuilder.<LavaFishEntity>create(SpawnGroup.MISC, LavaFishEntity::new)
                    .dimensions(EntityDimensions.fixed(0.5f, 0.3f))
                    .fireImmune()
                    .trackRangeBlocks(64)
                    .build()
    );

    public static final Item BUCKET_OF_LAVA_FISH = item("lava_fish_bucket",
            new EntityBucketItem(LAVA_FISH, Fluids.WATER, SoundEvents.ITEM_BUCKET_EMPTY_FISH,
                    new Item.Settings().maxCount(1).component(DataComponentTypes.BUCKET_ENTITY_DATA, NbtComponent.DEFAULT)));

    public static final Item LAVA_BUCKET_OF_LAVA_FISH = item("lava_fish_lava_bucket",
            new EntityBucketItem(LAVA_FISH, Fluids.LAVA, SoundEvents.ITEM_BUCKET_EMPTY_LAVA,
                    new Item.Settings().maxCount(1).component(DataComponentTypes.BUCKET_ENTITY_DATA, NbtComponent.DEFAULT)));

    public static final Item LAVA_FISH_SPAWN_EGG = item("lava_fish_spawn_egg",
            new SpawnEggItem(LAVA_FISH, 0x2C2022, 0xF5701A, new Item.Settings()));

    private static Item item(String name, Item item) {
        return Registry.register(Registries.ITEM, Identifier.of(RagnarsMagicMod.MOD_ID, name), item);
    }

    public static void register() {
        FabricDefaultAttributeRegistry.register(LAVA_FISH, LavaFishEntity.createAttributes());
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries ->
                entries.addAfter(Items.TADPOLE_BUCKET, BUCKET_OF_LAVA_FISH, LAVA_BUCKET_OF_LAVA_FISH));
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.SPAWN_EGGS).register(entries ->
                entries.addAfter(Items.MAGMA_CUBE_SPAWN_EGG, LAVA_FISH_SPAWN_EGG));
    }
}
