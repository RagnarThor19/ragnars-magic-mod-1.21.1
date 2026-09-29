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

/** Server -> one player: you are slippery for {@code ticks} more ticks (0 = not any more). */
public record SlipperyPayload(int ticks) implements CustomPayload {
    public static final Id<SlipperyPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "slippery"));
    public static final PacketCodec<RegistryByteBuf, SlipperyPayload> CODEC =
            PacketCodec.tuple(PacketCodecs.VAR_INT, SlipperyPayload::ticks, SlipperyPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(ID, CODEC);
    }

    public static void send(ServerPlayerEntity player, int ticks) {
        if (ServerPlayNetworking.canSend(player, ID)) ServerPlayNetworking.send(player, new SlipperyPayload(ticks));
    }
}
