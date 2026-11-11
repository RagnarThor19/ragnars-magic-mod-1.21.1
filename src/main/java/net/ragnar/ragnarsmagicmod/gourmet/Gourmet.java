package net.ragnar.ragnarsmagicmod.gourmet;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
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
 * Tome of the Gourmet: a passive tome. While it's in your inventory (or on a staff you carry), food is eaten the
 * instant you right-click it: no chewing animation, just the bite and the burp. That's all it does; what you can eat
 * and when (not when full, except golden apples and the like) is unchanged.
 * <p>
 * It's in GourmetItemStackMixin. To remove it: delete this package and that mixin (and its line in
 * ragnarsmagicmod.mixins.json), {@code Gourmet.register()} in RagnarsMagicMod, {@code GOURMET} in SpellId, it in
 * PassiveTomes.register, {@code models/item/tome_of_the_gourmet.json} and its en_us.json line.
 */
public final class Gourmet {
    private Gourmet() {}

    public static final TomeItem TOME_OF_THE_GOURMET = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_the_gourmet"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Passive
                    TomeTier.PASSIVE, SpellId.GOURMET, 0
            ).setCooldown(0)
    );

    public static void register() {
        ModItems.TOMES.computeIfAbsent(SpellId.GOURMET, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.PASSIVE, TOME_OF_THE_GOURMET);
        Spells.register(SpellId.GOURMET, new PassiveSpell("food is eaten instantly while this tome is in your inventory or on a staff you carry."));
    }

    /** True if {@code stack} is food that {@code user} eats instantly. Both sides call it, so they agree. */
    public static boolean instant(ItemStack stack, LivingEntity user) {
        return user instanceof PlayerEntity p && stack.contains(DataComponentTypes.FOOD) && PassiveTomes.has(p, TOME_OF_THE_GOURMET);
    }
}
