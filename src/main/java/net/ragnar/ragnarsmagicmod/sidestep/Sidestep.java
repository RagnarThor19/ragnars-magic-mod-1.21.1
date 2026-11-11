package net.ragnar.ragnarsmagicmod.sidestep;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
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

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tome of Sidestep: a passive tome. While it's in your inventory (or on a staff you carry), double-tap left or right
 * (A or D) to dart {@link #DISTANCE} blocks that way in a blink, on the ground or in the air, then stop dead: a dodge.
 * Whatever you were doing forward or back carries on through it. It's ready again after {@link #COOLDOWN} ticks.
 * <p>
 * Never while crouching, so bridging and sneaking at edges are safe; and not in water, on a wall, mid-pound or flying.
 * A quick airy whoosh, a fading trail of puffs where you were, and dust kicked up off the ground. The client moves the
 * player (see SidestepClient); the server checks it and shows it to everyone else. To remove it: delete this package,
 * {@code Sidestep.register()} in RagnarsMagicMod, {@code SidestepClient.init()} in RagnarsMagicModClient,
 * {@code SIDESTEP} in SpellId, it in PassiveTomes.register, {@code models/item/tome_of_sidestep.json} and its en_us.json
 * line.
 */
public final class Sidestep {
    private Sidestep() {}

    /** How far a sidestep takes you, and over how many ticks. */
    public static final double DISTANCE = 4.0;
    public static final int DASH_TICKS = 4;
    /** Ticks between sidesteps. */
    public static final int COOLDOWN = 16;
    /** The second tap has to come within this many ticks of the first (like double-tapping forward to sprint). */
    public static final int DOUBLE_TAP = 7;

    public static final TomeItem TOME_OF_SIDESTEP = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_sidestep"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Passive
                    TomeTier.PASSIVE, SpellId.SIDESTEP, 0
            ).setCooldown(0)
    );

    /** Client -> server: I've just sidestepped. */
    public record SidestepPayload(boolean right) implements CustomPayload {
        public static final Id<SidestepPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "sidestep"));
        public static final PacketCodec<RegistryByteBuf, SidestepPayload> CODEC =
                PacketCodec.tuple(PacketCodecs.BOOL, SidestepPayload::right, SidestepPayload::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** When each player last sidestepped (server ticks). */
    private static final Map<UUID, Integer> LAST = new HashMap<>();

    public static void register() {
        ModItems.TOMES.computeIfAbsent(SpellId.SIDESTEP, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.PASSIVE, TOME_OF_SIDESTEP);
        Spells.register(SpellId.SIDESTEP, new PassiveSpell("double-tap left or right to sidestep."));
        PayloadTypeRegistry.playC2S().register(SidestepPayload.ID, SidestepPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(SidestepPayload.ID, (payload, context) -> sidestep(context.player()));
    }

    /** True if {@code player} can sidestep at all: alive, not spectating, carrying the tome. */
    public static boolean canSidestep(PlayerEntity player) {
        return player.isAlive() && !player.isSpectator() && PassiveTomes.has(player, TOME_OF_SIDESTEP);
    }

    /** Server: {@code player} has sidestepped. Checks it, and lets everyone else see and hear it. */
    private static void sidestep(ServerPlayerEntity player) {
        if (!canSidestep(player)) return;
        int now = player.getServer().getTicks();
        Integer last = LAST.get(player.getUuid());
        if (last != null && now - last < COOLDOWN - 4) return; // a little slack for lag
        LAST.put(player.getUuid(), now);

        ServerWorld sw = player.getServerWorld();
        double x = player.getX(), y = player.getY(), z = player.getZ();
        sw.playSound(player, x, y, z, SoundEvents.ENTITY_BREEZE_SLIDE, SoundCategory.PLAYERS, 0.7f, 1.35f);
        for (ServerPlayerEntity viewer : sw.getPlayers()) {
            if (viewer == player || viewer.squaredDistanceTo(player) > 64 * 64) continue;
            sw.spawnParticles(viewer, ParticleTypes.POOF, false, x, y + 0.9, z, 6, 0.15, 0.5, 0.15, 0.01);
        }
    }
}
