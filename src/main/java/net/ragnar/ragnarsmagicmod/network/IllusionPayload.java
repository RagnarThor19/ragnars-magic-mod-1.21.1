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

/** Server -> caster: you're a Tome of Illusion ghost for {@code ticks} more ticks (0 = you've been revealed). */
public record IllusionPayload(int ticks) implements CustomPayload {
    public static final Id<IllusionPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "illusion"));
    public static final PacketCodec<RegistryByteBuf, IllusionPayload> CODEC =
            PacketCodec.tuple(PacketCodecs.VAR_INT, IllusionPayload::ticks, IllusionPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(ID, CODEC);
    }

    public static void send(ServerPlayerEntity player, int ticks) {
        if (ServerPlayNetworking.canSend(player, ID)) ServerPlayNetworking.send(player, new IllusionPayload(ticks));
    }
}
