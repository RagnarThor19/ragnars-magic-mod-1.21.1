package net.ragnar.ragnarsmagicmod.entity;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.*;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
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

    // ========== STEVE (new) ==========
    public static final EntityType<SteveEntity> STEVE = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(RagnarsMagicMod.MOD_ID, "steve"),
            FabricEntityTypeBuilder.<SteveEntity>create(SpawnGroup.CREATURE, SteveEntity::new)
                    .dimensions(EntityDimensions.fixed(0.6F, 1.8F)) // player size
                    .trackRangeBlocks(64)
                    .trackedUpdateRate(3)
                    .build()
    );

    public static final EntityType<BoulderProjectileEntity> BOULDER_PROJECTILE = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(RagnarsMagicMod.MOD_ID, "boulder"),
            FabricEntityTypeBuilder.<BoulderProjectileEntity>create(SpawnGroup.MISC, BoulderProjectileEntity::new)
                    .dimensions(EntityDimensions.fixed(0.75f, 0.75f)) // Bigger hitbox
                    .trackRangeBlocks(64)
                    .trackedUpdateRate(10)
                    .build()
    );

    public static void registerModEntities() {
        FabricDefaultAttributeRegistry.register(STEVE, SteveEntity.createAttributes());
    }
    public static void register() {
        RagnarsMagicMod.LOGGER.info("Registered entities");
    }
}
