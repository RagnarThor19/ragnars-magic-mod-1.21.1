package net.ragnar.ragnarsmagicmod.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;

/**
 * Server -> everyone in the world: the Tome of Dusk and Dawn is turning the sky from {@code startTime} to
 * {@code endTime}, cast at (x, y, z). Clients move their own sky smoothly and draw the pillar and sigil.
 */
public record DayShiftPayload(long startTime, long endTime, boolean toNight, double x, double y, double z)
        implements CustomPayload {
    public static final Id<DayShiftPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "day_shift"));
    public static final PacketCodec<RegistryByteBuf, DayShiftPayload> CODEC = PacketCodec.of(
            (p, buf) -> {
                buf.writeLong(p.startTime);
                buf.writeLong(p.endTime);
                buf.writeBoolean(p.toNight);
                buf.writeDouble(p.x);
                buf.writeDouble(p.y);
                buf.writeDouble(p.z);
            },
            buf -> new DayShiftPayload(buf.readLong(), buf.readLong(), buf.readBoolean(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble()));

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public Vec3d pos() {
        return new Vec3d(x, y, z);
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(ID, CODEC);
    }

    public static void broadcast(ServerWorld world, DayShiftPayload payload) {
        for (ServerPlayerEntity p : world.getPlayers()) {
            if (ServerPlayNetworking.canSend(p, ID)) ServerPlayNetworking.send(p, payload);
        }
    }
}
