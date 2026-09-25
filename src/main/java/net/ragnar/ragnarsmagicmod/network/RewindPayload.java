package net.ragnar.ragnarsmagicmod.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;

import java.util.ArrayList;
import java.util.List;

/** Server -> caster: a Tome of Rewind started (with the path it will walk back along, newest first) or ended. */
public record RewindPayload(boolean active, List<Vec3d> path) implements CustomPayload {
    public static final Id<RewindPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "rewind"));
    public static final PacketCodec<RegistryByteBuf, RewindPayload> CODEC = PacketCodec.of(
            (p, buf) -> {
                buf.writeBoolean(p.active);
                buf.writeVarInt(p.path.size());
                for (Vec3d v : p.path) {
                    buf.writeDouble(v.x);
                    buf.writeDouble(v.y);
                    buf.writeDouble(v.z);
                }
            },
            buf -> {
                boolean active = buf.readBoolean();
                int n = Math.min(buf.readVarInt(), 1024);
                List<Vec3d> path = new ArrayList<>(n);
                for (int i = 0; i < n; i++) path.add(new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble()));
                return new RewindPayload(active, path);
            });

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(ID, CODEC);
    }

    public static void send(ServerPlayerEntity player, boolean active, List<Vec3d> path) {
        if (ServerPlayNetworking.canSend(player, ID)) ServerPlayNetworking.send(player, new RewindPayload(active, path));
    }
}
