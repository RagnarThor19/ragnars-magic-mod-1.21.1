package net.ragnar.ragnarsmagicmod.nightvision;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.effect.StatusEffectInstance;
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

import java.util.EnumMap;

/**
 * Tome of Night Vision: a passive tome. While it's in your inventory you have Night Vision; put it away and it goes.
 * It's kept topped up well above the point where Night Vision starts to flicker, so the screen never blinks, and a
 * potion's Night Vision is never touched.
 * <p>
 * To remove it: delete this package, {@code NightVision.register()} in RagnarsMagicMod, {@code NIGHT_VISION} in
 * SpellId, it in PassiveTomes.register, {@code models/item/tome_of_night_vision.json} and its en_us.json line.
 */
public final class NightVision {
    private NightVision() {}

    /** How often it's topped up; each top-up lasts {@link #TOP_UP}, and it's renewed once below {@link #RENEW_BELOW}. */
    private static final int CHECK_EVERY = 10, TOP_UP = 400, RENEW_BELOW = 300;

    public static final TomeItem TOME_OF_NIGHT_VISION = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_night_vision"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Passive
                    TomeTier.PASSIVE, SpellId.NIGHT_VISION, 0
            ).setCooldown(0)
    );

    public static void register() {
        ModItems.TOMES.computeIfAbsent(SpellId.NIGHT_VISION, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.PASSIVE, TOME_OF_NIGHT_VISION);
        Spells.register(SpellId.NIGHT_VISION, new PassiveSpell("Night Vision while this tome is in your inventory or on a staff you carry."));
        ServerTickEvents.END_SERVER_TICK.register(NightVision::tick);
    }

    private static void tick(MinecraftServer server) {
        if (server.getTicks() % CHECK_EVERY != 0) return;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) update(player);
    }

    /** Gives {@code player} Night Vision if they carry the tome, or takes the tome's away if they don't. */
    public static void update(PlayerEntity player) {
        if (!player.isAlive() || player.isSpectator()) return;
        StatusEffectInstance current = player.getStatusEffect(StatusEffects.NIGHT_VISION);
        if (PassiveTomes.has(player, TOME_OF_NIGHT_VISION)) {
            // Ambient and particle-free: just the icon. A potion's longer Night Vision is left to run.
            if (current == null || ours(current) && current.getDuration() < RENEW_BELOW) {
                player.addStatusEffect(new StatusEffectInstance(StatusEffects.NIGHT_VISION, TOP_UP, 0, true, false, true));
            }
        } else if (current != null && ours(current)) {
            player.removeStatusEffect(StatusEffects.NIGHT_VISION);
        }
    }

    /** True for the Night Vision this tome hands out (potions' is never ambient). */
    private static boolean ours(StatusEffectInstance effect) {
        return effect.getAmplifier() == 0 && effect.isAmbient() && !effect.shouldShowParticles() && effect.getDuration() <= TOP_UP;
    }
}
