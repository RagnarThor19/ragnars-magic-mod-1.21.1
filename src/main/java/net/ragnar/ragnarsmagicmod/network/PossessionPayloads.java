package net.ragnar.ragnarsmagicmod.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.spell.ControllingSpell;

/** Packets for the Tome of Controlling: riding along inside a mob and steering it. */
public final class PossessionPayloads {
    private PossessionPayloads() {}

    /** Server -> possessor: look through this mob for {@code ticks} ticks. An entity id of -1 sends you home. */
    public record Possess(int entityId, int ticks) implements CustomPayload {
        public static final Id<Possess> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "possess"));
        public static final PacketCodec<RegistryByteBuf, Possess> CODEC =
                PacketCodec.tuple(PacketCodecs.VAR_INT, Possess::entityId, PacketCodecs.VAR_INT, Possess::ticks, Possess::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Server -> everyone: this player's body is (or no longer is) hidden while they possess something. */
    public record Hidden(int playerId, boolean hidden) implements CustomPayload {
        public static final Id<Hidden> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "possess_hidden"));
        public static final PacketCodec<RegistryByteBuf, Hidden> CODEC =
                PacketCodec.tuple(PacketCodecs.VAR_INT, Hidden::playerId, PacketCodecs.BOOL, Hidden::hidden, Hidden::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Client -> server, every tick while possessing: movement keys and where the player is looking. */
    public record Steer(float forward, float sideways, boolean jump, boolean sneak, boolean sprint, float yaw, float pitch)
            implements CustomPayload {
        public static final Id<Steer> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "possess_steer"));
        public static final PacketCodec<RegistryByteBuf, Steer> CODEC = PacketCodec.of(
                (s, buf) -> {
                    buf.writeFloat(s.forward);
                    buf.writeFloat(s.sideways);
                    buf.writeByte((s.jump ? 1 : 0) | (s.sneak ? 2 : 0) | (s.sprint ? 4 : 0));
                    buf.writeFloat(s.yaw);
                    buf.writeFloat(s.pitch);
                },
                buf -> {
                    float forward = buf.readFloat();
                    float sideways = buf.readFloat();
                    int flags = buf.readByte();
                    return new Steer(forward, sideways, (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0,
                            buf.readFloat(), buf.readFloat());
                });

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Client -> server: left click (use the mob's ability) or right click (leave the mob). */
    public record Act(boolean leave) implements CustomPayload {
        public static final Id<Act> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "possess_act"));
        public static final PacketCodec<RegistryByteBuf, Act> CODEC =
                PacketCodec.tuple(PacketCodecs.BOOL, Act::leave, Act::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(Possess.ID, Possess.CODEC);
        PayloadTypeRegistry.playS2C().register(Hidden.ID, Hidden.CODEC);
        PayloadTypeRegistry.playC2S().register(Steer.ID, Steer.CODEC);
        PayloadTypeRegistry.playC2S().register(Act.ID, Act.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Steer.ID, (payload, context) ->
                ControllingSpell.onSteer(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(Act.ID, (payload, context) ->
                ControllingSpell.onAct(context.player(), payload.leave()));
    }

    public static void sendPossess(ServerPlayerEntity player, int entityId, int ticks) {
        if (ServerPlayNetworking.canSend(player, Possess.ID)) {
            ServerPlayNetworking.send(player, new Possess(entityId, ticks));
        }
    }

    public static void sendHidden(ServerPlayerEntity to, int playerId, boolean hidden) {
        if (ServerPlayNetworking.canSend(to, Hidden.ID)) {
            ServerPlayNetworking.send(to, new Hidden(playerId, hidden));
        }
    }

    public static void broadcastHidden(MinecraftServer server, int playerId, boolean hidden) {
        for (ServerPlayerEntity p : PlayerLookup.all(server)) sendHidden(p, playerId, hidden);
    }
}
