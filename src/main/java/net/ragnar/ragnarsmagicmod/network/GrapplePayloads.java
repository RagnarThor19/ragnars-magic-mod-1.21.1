package net.ragnar.ragnarsmagicmod.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.spell.GrapplingSpell;

import java.util.HashSet;
import java.util.Set;

/** Packets for the Tome of Grappling. */
public final class GrapplePayloads {
    private GrapplePayloads() {}

    /**
     * Server -> everyone who can see the caster: player {@code playerId} fired their hook at {@code x y z} (or at entity
     * {@code entityId}, -1 for none). It takes {@code travel} ticks to get there. {@code pullSelf}: it reels the caster
     * in; otherwise it drags the hooked entity back to them.
     */
    public record Attach(int playerId, double x, double y, double z, int entityId, boolean pullSelf, int travel) implements CustomPayload {
        public static final Id<Attach> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "grapple_attach"));
        public static final PacketCodec<RegistryByteBuf, Attach> CODEC = CustomPayload.codecOf((a, buf) -> {
            buf.writeVarInt(a.playerId);
            buf.writeDouble(a.x);
            buf.writeDouble(a.y);
            buf.writeDouble(a.z);
            buf.writeVarInt(a.entityId);
            buf.writeBoolean(a.pullSelf);
            buf.writeVarInt(a.travel);
        }, buf -> new Attach(buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readVarInt(), buf.readBoolean(), buf.readVarInt()));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Server -> everyone who can see the caster: player {@code playerId}'s hook lets go and winds back in. */
    public record End(int playerId) implements CustomPayload {
        public static final Id<End> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "grapple_end"));
        public static final PacketCodec<RegistryByteBuf, End> CODEC = PacketCodec.tuple(PacketCodecs.VAR_INT, End::playerId, End::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Client -> server: I let go of my hook ({@code jumped}: by jumping off it). */
    public record Release(boolean jumped) implements CustomPayload {
        public static final Id<Release> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "grapple_release"));
        public static final PacketCodec<RegistryByteBuf, Release> CODEC = PacketCodec.tuple(PacketCodecs.BOOL, Release::jumped, Release::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(Attach.ID, Attach.CODEC);
        PayloadTypeRegistry.playS2C().register(End.ID, End.CODEC);
        PayloadTypeRegistry.playC2S().register(Release.ID, Release.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Release.ID, (payload, context) ->
                GrapplingSpell.onRelease(context.player(), payload.jumped()));
    }

    /** Sends to the caster and everyone tracking them. */
    public static void broadcast(ServerPlayerEntity caster, CustomPayload payload) {
        Set<ServerPlayerEntity> to = new HashSet<>(PlayerLookup.tracking(caster));
        to.add(caster);
        for (ServerPlayerEntity p : to) {
            if (ServerPlayNetworking.canSend(p, payload.getId())) ServerPlayNetworking.send(p, payload);
        }
    }
}
