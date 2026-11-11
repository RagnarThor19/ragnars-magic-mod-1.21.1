package net.ragnar.ragnarsmagicmod.swimming;

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
 * Tome of Swimming: a passive tome. While it's in your inventory (or on a staff you carry) you breathe underwater
 * (Water Breathing, kept topped up like Night Vision) and swim {@link #SPEED}x as fast, sprint-swimming and swimming
 * up included. Sprint-swimming leaves a little trail of bubbles, with a soft dolphin whoosh as you set off (see
 * SwimmingClient).
 * <p>
 * Nothing changes out of water, and none of the other movement tomes work in water, so there's nothing to clash with.
 * The speed is in PassiveFluidMovementMixin. To remove it: delete this package, {@code Swimming.register()} in
 * RagnarsMagicMod, {@code SwimmingClient.init()} in RagnarsMagicModClient, its lines in PassiveFluidMovementMixin,
 * {@code SWIMMING} in SpellId, it in PassiveTomes.register, {@code models/item/tome_of_swimming.json} and its en_us.json
 * line.
 */
public final class Swimming {
    private Swimming() {}

    /** How many times as fast you swim. */
    public static final float SPEED = 2.0f;
    private static final int CHECK_EVERY = 10;

    public static final TomeItem TOME_OF_SWIMMING = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_swimming"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Passive
                    TomeTier.PASSIVE, SpellId.SWIMMING, 0
            ).setCooldown(0)
    );

    public static void register() {
        ModItems.TOMES.computeIfAbsent(SpellId.SWIMMING, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.PASSIVE, TOME_OF_SWIMMING);
        Spells.register(SpellId.SWIMMING, new PassiveSpell("breathe underwater and swim twice as fast while this tome is in your inventory or on a staff you carry."));
        ServerTickEvents.END_SERVER_TICK.register(Swimming::tick);
    }

    private static void tick(MinecraftServer server) {
        if (server.getTicks() % CHECK_EVERY != 0) return;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) update(player);
    }

    /** Gives {@code player} Water Breathing if they carry the tome, or takes the tome's away if they don't. */
    public static void update(PlayerEntity player) {
        PassiveEffect.keep(player, StatusEffects.WATER_BREATHING, PassiveTomes.has(player, TOME_OF_SWIMMING));
    }

    /** How hard {@code player} pushes through water, given vanilla's {@code speed}. Both sides call it each tick in water. */
    public static float waterSpeed(PlayerEntity player, float speed) {
        return PassiveTomes.has(player, TOME_OF_SWIMMING) ? speed * SPEED : speed;
    }

    /** Extra upward push when {@code player} swims up (holding jump) in water; vanilla's is 0.04. */
    public static double extraUp(PlayerEntity player) {
        return PassiveTomes.has(player, TOME_OF_SWIMMING) ? 0.04 * (SPEED - 1) : 0.0;
    }
}
