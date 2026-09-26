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
 * Server -> everyone who can see the caster: player {@code entityId} starts a Tome of Ice Beam cast, charging for
 * {@code chargeTicks} and then firing for {@code fireTicks}. Both 0 means the beam was cut short.
 * The client draws it from there on its own, aimed wherever that player is looking.
 */
public record IceBeamPayload(int entityId, int chargeTicks, int fireTicks) implements CustomPayload {
    public static final Id<IceBeamPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "ice_beam"));
    public static final PacketCodec<RegistryByteBuf, IceBeamPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, IceBeamPayload::entityId,
            PacketCodecs.VAR_INT, IceBeamPayload::chargeTicks,
            PacketCodecs.VAR_INT, IceBeamPayload::fireTicks,
            IceBeamPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(ID, CODEC);
    }

    public static void broadcast(ServerPlayerEntity caster, int chargeTicks, int fireTicks) {
        IceBeamPayload payload = new IceBeamPayload(caster.getId(), chargeTicks, fireTicks);
        if (ServerPlayNetworking.canSend(caster, ID)) ServerPlayNetworking.send(caster, payload);
        for (ServerPlayerEntity p : PlayerLookup.tracking(caster)) {
            if (p != caster && ServerPlayNetworking.canSend(p, ID)) ServerPlayNetworking.send(p, payload);
        }
    }
}
