package net.ragnar.ragnarsmagicmod.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.util.PortalNetwork;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Packets for the Tome of Portals. */
public final class PortalPayloads {
    private PortalPayloads() {}

    /** One open portal, as the clients draw it. {@code slot} is 0 for the magenta portal, 1 for the dark purple one. */
    public record View(UUID owner, int slot, Identifier world, Vec3d center, Direction normal, Direction up, boolean linked) {
        public static View of(UUID owner, int slot, PortalNetwork.Portal p, boolean linked) {
            return new View(owner, slot, p.world().getValue(), p.center(), p.normal(), p.up(), linked);
        }

        void write(RegistryByteBuf buf) {
            buf.writeUuid(owner);
            buf.writeByte(slot);
            buf.writeIdentifier(world);
            buf.writeDouble(center.x);
            buf.writeDouble(center.y);
            buf.writeDouble(center.z);
            buf.writeByte(normal.getId());
            buf.writeByte(up.getId());
            buf.writeBoolean(linked);
        }

        static View read(RegistryByteBuf buf) {
            return new View(buf.readUuid(), buf.readByte(), buf.readIdentifier(),
                    new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble()),
                    Direction.byId(buf.readByte()), Direction.byId(buf.readByte()), buf.readBoolean());
        }
    }

    /**
     * Server -> everyone: every open portal. {@code snapshot} is set when joining, so portals that were already
     * open don't play their opening.
     */
    public record Sync(boolean snapshot, List<View> portals) implements CustomPayload {
        public static final Id<Sync> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "portal_sync"));
        public static final PacketCodec<RegistryByteBuf, Sync> CODEC = PacketCodec.of((value, buf) -> {
            buf.writeBoolean(value.snapshot());
            buf.writeVarInt(value.portals().size());
            for (View v : value.portals()) v.write(buf);
        }, buf -> {
            boolean snapshot = buf.readBoolean();
            int count = buf.readVarInt();
            List<View> portals = new ArrayList<>(count);
            for (int i = 0; i < count; i++) portals.add(View.read(buf));
            return new Sync(snapshot, portals);
        });

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Server -> traveller: you just came out of a portal of this colour. */
    public record Traveled(int color) implements CustomPayload {
        public static final Id<Traveled> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "portal_traveled"));
        public static final PacketCodec<RegistryByteBuf, Traveled> CODEC =
                PacketCodec.tuple(PacketCodecs.INTEGER, Traveled::color, Traveled::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(Sync.ID, Sync.CODEC);
        PayloadTypeRegistry.playS2C().register(Traveled.ID, Traveled.CODEC);
    }

    public static void sendSync(ServerPlayerEntity player, boolean snapshot, List<View> portals) {
        if (ServerPlayNetworking.canSend(player, Sync.ID)) ServerPlayNetworking.send(player, new Sync(snapshot, portals));
    }

    public static void sendTraveled(ServerPlayerEntity player, int color) {
        if (ServerPlayNetworking.canSend(player, Traveled.ID)) ServerPlayNetworking.send(player, new Traveled(color));
    }
}
