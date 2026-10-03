package net.ragnar.ragnarsmagicmod.jaunting;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import org.joml.Vector3f;

/**
 * Server -> players nearby: someone just jaunted from {@code from} to {@code to}. Draws the lightning streak
 * between them ({@code streak} is false when they changed dimension), and for the jaunter themself
 * ({@code self}) the camera zip and the flash.
 */
public record JauntPayload(Vector3f from, Vector3f to, boolean streak, boolean self) implements CustomPayload {
    public static final Id<JauntPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "jaunt"));
    public static final PacketCodec<RegistryByteBuf, JauntPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VECTOR3F, JauntPayload::from,
            PacketCodecs.VECTOR3F, JauntPayload::to,
            PacketCodecs.BOOL, JauntPayload::streak,
            PacketCodecs.BOOL, JauntPayload::self,
            JauntPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    static void register() {
        PayloadTypeRegistry.playS2C().register(ID, CODEC);
    }

    static void send(ServerPlayerEntity player, JauntPayload payload) {
        if (ServerPlayNetworking.canSend(player, ID)) ServerPlayNetworking.send(player, payload);
    }
}
