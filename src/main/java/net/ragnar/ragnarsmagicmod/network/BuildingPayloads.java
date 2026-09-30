package net.ragnar.ragnarsmagicmod.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.spell.BuildingSpell;

import java.util.List;

/** Packets for the Tome of Building. */
public final class BuildingPayloads {
    private BuildingPayloads() {}

    /**
     * Client -> server, sent just before the staff use: exactly what the player saw outlined (where, which face,
     * which way was forward) and their build settings. The server checks all of it again.
     */
    public record Request(BlockPos anchor, int face, int forward, NbtCompound settings) implements CustomPayload {
        public static final Id<Request> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "build_request"));
        public static final PacketCodec<RegistryByteBuf, Request> CODEC = PacketCodec.tuple(
                BlockPos.PACKET_CODEC, Request::anchor,
                PacketCodecs.VAR_INT, Request::face,
                PacketCodecs.VAR_INT, Request::forward,
                PacketCodecs.NBT_COMPOUND, Request::settings,
                Request::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** One block about to pop in: where, what, and how many ticks from now it starts growing. */
    public record Ghost(BlockPos pos, int stateId, int delay) {
        public static final PacketCodec<RegistryByteBuf, Ghost> CODEC = PacketCodec.tuple(
                BlockPos.PACKET_CODEC, Ghost::pos,
                PacketCodecs.VAR_INT, Ghost::stateId,
                PacketCodecs.VAR_INT, Ghost::delay,
                Ghost::new);
    }

    /** Server -> nearby players: play the build animation. {@code own} is true for the builder. */
    public record Animate(boolean own, List<Ghost> ghosts) implements CustomPayload {
        public static final Id<Animate> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "build_animate"));
        public static final PacketCodec<RegistryByteBuf, Animate> CODEC = PacketCodec.tuple(
                PacketCodecs.BOOL, Animate::own,
                Ghost.CODEC.collect(PacketCodecs.toList(1024)), Animate::ghosts,
                Animate::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(Request.ID, Request.CODEC);
        PayloadTypeRegistry.playS2C().register(Animate.ID, Animate.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Request.ID, (payload, context) ->
                BuildingSpell.onRequest(context.player(), payload));
    }

    public static void sendAnimate(ServerPlayerEntity player, boolean own, List<Ghost> ghosts) {
        if (ServerPlayNetworking.canSend(player, Animate.ID)) ServerPlayNetworking.send(player, new Animate(own, ghosts));
    }
}
