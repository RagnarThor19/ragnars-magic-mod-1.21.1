package net.ragnar.ragnarsmagicmod.sun;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;

/**
 * Tome of the Sun (the item is ModItems.TOME_SUN): conjures a small sun in front of you that kindles, drifts off the
 * way you aim, grows as it goes - burning all it touches - then caves in and bursts in a nova of flame (see
 * SunEntity). It's drawn as a solid, churning ball of molten blocks in a blazing corona (SunRenderer).
 * <p>
 * Everything for it lives in this package (the drawing in {@code client}).
 */
public final class Sun {
    private Sun() {}

    public static final EntityType<SunEntity> SUN = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(RagnarsMagicMod.MOD_ID, "sun"),
            FabricEntityTypeBuilder.<SunEntity>create(SpawnGroup.MISC, SunEntity::new)
                    .dimensions(EntityDimensions.fixed(0.5f, 0.5f))
                    .trackRangeBlocks(192)
                    .trackedUpdateRate(1)
                    .disableSaving()
                    .build()
    );

    public static void register() {
        Spells.register(SpellId.SUN, new Spell() {
            @Override
            public boolean cast(World world, PlayerEntity player, ItemStack staff) {
                if (world.isClient) return false;
                world.spawnEntity(new SunEntity(world, player));
                world.playSound(null, player.getBlockPos(), net.minecraft.sound.SoundEvents.ITEM_FIRECHARGE_USE,
                        net.minecraft.sound.SoundCategory.PLAYERS, 1.5f, 0.5f);
                world.playSound(null, player.getBlockPos(), net.minecraft.sound.SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE,
                        net.minecraft.sound.SoundCategory.PLAYERS, 1.2f, 0.6f);
                return true;
            }
        });
    }
}
