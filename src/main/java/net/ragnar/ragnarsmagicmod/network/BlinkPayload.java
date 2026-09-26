package net.ragnar.ragnarsmagicmod.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;

/** Server -> caster: you just blinked, zip the camera over and flash the screen. */
public record BlinkPayload() implements CustomPayload {
    public static final Id<BlinkPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "blink"));
    public static final PacketCodec<RegistryByteBuf, BlinkPayload> CODEC = PacketCodec.unit(new BlinkPayload());

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(ID, CODEC);
    }

    public static void send(ServerPlayerEntity player) {
        if (ServerPlayNetworking.canSend(player, ID)) ServerPlayNetworking.send(player, new BlinkPayload());
    }
}
