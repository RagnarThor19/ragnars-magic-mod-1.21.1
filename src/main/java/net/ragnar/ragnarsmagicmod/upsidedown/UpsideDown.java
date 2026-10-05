package net.ragnar.ragnarsmagicmod.upsidedown;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.impulse.Impulse;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;

import java.util.EnumMap;

/**
 * Tome of Upside Down: flips your gravity, and only yours. The ceiling becomes your floor - you fall up to it, stand
 * on it, walk on it and jump "down" off it - and your view rolls over so it looks like you're on the ground. With no
 * ceiling over you, you fall into the sky, and far enough up it hurts like falling out of the bottom of the world.
 * Cast it again to flip back. Everyone else sees you hanging upside down. Everything for it lives in this package
 * and the {@code mixin/upsidedown} mixins.
 * <p>
 * The flip is a flag synced on the player (see UpsideDownPlayerMixin), so the server, your client and everyone
 * watching agree on it. It isn't saved: you come back the right way up after dying or rejoining.
 * <p>
 * To remove it:
 * <ol>
 *   <li>delete this package ({@code upsidedown} and {@code upsidedown/client}) and {@code mixin/upsidedown}, and the
 *       {@code upsidedown.*} lines in ragnarsmagicmod.mixins.json</li>
 *   <li>delete {@code UpsideDown.register()} in RagnarsMagicMod and {@code UpsideDownClient.init()} in
 *       RagnarsMagicModClient</li>
 *   <li>delete {@code UPSIDE_DOWN} in SpellId</li>
 *   <li>delete {@code models/item/tome_of_upside_down.json} and the {@code upside_down} line in en_us.json</li>
 * </ol>
 */
public final class UpsideDown {
    private UpsideDown() {}

    /** How far over the top of the world you can fall before the sky starts to hurt. */
    public static final int SKY_VOID = 64;

    public static final TomeItem TOME_OF_UPSIDE_DOWN = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_upside_down"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.UNCOMMON), // Beginner
                    TomeTier.BEGINNER, SpellId.UPSIDE_DOWN, 10
            ).setCooldown(20 * 2) // 2 seconds
    );

    /** Implemented on every player (see UpsideDownPlayerMixin). */
    public interface Flippable {
        boolean ragnarsmagicmod$isUpsideDown();

        void ragnarsmagicmod$setUpsideDown(boolean upsideDown);
    }

    /** True if {@code entity} is a player whose gravity is flipped. */
    public static boolean isFlipped(Entity entity) {
        return entity instanceof Flippable f && f.ragnarsmagicmod$isUpsideDown();
    }

    public static void setFlipped(PlayerEntity player, boolean flipped) {
        ((Flippable) player).ragnarsmagicmod$setUpsideDown(flipped);
    }

    public static void register() {
        // Lets staffs hand the tome back and puts it in the beginner tome loot pool
        ModItems.TOMES.computeIfAbsent(SpellId.UPSIDE_DOWN, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.BEGINNER, TOME_OF_UPSIDE_DOWN);
        Spells.register(SpellId.UPSIDE_DOWN, new UpsideDownSpell());
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.addAfter(Impulse.TOME_OF_IMPULSE, TOME_OF_UPSIDE_DOWN));
    }
}
