package net.ragnar.ragnarsmagicmod.shadowhands;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.balllightning.BallLightning;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * Tome of Unseen Hands: where you look, a pool of living shadow creeps out across the ground for three seconds, to
 * {@link #RADIUS} blocks, a heartbeat quickening under it. Then the hands come: black arms burst out of the pool and
 * seize everything standing in it, lift it off its feet and crush it - {@link #PULSES} squeezes over four seconds,
 * {@link #DAMAGE_PER_PULSE} each, through any armour - before sinking back into the dark. Nothing caught can move.
 * Everything for it lives in this package.
 * <p>
 * The server runs the timeline (see Grasp); the client draws the pool and the hands from it (see ShadowHandsClient).
 * <p>
 * To remove it:
 * <ol>
 *   <li>delete this package ({@code shadowhands} and {@code shadowhands/client})</li>
 *   <li>delete {@code ShadowHands.register()} in RagnarsMagicMod and {@code ShadowHandsClient.init()} in
 *       RagnarsMagicModClient</li>
 *   <li>delete {@code SHADOW_HANDS} in SpellId</li>
 *   <li>delete {@code models/item/tome_of_unseen_hands.json} and the {@code unseen_hands} line in en_us.json</li>
 * </ol>
 */
public final class ShadowHands {
    private ShadowHands() {}

    public static final float RADIUS = 10f;
    /** How far you can cast it. */
    public static final double REACH = 48.0;
    /** Ticks the pool takes to creep out, then the squeezing, then how long the hands take to sink and the pool to drain. */
    public static final int WAVE_TICKS = 60, SQUEEZE_TICKS = 80, AFTER_TICKS = 40;
    public static final int PULSES = 5;
    public static final int PULSE_EVERY = SQUEEZE_TICKS / PULSES;
    public static final float DAMAGE_PER_PULSE = 5f;
    /** Ticks for the hands to burst up out of the ground and close on their prey. */
    public static final int RISE_TICKS = 8;

    public static final TomeItem TOME_OF_SHADOW_HANDS = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_unseen_hands"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC), // Master
                    TomeTier.MASTER, SpellId.SHADOW_HANDS, 35
            ).setCooldown(20 * 40) // 40 seconds
    );

    /** How far everyone can see it from. */
    private static final double VIEW_RANGE = 128.0;

    static final List<Grasp> ACTIVE = new ArrayList<>();
    private static int nextId;

    /** Server -> players nearby: shadow number {@code id} starts creeping out from {@code center}. */
    public record StartPayload(int id, Vector3f center, float radius) implements CustomPayload {
        public static final Id<StartPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "shadow_hands_start"));
        public static final PacketCodec<RegistryByteBuf, StartPayload> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, StartPayload::id,
                PacketCodecs.VECTOR3F, StartPayload::center,
                PacketCodecs.FLOAT, StartPayload::radius,
                StartPayload::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Server -> players nearby: shadow number {@code id} has seized these entities (by network id). */
    public record GrabPayload(int id, List<Integer> victims) implements CustomPayload {
        public static final Id<GrabPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "shadow_hands_grab"));
        public static final PacketCodec<RegistryByteBuf, GrabPayload> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, GrabPayload::id,
                PacketCodecs.VAR_INT.collect(PacketCodecs.toList()), GrabPayload::victims,
                GrabPayload::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    static int nextId() {
        return nextId++;
    }

    static void send(ServerWorld world, Vec3d near, CustomPayload payload) {
        for (ServerPlayerEntity p : world.getPlayers()) {
            if (p.getPos().squaredDistanceTo(near) < VIEW_RANGE * VIEW_RANGE && ServerPlayNetworking.canSend(p, payload.getId())) {
                ServerPlayNetworking.send(p, payload);
            }
        }
    }

    public static void register() {
        // Lets staffs hand the tome back and puts it in the master tome loot pool
        ModItems.TOMES.computeIfAbsent(SpellId.SHADOW_HANDS, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.MASTER, TOME_OF_SHADOW_HANDS);
        Spells.register(SpellId.SHADOW_HANDS, new ShadowHandsSpell());
        PayloadTypeRegistry.playS2C().register(StartPayload.ID, StartPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(GrabPayload.ID, GrabPayload.CODEC);
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.addAfter(BallLightning.TOME_OF_BALL_LIGHTNING, TOME_OF_SHADOW_HANDS));
        ServerTickEvents.END_SERVER_TICK.register(server -> ACTIVE.removeIf(g -> !g.tick()));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            ACTIVE.forEach(Grasp::release);
            ACTIVE.clear();
        });
    }
}
