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

/** Server -> everyone who can see them: player {@code entityId} is a fairy for {@code ticks} more ticks (0 = turned back). */
public record FairyPayload(int entityId, int ticks) implements CustomPayload {
    public static final Id<FairyPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "fairy"));
    public static final PacketCodec<RegistryByteBuf, FairyPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, FairyPayload::entityId,
            PacketCodecs.VAR_INT, FairyPayload::ticks,
            FairyPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(ID, CODEC);
    }

    public static void broadcast(ServerPlayerEntity player, int ticks) {
        FairyPayload payload = new FairyPayload(player.getId(), ticks);
        if (ServerPlayNetworking.canSend(player, ID)) ServerPlayNetworking.send(player, payload);
        for (ServerPlayerEntity p : PlayerLookup.tracking(player)) {
            if (p != player && ServerPlayNetworking.canSend(p, ID)) ServerPlayNetworking.send(p, payload);
        }
    }

    /** Tells one player about a fairy they've just started seeing. */
    public static void sendTo(ServerPlayerEntity viewer, ServerPlayerEntity fairy, int ticks) {
        if (ServerPlayNetworking.canSend(viewer, ID)) ServerPlayNetworking.send(viewer, new FairyPayload(fairy.getId(), ticks));
    }
}
