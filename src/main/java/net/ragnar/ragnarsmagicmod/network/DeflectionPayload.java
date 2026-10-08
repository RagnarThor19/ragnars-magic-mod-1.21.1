package net.ragnar.ragnarsmagicmod.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;

/**
 * Server -> the caster and everyone who can see them: a Tome of Deflection pane. {@code ticks > 0} puts one up in front
 * of player {@code entityId} for that many ticks (including its fade); {@code ticks == 0} takes it down. With
 * {@code hit}, something just struck it at ({@code u}, {@code v}) blocks across and up from its middle.
 */
public record DeflectionPayload(int entityId, int ticks, boolean hit, float u, float v) implements CustomPayload {
    public static final Id<DeflectionPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "deflection"));
    public static final PacketCodec<RegistryByteBuf, DeflectionPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, DeflectionPayload::entityId,
            PacketCodecs.VAR_INT, DeflectionPayload::ticks,
            PacketCodecs.BOOL, DeflectionPayload::hit,
            PacketCodecs.FLOAT, DeflectionPayload::u,
            PacketCodecs.FLOAT, DeflectionPayload::v,
            DeflectionPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(ID, CODEC);
    }

    public static void broadcast(ServerPlayerEntity player, DeflectionPayload payload) {
        if (ServerPlayNetworking.canSend(player, ID)) ServerPlayNetworking.send(player, payload);
        for (ServerPlayerEntity p : PlayerLookup.tracking(player)) {
            if (p != player && ServerPlayNetworking.canSend(p, ID)) ServerPlayNetworking.send(p, payload);
        }
    }
}
