package net.ragnar.ragnarsmagicmod.grace;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.FleeEntityGoal;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PhantomEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.predicate.entity.EntityPredicates;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.PassiveTomes;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.PassiveSpell;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;

/**
 * Tome of Grace: a passive tome. While it's in your inventory (or on a staff you carry), creepers and phantoms treat you
 * the way they treat a cat. Neither will pick you as a target (so a creeper never starts its fuse for you, and phantoms
 * just circle), and creepers back away once you're within {@link #FLEE_DISTANCE} blocks. The first time one turns
 * tail, you hear the faintest cat's hiss.
 * <p>
 * A creeper lit with flint and steel, or caught in someone else's fight, still goes off as normal. To remove it: delete
 * this package, its lines in CreeperEntityMixin and MobEntityMixin, {@code GRACE} in SpellId, it in
 * PassiveTomes.register, {@code models/item/tome_of_grace.json} and its en_us.json line.
 */
public final class Grace {
    private Grace() {}

    /** Creepers back away from you inside this many blocks, like they do from cats. */
    public static final float FLEE_DISTANCE = 6.0f;
    /** A creeper hisses at most this often, so a nervous one dancing at the edge doesn't keep at it. */
    private static final int HISS_EVERY = 200;

    public static final TomeItem TOME_OF_GRACE = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_grace"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Passive
                    TomeTier.PASSIVE, SpellId.GRACE, 0
            ).setCooldown(0)
    );

    public static void register() {
        ModItems.TOMES.computeIfAbsent(SpellId.GRACE, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.PASSIVE, TOME_OF_GRACE);
        Spells.register(SpellId.GRACE, new PassiveSpell("creepers and phantoms keep their distance while this tome is in your inventory or on a staff you carry."));
    }

    /** True if {@code mob} should leave {@code target} alone: a creeper or phantom, and a player carrying the tome. */
    public static boolean ignores(MobEntity mob, @Nullable LivingEntity target) {
        return (mob instanceof CreeperEntity || mob instanceof PhantomEntity) && target instanceof PlayerEntity p
                && !mob.getWorld().isClient && PassiveTomes.has(p, TOME_OF_GRACE);
    }

    /** The goal every creeper gets: back away from players carrying the tome, as from a cat. */
    public static FleeEntityGoal<PlayerEntity> fleeGoal(CreeperEntity creeper) {
        return new FleeEntityGoal<>(creeper, PlayerEntity.class, p -> PassiveTomes.has((PlayerEntity) p, TOME_OF_GRACE),
                FLEE_DISTANCE, 1.0, 1.2, EntityPredicates.EXCEPT_SPECTATOR::test) {
            private int lastHiss = -HISS_EVERY;

            @Override
            public void start() {
                super.start();
                if (creeper.age - lastHiss < HISS_EVERY || targetEntity == null) return;
                lastHiss = creeper.age;
                // Only the player it's backing away from hears it, quietly
                if (targetEntity instanceof PlayerEntity p) {
                    p.playSoundToPlayer(SoundEvents.ENTITY_CAT_HISS, SoundCategory.PLAYERS, 0.25f, 1.3f);
                }
            }
        };
    }
}
