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

/**
 * Server -> everyone in the world: player {@code playerId} has the Tome of Clouds for another
 * {@code ticksLeft} ticks (0 = the spell is over), and {@code shown} says whether they are actually
 * flying on the cloud right now. The cloud itself is drawn client side.
 */
public record CloudPayload(int playerId, int ticksLeft, boolean shown) implements CustomPayload {
    public static final Id<CloudPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "cloud"));
    public static final PacketCodec<RegistryByteBuf, CloudPayload> CODEC =
            PacketCodec.tuple(PacketCodecs.VAR_INT, CloudPayload::playerId, PacketCodecs.VAR_INT, CloudPayload::ticksLeft,
                    PacketCodecs.BOOL, CloudPayload::shown, CloudPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(ID, CODEC);
    }

    public static void broadcast(ServerWorld world, int playerId, int ticksLeft, boolean shown) {
        CloudPayload payload = new CloudPayload(playerId, ticksLeft, shown);
        for (ServerPlayerEntity p : PlayerLookup.world(world)) {
            if (ServerPlayNetworking.canSend(p, ID)) ServerPlayNetworking.send(p, payload);
        }
    }
}
