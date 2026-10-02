package net.ragnar.ragnarsmagicmod.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;

/**
 * Server -> nearby players: a Tome of Reckoning wave starts here, spreading at {@code speed} blocks a tick out to
 * {@code radius}. One packet per wave; each client draws the ring itself (see ReckoningClient).
 */
public record ReckoningPayload(double x, double y, double z, float speed, float radius) implements CustomPayload {
    public static final Id<ReckoningPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "reckoning"));
    public static final PacketCodec<RegistryByteBuf, ReckoningPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.DOUBLE, ReckoningPayload::x, PacketCodecs.DOUBLE, ReckoningPayload::y, PacketCodecs.DOUBLE, ReckoningPayload::z,
            PacketCodecs.FLOAT, ReckoningPayload::speed, PacketCodecs.FLOAT, ReckoningPayload::radius, ReckoningPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(ID, CODEC);
    }

    /** Tells everyone close enough to see it. */
    public static void around(ServerWorld world, Vec3d at, float speed, float radius) {
        ReckoningPayload payload = new ReckoningPayload(at.x, at.y, at.z, speed, radius);
        for (ServerPlayerEntity p : world.getPlayers()) {
            if (p.squaredDistanceTo(at) < 96 * 96 && ServerPlayNetworking.canSend(p, ID)) ServerPlayNetworking.send(p, payload);
        }
    }
}
