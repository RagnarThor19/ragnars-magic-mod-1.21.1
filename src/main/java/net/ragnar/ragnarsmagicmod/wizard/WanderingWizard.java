package net.ragnar.ragnarsmagicmod.wizard;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.Items;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;

/**
 * The Wandering Wizard (see WanderingWizardEntity): a wandering trader turned wizard, twice as rare, who trades tomes
 * for tomes and fights back with one of the tomes in WizardSpell. Everything for him lives in this package (the
 * renderer in {@code client}).
 * <p>
 * To remove him: delete this package and {@code mixin/wizard} (and its line in ragnarsmagicmod.mixins.json), delete
 * {@code WanderingWizard.register()} in RagnarsMagicMod and {@code WanderingWizardClient.init()} in
 * RagnarsMagicModClient, {@code textures/entity/wandering_wizard*.png} (him, his casting circle and sigil),
 * {@code models/item/wandering_wizard_spawn_egg.json}, his en_us.json lines, and in the gametests
 * WanderingWizardGameTests, WizardClientSmoke and the {@code wizardSmoke} run in build.gradle. The small hooks he uses in Slicer, SharpLeavesSpell, AirCutSpell and RisingSpikesSpell
 * (casting from something that isn't a player) can stay.
 */
public final class WanderingWizard {
    private WanderingWizard() {}

    public static final EntityType<WanderingWizardEntity> WANDERING_WIZARD = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(RagnarsMagicMod.MOD_ID, "wandering_wizard"),
            EntityType.Builder.<WanderingWizardEntity>create(WanderingWizardEntity::new, SpawnGroup.CREATURE)
                    .dimensions(0.6f, 1.95f)
                    .eyeHeight(1.62f)
                    .maxTrackingRange(10)
                    .build()
    );

    public static final Item WANDERING_WIZARD_SPAWN_EGG = Registry.register(Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "wandering_wizard_spawn_egg"),
            new SpawnEggItem(WANDERING_WIZARD, 0x26306B, 0xF2C04A, new Item.Settings()));

    public static void register() {
        FabricDefaultAttributeRegistry.register(WANDERING_WIZARD, WanderingWizardEntity.createAttributes());
        ServerTickEvents.END_WORLD_TICK.register(WizardSpawner::tick);
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.SPAWN_EGGS).register(entries ->
                entries.addAfter(Items.WANDERING_TRADER_SPAWN_EGG, WANDERING_WIZARD_SPAWN_EGG));
    }
}
