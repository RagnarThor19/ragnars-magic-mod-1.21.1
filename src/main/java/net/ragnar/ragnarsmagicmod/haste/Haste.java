package net.ragnar.ragnarsmagicmod.haste;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
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
import net.ragnar.ragnarsmagicmod.util.PassiveEffect;

import java.util.EnumMap;

/**
 * Tome of Haste: a passive tome. While it's in your inventory (or on a staff you carry) you have Haste II, so you mine
 * (and swing) faster - kept topped up like Night Vision, just the icon, no particles. A beacon's Haste II is left
 * alone, and a weaker Haste is lifted to II. To remove it: delete this package, {@code Haste.register()} in
 * RagnarsMagicMod, {@code HASTE} in SpellId, it in PassiveTomes.register, {@code models/item/tome_of_haste.json} and its
 * en_us.json line.
 */
public final class Haste {
    private Haste() {}

    /** Haste II (amplifiers count from 0). */
    public static final int AMPLIFIER = 1;
    private static final int CHECK_EVERY = 10;

    public static final TomeItem TOME_OF_HASTE = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_haste"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Passive
                    TomeTier.PASSIVE, SpellId.HASTE, 0
            ).setCooldown(0)
    );

    public static void register() {
        ModItems.TOMES.computeIfAbsent(SpellId.HASTE, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.PASSIVE, TOME_OF_HASTE);
        Spells.register(SpellId.HASTE, new PassiveSpell("Haste II while this tome is in your inventory or on a staff you carry."));
        ServerTickEvents.END_SERVER_TICK.register(Haste::tick);
    }

    private static void tick(MinecraftServer server) {
        if (server.getTicks() % CHECK_EVERY != 0) return;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) update(player);
    }

    /** Gives {@code player} Haste II if they carry the tome, or takes the tome's away if they don't. */
    public static void update(PlayerEntity player) {
        PassiveEffect.keep(player, StatusEffects.HASTE, AMPLIFIER, PassiveTomes.has(player, TOME_OF_HASTE));
    }
}
