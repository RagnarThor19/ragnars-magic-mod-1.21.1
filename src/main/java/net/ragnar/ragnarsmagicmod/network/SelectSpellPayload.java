package net.ragnar.ragnarsmagicmod.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.ItemStack;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;

/** Client -> server: select tome slot {@code index} on the staff held in {@code offHand ? off : main} hand. */
public record SelectSpellPayload(boolean offHand, int index) implements CustomPayload {
    public static final Id<SelectSpellPayload> ID =
            new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "select_spell"));

    public static final PacketCodec<RegistryByteBuf, SelectSpellPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.BOOL, SelectSpellPayload::offHand,
            PacketCodecs.VAR_INT, SelectSpellPayload::index,
            SelectSpellPayload::new
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(ID, CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ID, (payload, context) -> {
            ItemStack staff = context.player().getStackInHand(payload.offHand() ? Hand.OFF_HAND : Hand.MAIN_HAND);
            if (staff.getItem() instanceof StaffItem) {
                StaffItem.setSelectedIndex(staff, payload.index());
            }
        });
    }
}
