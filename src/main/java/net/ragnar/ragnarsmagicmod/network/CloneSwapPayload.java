package net.ragnar.ragnarsmagicmod.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;

/** Server -> caster: you just swapped bodies with a Tome of Clones clone, glide the camera over. */
public record CloneSwapPayload() implements CustomPayload {
    public static final Id<CloneSwapPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "clone_swap"));
    public static final PacketCodec<RegistryByteBuf, CloneSwapPayload> CODEC = PacketCodec.unit(new CloneSwapPayload());

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(ID, CODEC);
    }

    public static void send(ServerPlayerEntity player) {
        if (ServerPlayNetworking.canSend(player, ID)) ServerPlayNetworking.send(player, new CloneSwapPayload());
    }
}
