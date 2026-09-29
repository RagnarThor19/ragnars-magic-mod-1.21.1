package net.ragnar.ragnarsmagicmod.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;

/**
 * Server -> the one haunted player (Tome of Paranoia): Test::Entity shows up at {@code x y z} for at most {@code ticks}
 * ticks and behaves as {@code mode} says (see TestEntityClient). Nobody else is ever told.
 */
public record TestEntityPayload(int mode, double x, double y, double z, int ticks) implements CustomPayload {
    public static final int GLIMPSE = 0;   // a split second, then gone
    public static final int STARE = 1;     // watches from afar until you look right at it
    public static final int BEHIND = 2;    // right behind you, gone the moment you turn
    public static final int JUMPSCARE = 3; // right behind you... and in your face when you turn
    public static final int APPROACH = 4;  // closer every time you look away

    public static final Id<TestEntityPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "test_entity"));
    public static final PacketCodec<RegistryByteBuf, TestEntityPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, TestEntityPayload::mode,
            PacketCodecs.DOUBLE, TestEntityPayload::x,
            PacketCodecs.DOUBLE, TestEntityPayload::y,
            PacketCodecs.DOUBLE, TestEntityPayload::z,
            PacketCodecs.VAR_INT, TestEntityPayload::ticks,
            TestEntityPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(ID, CODEC);
    }

    public static boolean canSend(ServerPlayerEntity player) {
        return ServerPlayNetworking.canSend(player, ID);
    }

    public static void send(ServerPlayerEntity player, int mode, Vec3d at, int ticks) {
        if (canSend(player)) ServerPlayNetworking.send(player, new TestEntityPayload(mode, at.x, at.y, at.z, ticks));
    }
}
