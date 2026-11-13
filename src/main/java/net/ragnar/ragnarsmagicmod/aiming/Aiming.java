package net.ragnar.ragnarsmagicmod.aiming;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
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

import java.util.EnumMap;

/**
 * Tome of Aiming: a passive tome. While it's in your inventory (or on a staff you carry), a bow is fully drawn in 7
 * ticks (0.35s, against 1s) and a crossbow loads in 13 (0.65s, against 1.25s).
 * <p>
 * How far a bow is drawn - the arrow's power, the bow's drawing animation, the zoom - and how far a crossbow is
 * loaded are all worked out from how long you've been holding it, so speeding that one count up speeds up all of
 * them together. AimingLivingEntityMixin adds extra ticks of drawing as it's held - worked out from the whole draw so
 * far, on both sides alike - so the client's animation and the server's arrow agree. Nothing else that's held (shields, food, tridents) is touched.
 * <p>
 * To remove it: delete this package and AimingLivingEntityMixin (and its line in ragnarsmagicmod.mixins.json),
 * {@code Aiming.register()} in RagnarsMagicMod, {@code AIMING} in SpellId, it in PassiveTomes.register,
 * {@code models/item/tome_of_aiming.json} and its en_us.json line.
 */
public final class Aiming {
    private Aiming() {}

    /**
     * How much faster each draws. A bow is fully drawn after 20 ticks of drawing and a crossbow loaded after 25, so
     * these land on 7 and 13 real ticks (a hair over 20/7 and 25/13, so rounding never costs a tick).
     */
    public static final float BOW_SPEED = 2.87f, CROSSBOW_SPEED = 1.93f;

    public static final TomeItem TOME_OF_AIMING = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_aiming"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Passive
                    TomeTier.PASSIVE, SpellId.AIMING, 0
            ).setCooldown(0)
    );

    public static void register() {
        ModItems.TOMES.computeIfAbsent(SpellId.AIMING, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.PASSIVE, TOME_OF_AIMING);
        Spells.register(SpellId.AIMING, new PassiveSpell("Bows draw in 0.35s and crossbows load in 0.65s while this tome is in your inventory or on a staff you carry."));
    }

    /** How much faster {@code user} draws {@code stack}: 1 unless it's a bow or crossbow and they carry the tome. */
    public static float speed(LivingEntity user, ItemStack stack) {
        boolean bow = stack.getItem() instanceof BowItem, crossbow = stack.getItem() instanceof CrossbowItem;
        if (!(bow || crossbow) || !(user instanceof PlayerEntity p) || !PassiveTomes.has(p, TOME_OF_AIMING)) return 1f;
        return bow ? BOW_SPEED : CROSSBOW_SPEED;
    }
}
