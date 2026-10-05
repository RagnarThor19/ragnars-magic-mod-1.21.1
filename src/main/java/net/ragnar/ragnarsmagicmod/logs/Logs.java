package net.ragnar.ragnarsmagicmod.logs;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.jumping.Jumping;

import java.util.EnumMap;

/**
 * Tome of Logs: the substitution jutsu. Cast it and for {@link LogsSpell#WINDOW} ticks the next hit an entity lands
 * on you never happens: you vanish in a puff of white smoke, a log drops where you stood and takes the blow, and you
 * reappear behind whoever hit you. Casting shows nothing to anyone else. The cooldown starts when the window ends,
 * or as soon as the substitution goes off. Everything for it lives in this package.
 * <p>
 * To remove it:
 * <ol>
 *   <li>delete this package ({@code logs} and {@code logs/client})</li>
 *   <li>delete {@code Logs.register()} in RagnarsMagicMod and {@code LogsClient.init()} in RagnarsMagicModClient</li>
 *   <li>delete {@code LOGS} in SpellId</li>
 *   <li>delete {@code models/item/tome_of_logs.json} and the {@code logs} line in en_us.json</li>
 * </ol>
 */
public final class Logs {
    private Logs() {}

    public static final TomeItem TOME_OF_LOGS = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_logs"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Advanced
                    TomeTier.ADVANCED, SpellId.LOGS, 5
            ).setCooldown(20 * 13) // 13 seconds, from the end of the window
    );

    /** Server -> the substituted player only: glide the camera to the new spot and flash the screen white. */
    public record SwapPayload() implements CustomPayload {
        public static final Id<SwapPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "log_swap"));
        public static final PacketCodec<RegistryByteBuf, SwapPayload> CODEC = PacketCodec.unit(new SwapPayload());

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }

        static void send(ServerPlayerEntity player) {
            if (ServerPlayNetworking.canSend(player, ID)) ServerPlayNetworking.send(player, new SwapPayload());
        }
    }

    public static void register() {
        // Lets staffs hand the tome back and puts it in the advanced tome loot pool
        ModItems.TOMES.computeIfAbsent(SpellId.LOGS, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.ADVANCED, TOME_OF_LOGS);
        Spells.register(SpellId.LOGS, new LogsSpell());
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.addAfter(Jumping.TOME_OF_JUMPING, TOME_OF_LOGS));
        PayloadTypeRegistry.playS2C().register(SwapPayload.ID, SwapPayload.CODEC);

        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !(entity instanceof ServerPlayerEntity player) || !LogsSpell.trySubstitute(player, source));
        ServerTickEvents.END_SERVER_TICK.register(LogsSpell::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> LogsSpell.clear());
    }
}
