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
 * Server -> everyone who can see the caster: player {@code entityId} starts a Tome of Spraying barrage (the halo
 * forms for {@code summonTicks}, then fires for {@code fireTicks}). Both 0 means it was cut short. The client draws
 * the halo and plays the recoil from there on its own, following the same firing order as the server.
 */
public record SprayPayload(int entityId, int summonTicks, int fireTicks) implements CustomPayload {
    public static final Id<SprayPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "spray"));
    public static final PacketCodec<RegistryByteBuf, SprayPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, SprayPayload::entityId,
            PacketCodecs.VAR_INT, SprayPayload::summonTicks,
            PacketCodecs.VAR_INT, SprayPayload::fireTicks,
            SprayPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(ID, CODEC);
    }

    public static void broadcast(ServerPlayerEntity caster, int summonTicks, int fireTicks) {
        SprayPayload payload = new SprayPayload(caster.getId(), summonTicks, fireTicks);
        if (ServerPlayNetworking.canSend(caster, ID)) ServerPlayNetworking.send(caster, payload);
        for (ServerPlayerEntity p : PlayerLookup.tracking(caster)) {
            if (p != caster && ServerPlayNetworking.canSend(p, ID)) ServerPlayNetworking.send(p, payload);
        }
    }
}
