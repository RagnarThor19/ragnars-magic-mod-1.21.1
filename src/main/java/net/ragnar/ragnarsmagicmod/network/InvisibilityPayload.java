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

/** Server -> everyone who can see them: player {@code entityId} is (or no longer is) completely invisible. */
public record InvisibilityPayload(int entityId, boolean hidden) implements CustomPayload {
    public static final Id<InvisibilityPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "invisibility"));
    public static final PacketCodec<RegistryByteBuf, InvisibilityPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, InvisibilityPayload::entityId,
            PacketCodecs.BOOL, InvisibilityPayload::hidden,
            InvisibilityPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(ID, CODEC);
    }

    public static void broadcast(ServerPlayerEntity player, boolean hidden) {
        InvisibilityPayload payload = new InvisibilityPayload(player.getId(), hidden);
        if (ServerPlayNetworking.canSend(player, ID)) ServerPlayNetworking.send(player, payload);
        for (ServerPlayerEntity p : PlayerLookup.tracking(player)) {
            if (p != player && ServerPlayNetworking.canSend(p, ID)) ServerPlayNetworking.send(p, payload);
        }
    }

    /** Tells one player about an invisible player they've just started tracking. */
    public static void sendTo(ServerPlayerEntity viewer, ServerPlayerEntity hidden) {
        if (ServerPlayNetworking.canSend(viewer, ID)) ServerPlayNetworking.send(viewer, new InvisibilityPayload(hidden.getId(), true));
    }
}
