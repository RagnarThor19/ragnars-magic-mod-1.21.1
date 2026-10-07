package net.ragnar.ragnarsmagicmod.earthquake;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
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
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.lightningpath.LightningPath;
import org.joml.Vector3f;

import java.util.EnumMap;

/**
 * Tome of Earthquake: the caster digs in for three seconds while the ground rumbles and cracks open in fissures
 * running out from their feet, then the earth heaves. A shockwave rolls out through the ground to
 * {@link EarthquakeCast#RADIUS} blocks, flinging up and hurting everything it passes under, and two aftershocks
 * follow it. It works just as well in a cave: the floor heaves and rubble rains from the roof.
 * <p>
 * The world is never changed. The cracks, the heaving ground, the flung earth and the falling rubble are all drawn
 * on each client (EarthquakeClient) from a handful of packets, so it costs the server next to nothing. Everything
 * for it lives in this package.
 * <p>
 * To remove it:
 * <ol>
 *   <li>delete this package ({@code earthquake} and {@code earthquake/client})</li>
 *   <li>delete {@code Earthquake.register()} in RagnarsMagicMod and {@code EarthquakeClient.init()} in
 *       RagnarsMagicModClient</li>
 *   <li>delete {@code EARTHQUAKE} in SpellId</li>
 *   <li>delete {@code models/item/tome_of_earthquake.json} and the {@code earthquake} line in en_us.json</li>
 * </ol>
 */
public final class Earthquake {
    private Earthquake() {}

    public static final TomeItem TOME_OF_EARTHQUAKE = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_earthquake"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC), // Master
                    TomeTier.MASTER, SpellId.EARTHQUAKE, 50
            ).setCooldown(20 * 100) // 100 seconds
    );

    /** How far away people still see and hear it. */
    static final double VIEW_RANGE = 112;

    /** Server -> players nearby: someone at {@code center} has started to charge. {@code seed} shapes the fissures. */
    public record ChargePayload(int casterId, Vector3f center, long seed) implements CustomPayload {
        public static final Id<ChargePayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "earthquake_charge"));
        public static final PacketCodec<RegistryByteBuf, ChargePayload> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, ChargePayload::casterId,
                PacketCodecs.VECTOR3F, ChargePayload::center,
                PacketCodecs.VAR_LONG, ChargePayload::seed,
                ChargePayload::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /**
     * Server -> players nearby: the quake at {@code casterId}'s cast goes off. {@code pulse} is 0 for the main
     * shock, then 1 and 2 for the aftershocks. A {@code pulse} of -1 means the cast was cut short.
     */
    public record ShockPayload(int casterId, int pulse) implements CustomPayload {
        public static final Id<ShockPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "earthquake_shock"));
        public static final PacketCodec<RegistryByteBuf, ShockPayload> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, ShockPayload::casterId,
                PacketCodecs.VAR_INT, ShockPayload::pulse,
                ShockPayload::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    static void sendNear(ServerWorld world, Vec3d at, CustomPayload payload) {
        for (ServerPlayerEntity p : world.getPlayers()) {
            if (p.getPos().squaredDistanceTo(at) < VIEW_RANGE * VIEW_RANGE && ServerPlayNetworking.canSend(p, payload.getId())) {
                ServerPlayNetworking.send(p, payload);
            }
        }
    }

    public static void register() {
        // Lets staffs hand the tome back and puts it in the master tome loot pool
        ModItems.TOMES.computeIfAbsent(SpellId.EARTHQUAKE, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.MASTER, TOME_OF_EARTHQUAKE);
        Spells.register(SpellId.EARTHQUAKE, new EarthquakeSpell());
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.addAfter(LightningPath.TOME_OF_THE_LIGHTNING_PATH, TOME_OF_EARTHQUAKE));

        PayloadTypeRegistry.playS2C().register(ChargePayload.ID, ChargePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ShockPayload.ID, ShockPayload.CODEC);

        ServerTickEvents.END_WORLD_TICK.register(EarthquakeCast::tickAll);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> EarthquakeCast.abandon(handler.player));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> EarthquakeCast.abandonAll());
    }
}
