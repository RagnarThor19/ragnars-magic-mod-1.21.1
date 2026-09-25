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
 * Server -> nearby players: a Tome of Dimension Split just hit at {@code (x, y, z)}. The clients rebuild the
 * slashes from {@code seed} and {@code view} (see SplitPattern) and play the whole show.
 */
public record DimensionSplitPayload(double x, double y, double z, float vx, float vy, float vz, long seed)
        implements CustomPayload {
    public static final Id<DimensionSplitPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "dimension_split"));
    public static final PacketCodec<RegistryByteBuf, DimensionSplitPayload> CODEC = PacketCodec.of(
            (p, buf) -> {
                buf.writeDouble(p.x);
                buf.writeDouble(p.y);
                buf.writeDouble(p.z);
                buf.writeFloat(p.vx);
                buf.writeFloat(p.vy);
                buf.writeFloat(p.vz);
                buf.writeLong(p.seed);
            },
            buf -> new DimensionSplitPayload(buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readLong()));

    private static final double RANGE = 160.0;

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public Vec3d pos() {
        return new Vec3d(x, y, z);
    }

    public Vec3d view() {
        return new Vec3d(vx, vy, vz);
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(ID, CODEC);
    }

    public static void broadcast(ServerWorld world, Vec3d pos, Vec3d view, long seed) {
        DimensionSplitPayload payload = new DimensionSplitPayload(pos.x, pos.y, pos.z,
                (float) view.x, (float) view.y, (float) view.z, seed);
        for (ServerPlayerEntity p : world.getPlayers()) {
            if (p.squaredDistanceTo(pos) > RANGE * RANGE) continue;
            if (ServerPlayNetworking.canSend(p, ID)) ServerPlayNetworking.send(p, payload);
        }
    }
}
