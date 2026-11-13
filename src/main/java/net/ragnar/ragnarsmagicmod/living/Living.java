package net.ragnar.ragnarsmagicmod.living;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectCategory;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.PassiveTomes;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.PassiveSpell;
import net.ragnar.ragnarsmagicmod.item.spell.RewindSpell;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tome of Living: a passive tome. While it's in your inventory (or on a staff you carry), the hit that should have
 * killed you never does. Instead, in that same instant, you're snapped back to where you were {@link #SECONDS_BACK}
 * seconds earlier - your position, view, health, hunger, fire and air as they were then - with a single strange note
 * in your ears and nothing else to explain it. Then the tome goes quiet for {@link #COOLDOWN} (an hour), by far the
 * longest cooldown in the game.
 * <p>
 * It hooks {@code ALLOW_DEATH}, which runs inside the fatal hit before anything about dying happens: no death screen,
 * no death message, no dropped items, no death statistic. As far as the game is concerned you never died. On top of
 * that:
 * <ul>
 *   <li>you come back with at least {@link #MIN_HEALTH} health, even if you were lower five seconds ago;</li>
 *   <li>harmful effects picked up since are cleared (a Wither effect would otherwise just finish the job);</li>
 *   <li>for {@link #GRACE_TICKS} ticks nothing can hurt you, so the same blow can't land twice in one moment.</li>
 * </ul>
 * The rewind uses the Tome of Rewind's memory (see {@link RewindSpell#snapBack}), so it reaches back only within the
 * world you're in. With nothing remembered (you'd only just arrived) you stay where you are, unless that's the void,
 * in which case you're put back on solid ground at the world spawn.
 * <p>
 * A totem of undying in your hand is used first, so the tome is saved for when you've nothing else. {@code /kill}
 * still kills. The cooldown sits on the tome item, so TomeCooldowns keeps it across logging out and dying.
 * <p>
 * To remove it: delete this package, {@code Living.register()} in RagnarsMagicMod, {@code LIVING} in SpellId, it in
 * PassiveTomes.register, {@code models/item/tome_of_living.json}, {@code sounds/tome_of_living.ogg}, its sounds.json
 * entry and its en_us.json line.
 */
public final class Living {
    private Living() {}

    public static final int SECONDS_BACK = 5;
    /** One hour. */
    public static final int COOLDOWN = 20 * 60 * 60;
    public static final float MIN_HEALTH = 4.0f;
    public static final int GRACE_TICKS = 20;

    public static final TomeItem TOME_OF_LIVING = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_living"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Passive
                    TomeTier.PASSIVE, SpellId.LIVING, 0
            ).setCooldown(COOLDOWN)
    );

    public static final Identifier SOUND_ID = Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_living");
    public static final SoundEvent SOUND = Registry.register(Registries.SOUND_EVENT, SOUND_ID, SoundEvent.of(SOUND_ID));

    /** Server tick until which each saved player can't be hurt. */
    private static final Map<UUID, Integer> GRACE = new HashMap<>();

    public static void register() {
        ModItems.TOMES.computeIfAbsent(SpellId.LIVING, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.PASSIVE, TOME_OF_LIVING);
        Spells.register(SpellId.LIVING, new PassiveSpell("Once an hour, the hit that should kill you... doesn't."));

        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) ->
                !(entity instanceof ServerPlayerEntity player) || !trySave(player, source));
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !(entity instanceof ServerPlayerEntity player) || !inGrace(player) || source.isOf(DamageTypes.GENERIC_KILL));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> GRACE.remove(handler.player.getUuid()));
    }

    /** True if the tome is ready to save {@code player}: carried, and off cooldown. */
    public static boolean isReady(PlayerEntity player) {
        return PassiveTomes.has(player, TOME_OF_LIVING) && !player.getItemCooldownManager().isCoolingDown(TOME_OF_LIVING);
    }

    /** True while {@code player} is in the moment of grace right after being saved. */
    public static boolean inGrace(ServerPlayerEntity player) {
        if (GRACE.isEmpty()) return false;
        Integer until = GRACE.get(player.getUuid());
        if (until == null) return false;
        if (player.getServer().getTicks() < until) return true;
        GRACE.remove(player.getUuid());
        return false;
    }

    /**
     * Called as {@code player} takes a fatal hit. If the tome can save them it does, there and then, and returns
     * true (the death is cancelled); otherwise returns false and the death goes ahead.
     */
    public static boolean trySave(ServerPlayerEntity player, DamageSource source) {
        if (player.isSpectator() || source.isOf(DamageTypes.GENERIC_KILL)) return false;
        if (player.isHolding(Items.TOTEM_OF_UNDYING)) return false; // the totem goes first
        if (!isReady(player)) return false;

        player.getItemCooldownManager().set(TOME_OF_LIVING, COOLDOWN);

        if (!RewindSpell.snapBack(player, SECONDS_BACK * 20)) {
            // Nothing to go back to: stay put
            player.setVelocity(Vec3d.ZERO);
            player.velocityModified = true;
            player.fallDistance = 0;
        }
        // Never left in the void (five seconds back can still be mid-fall into it)
        ServerWorld world = player.getServerWorld();
        if (player.getY() < world.getBottomY()) {
            BlockPos spawn = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, world.getSpawnPos());
            player.networkHandler.requestTeleport(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
                    player.getYaw(), player.getPitch(), EnumSet.noneOf(PositionFlag.class));
            player.setVelocity(Vec3d.ZERO);
            player.fallDistance = 0;
        }
        if (player.getHealth() < MIN_HEALTH) player.setHealth(MIN_HEALTH);
        clearHarmful(player);
        GRACE.put(player.getUuid(), player.getServer().getTicks() + GRACE_TICKS);

        // For them alone: one note, no particles, no message
        player.playSoundToPlayer(SOUND, SoundCategory.PLAYERS, 1.0f, 1.0f);
        return true;
    }

    private static void clearHarmful(ServerPlayerEntity player) {
        List<RegistryEntry<StatusEffect>> harmful = new ArrayList<>();
        for (StatusEffectInstance e : player.getStatusEffects()) {
            if (e.getEffectType().value().getCategory() == StatusEffectCategory.HARMFUL) harmful.add(e.getEffectType());
        }
        harmful.forEach(player::removeStatusEffect);
    }
}
