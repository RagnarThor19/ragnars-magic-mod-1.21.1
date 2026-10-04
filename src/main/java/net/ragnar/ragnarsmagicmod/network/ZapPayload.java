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
import org.joml.Vector3f;

/**
 * Server -> players nearby: player {@code casterId} cast a Tome of Zap that struck {@code to}. {@code from} is
 * where the server thinks the staff is, used if the caster isn't loaded on the client. {@code hit} is 0 for
 * nothing (out of range), 1 for a block (with {@code normal} the face it hit) and 2 for a mob.
 */
public record ZapPayload(int casterId, Vector3f from, Vector3f to, int hit, Vector3f normal) implements CustomPayload {
    public static final int MISS = 0, BLOCK = 1, ENTITY = 2;
    private static final double RANGE = 96.0;

    public static final Id<ZapPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "zap"));
    public static final PacketCodec<RegistryByteBuf, ZapPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, ZapPayload::casterId,
            PacketCodecs.VECTOR3F, ZapPayload::from,
            PacketCodecs.VECTOR3F, ZapPayload::to,
            PacketCodecs.VAR_INT, ZapPayload::hit,
            PacketCodecs.VECTOR3F, ZapPayload::normal,
            ZapPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(ID, CODEC);
    }

    /** Sends it to everyone close enough to see either end of the bolt. */
    public static void broadcast(ServerWorld world, int casterId, Vec3d from, Vec3d to, int hit, Vec3d normal) {
        ZapPayload payload = new ZapPayload(casterId, from.toVector3f(), to.toVector3f(), hit, normal.toVector3f());
        for (ServerPlayerEntity p : world.getPlayers()) {
            if ((p.getPos().distanceTo(from) < RANGE || p.getPos().distanceTo(to) < RANGE) && ServerPlayNetworking.canSend(p, ID)) {
                ServerPlayNetworking.send(p, payload);
            }
        }
    }
}
