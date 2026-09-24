package net.ragnar.ragnarsmagicmod.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.spell.TelekinesisSpell;

/** Packets for the Tome of Telekinesis's hold-and-scroll. */
public final class TelekinesisPayloads {
    private TelekinesisPayloads() {}

    /** Server -> client: whether the player is currently holding something, so the wheel moves it. */
    public record Holding(boolean active) implements CustomPayload {
        public static final Id<Holding> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "telekinesis_holding"));
        public static final PacketCodec<RegistryByteBuf, Holding> CODEC =
                PacketCodec.tuple(PacketCodecs.BOOL, Holding::active, Holding::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Client -> server: mouse wheel steps while holding (positive pushes away). */
    public record Scroll(int steps) implements CustomPayload {
        public static final Id<Scroll> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "telekinesis_scroll"));
        public static final PacketCodec<RegistryByteBuf, Scroll> CODEC =
                PacketCodec.tuple(PacketCodecs.VAR_INT, Scroll::steps, Scroll::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(Holding.ID, Holding.CODEC);
        PayloadTypeRegistry.playC2S().register(Scroll.ID, Scroll.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Scroll.ID, (payload, context) ->
                TelekinesisSpell.onScroll(context.player(), payload.steps()));
    }

    public static void sendHolding(ServerPlayerEntity player, boolean active) {
        if (ServerPlayNetworking.canSend(player, Holding.ID)) {
            ServerPlayNetworking.send(player, new Holding(active));
        }
    }
}
