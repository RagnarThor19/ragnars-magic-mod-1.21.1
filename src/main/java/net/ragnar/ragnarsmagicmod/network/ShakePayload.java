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
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;

/** Server -> player: shake the camera this hard, fading out over this many ticks. */
public record ShakePayload(float strength, int ticks) implements CustomPayload {
    public static final Id<ShakePayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "shake"));
    public static final PacketCodec<RegistryByteBuf, ShakePayload> CODEC =
            PacketCodec.tuple(PacketCodecs.FLOAT, ShakePayload::strength, PacketCodecs.VAR_INT, ShakePayload::ticks, ShakePayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(ID, CODEC);
    }

    /** Shakes everyone near {@code at}: full strength within {@code radius}, fading out to twice that. */
    public static void around(ServerWorld world, Vec3d at, double radius, float strength, int ticks) {
        for (ServerPlayerEntity p : world.getPlayers()) {
            double d = p.getPos().distanceTo(at);
            float k = (float) MathHelper.clamp(1.0 - (d - radius) / radius, 0.0, 1.0);
            if (k > 0 && ServerPlayNetworking.canSend(p, ID)) ServerPlayNetworking.send(p, new ShakePayload(strength * k, ticks));
        }
    }
}
