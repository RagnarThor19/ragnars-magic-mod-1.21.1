package net.ragnar.ragnarsmagicmod.mightypush;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
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
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import org.joml.Vector3f;

import java.util.EnumMap;

/**
 * Tome of Mighty Pushing: the caster rises (if they're in the air, about {@link MightyPushCast#RISE} blocks up, and
 * hangs there) or plants their feet, spreads their arms into a cross and gathers power for a few seconds, then cries
 * "Mighty Push!" and a gravity barrier swells slowly out of them to {@link MightyPushCast#MAX_RADIUS} blocks,
 * carrying everything before it - and crushing anything pinned against a wall (see MightyPushCast).
 * <p>
 * The world is never changed: the heaving ground and flung chunks of earth are drawn on each client
 * (MightyPushClient), so it costs the server nothing and can't break anything. Everything for it lives in this
 * package, plus the pose in {@code mixin/client/MightyPushPoseMixin}.
 * <p>
 * To remove it:
 * <ol>
 *   <li>delete this package ({@code mightypush} and {@code mightypush/client}) and MightyPushPoseMixin (and its line
 *       in ragnarsmagicmod.mixins.json)</li>
 *   <li>delete {@code MightyPush.register()} in RagnarsMagicMod, {@code MightyPushClient.init()} in
 *       RagnarsMagicModClient and the {@code MightyPushClient} line in KeyboardInputMixin</li>
 *   <li>delete {@code MIGHTY_PUSH} in SpellId</li>
 *   <li>delete {@code models/item/tome_of_mighty_pushing.json} and the {@code mighty_pushing} line in en_us.json</li>
 * </ol>
 */
public final class MightyPush {
    private MightyPush() {}

    public static final TomeItem TOME_OF_MIGHTY_PUSHING = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_mighty_pushing"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC), // Master
                    TomeTier.MASTER, SpellId.MIGHTY_PUSH, 50
            ).setCooldown(20 * 100) // 100 seconds
    );

    /** How far away people still see and hear it all. */
    static final double VIEW_RANGE = 96;

    // ---------------------------------------------------------------------
    // Network
    // ---------------------------------------------------------------------

    /** Server -> players nearby: {@code casterId} has begun to charge (rising into the air first if {@code airborne}). */
    public record ChargePayload(int casterId, boolean airborne) implements CustomPayload {
        public static final Id<ChargePayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "mighty_push_charge"));
        public static final PacketCodec<RegistryByteBuf, ChargePayload> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, ChargePayload::casterId,
                PacketCodecs.BOOL, ChargePayload::airborne,
                ChargePayload::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Server -> players nearby: {@code casterId} has let the push go from {@code center}. */
    public record ReleasePayload(int casterId, Vector3f center) implements CustomPayload {
        public static final Id<ReleasePayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "mighty_push_release"));
        public static final PacketCodec<RegistryByteBuf, ReleasePayload> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, ReleasePayload::casterId,
                PacketCodecs.VECTOR3F, ReleasePayload::center,
                ReleasePayload::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Server -> players nearby: {@code casterId} is done (their pose, and their own camera, go back to normal). */
    public record EndPayload(int casterId) implements CustomPayload {
        public static final Id<EndPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "mighty_push_end"));
        public static final PacketCodec<RegistryByteBuf, EndPayload> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, EndPayload::casterId,
                EndPayload::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    static void sendNear(ServerWorld world, Vec3d at, CustomPayload payload) {
        for (ServerPlayerEntity p : world.getPlayers()) {
            if (p.getPos().squaredDistanceTo(at) < VIEW_RANGE * VIEW_RANGE && ServerPlayNetworking.canSend(p, payload.getId())) {
                ServerPlayNetworking.send(p, payload);
            }
        }
    }

    public static void register() {
        // Lets staffs hand the tome back and puts it in the master tome loot pool
        ModItems.TOMES.computeIfAbsent(SpellId.MIGHTY_PUSH, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.MASTER, TOME_OF_MIGHTY_PUSHING);
        Spells.register(SpellId.MIGHTY_PUSH, new MightyPushSpell());
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.addAfter(ModItems.TOME_SUN, TOME_OF_MIGHTY_PUSHING));

        PayloadTypeRegistry.playS2C().register(ChargePayload.ID, ChargePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ReleasePayload.ID, ReleasePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(EndPayload.ID, EndPayload.CODEC);

        ServerTickEvents.END_WORLD_TICK.register(MightyPushCast::tickAll);
        // Never leave anyone floating
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> MightyPushCast.abandon(handler.player));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> MightyPushCast.abandonAll(server));
    }
}
