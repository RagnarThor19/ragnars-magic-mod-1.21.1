package net.ragnar.ragnarsmagicmod.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.spell.TntSpell;

/** Packets for aiming the Tome of TNT. */
public final class TntPayloads {
    private TntPayloads() {}

    /** Server -> caster: start or stop showing the throw arc, at this throw strength. */
    public record Aiming(boolean active, float power) implements CustomPayload {
        public static final Id<Aiming> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "tnt_aiming"));
        public static final PacketCodec<RegistryByteBuf, Aiming> CODEC =
                PacketCodec.tuple(PacketCodecs.BOOL, Aiming::active, PacketCodecs.FLOAT, Aiming::power, Aiming::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Client -> server: the throw strength after scrolling. */
    public record Power(float power) implements CustomPayload {
        public static final Id<Power> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "tnt_power"));
        public static final PacketCodec<RegistryByteBuf, Power> CODEC =
                PacketCodec.tuple(PacketCodecs.FLOAT, Power::power, Power::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(Aiming.ID, Aiming.CODEC);
        PayloadTypeRegistry.playC2S().register(Power.ID, Power.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Power.ID, (payload, context) ->
                TntSpell.onPower(context.player(), payload.power()));
    }

    public static void sendAiming(ServerPlayerEntity player, boolean active, float power) {
        if (ServerPlayNetworking.canSend(player, Aiming.ID)) ServerPlayNetworking.send(player, new Aiming(active, power));
    }
}
