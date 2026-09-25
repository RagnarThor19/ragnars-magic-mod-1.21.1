package net.ragnar.ragnarsmagicmod.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;

/** Server -> everyone in the world: player {@code playerId} has Tome of Wings wings for {@code ticks} more ticks (0 = gone). */
public record WingsPayload(int playerId, int ticks) implements CustomPayload {
    public static final Id<WingsPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "wings"));
    public static final PacketCodec<RegistryByteBuf, WingsPayload> CODEC =
            PacketCodec.tuple(PacketCodecs.VAR_INT, WingsPayload::playerId, PacketCodecs.VAR_INT, WingsPayload::ticks, WingsPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(ID, CODEC);
    }

    public static void broadcast(ServerWorld world, int playerId, int ticks) {
        WingsPayload payload = new WingsPayload(playerId, ticks);
        for (ServerPlayerEntity p : PlayerLookup.world(world)) {
            if (ServerPlayNetworking.canSend(p, ID)) ServerPlayNetworking.send(p, payload);
        }
    }
}
