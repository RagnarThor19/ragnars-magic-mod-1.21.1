package net.ragnar.ragnarsmagicmod.entity;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;

public class ModEntities {
    public static final EntityType<IceShardEntity> ICE_SHARD = Registry.register(
            Registries.ENTITY_TYPE,
            net.minecraft.util.Identifier.of(RagnarsMagicMod.MOD_ID, "ice_shard"),
            FabricEntityTypeBuilder.<IceShardEntity>create(SpawnGroup.MISC, IceShardEntity::new)
                    .dimensions(EntityDimensions.fixed(0.25f, 0.25f))
                    .trackRangeBlocks(64)
                    .trackedUpdateRate(10)
                    .build()
    );

    public static void register() {
        RagnarsMagicMod.LOGGER.info("Registered entities");
    }
}
