package net.ragnar.ragnarsmagicmod.poisondarts;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.tornado.Tornado;
import org.joml.Vector3f;

import java.util.EnumMap;

/**
 * Tome of Poison Darts: puffs out a little green dart, blowpipe style - no charge, very fast and nearly flat. The first
 * living thing it hits takes {@link PoisonDartEntity#DAMAGE} damage and is poisoned; anything else, it sticks into for
 * a moment. Everything for it lives in this package.
 * <p>
 * To remove it:
 * <ol>
 *   <li>delete this package ({@code poisondarts} and {@code poisondarts/client})</li>
 *   <li>delete {@code PoisonDarts.register()} in RagnarsMagicMod and {@code PoisonDartsClient.init()} in
 *       RagnarsMagicModClient</li>
 *   <li>delete {@code POISON_DARTS} in SpellId</li>
 *   <li>delete {@code models/item/tome_of_poison_darts.json} and the {@code poison_dart} lines in en_us.json</li>
 * </ol>
 */
public final class PoisonDarts {
    private PoisonDarts() {}

    public static final int XP_COST = 6;
    public static final int COOLDOWN = 20 * 5;

    /** The sickly green everything about the spell is coloured. */
    public static final int COLOR = 0x7FD13B;
    public static final DustParticleEffect DUST = new DustParticleEffect(new Vector3f(0.5f, 0.82f, 0.23f), 1.0f);
    /** Smaller motes, for the trail and anything right in front of the caster's face. */
    public static final DustParticleEffect MOTE = new DustParticleEffect(new Vector3f(0.55f, 0.9f, 0.3f), 0.45f);

    public static final EntityType<PoisonDartEntity> DART = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(RagnarsMagicMod.MOD_ID, "poison_dart"),
            FabricEntityTypeBuilder.<PoisonDartEntity>create(SpawnGroup.MISC, PoisonDartEntity::new)
                    .dimensions(EntityDimensions.fixed(0.25f, 0.25f))
                    .trackRangeBlocks(64)
                    .trackedUpdateRate(1)
                    .disableSaving()
                    .build()
    );

    public static final TomeItem TOME_OF_POISON_DARTS = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_poison_darts"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.UNCOMMON), // Beginner
                    TomeTier.BEGINNER, SpellId.POISON_DARTS, XP_COST
            ).setCooldown(COOLDOWN) // 5 seconds
    );

    public static void register() {
        // Lets staffs hand the tome back and puts it in the beginner tome loot pool
        ModItems.TOMES.computeIfAbsent(SpellId.POISON_DARTS, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.BEGINNER, TOME_OF_POISON_DARTS);
        Spells.register(SpellId.POISON_DARTS, new PoisonDartSpell());
        // At the end of the beginner tomes in the tab
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.addAfter(Tornado.TOME_OF_TORNADOS, TOME_OF_POISON_DARTS));
    }
}
