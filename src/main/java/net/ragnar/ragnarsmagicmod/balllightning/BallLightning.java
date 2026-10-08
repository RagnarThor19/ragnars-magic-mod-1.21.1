package net.ragnar.ragnarsmagicmod.balllightning;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
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
 * Tome of Ball Lightning: a crackling ball of storm lightning flies where you look, zapping everything it passes
 * near, tearing through anything it hits head on, and going off in a thunderclap when it reaches a block or the end
 * of its range. Drawn in the same yellow-white as the Tome of Zap. Everything for it lives in this package.
 * <p>
 * To remove it:
 * <ol>
 *   <li>delete this package ({@code balllightning} and {@code balllightning/client})</li>
 *   <li>delete {@code BallLightning.register()} in RagnarsMagicMod and {@code BallLightningClient.init()} in
 *       RagnarsMagicModClient</li>
 *   <li>delete {@code BALL_LIGHTNING} in SpellId</li>
 *   <li>delete {@code models/item/tome_of_ball_lightning.json} and the {@code ball_lightning} lines in en_us.json</li>
 * </ol>
 */
public final class BallLightning {
    private BallLightning() {}

    public static final EntityType<BallLightningEntity> BALL = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(RagnarsMagicMod.MOD_ID, "ball_lightning"),
            FabricEntityTypeBuilder.<BallLightningEntity>create(SpawnGroup.MISC, BallLightningEntity::new)
                    .dimensions(EntityDimensions.fixed(0.5f, 0.5f))
                    .trackRangeBlocks(128)
                    .trackedUpdateRate(1)
                    .disableSaving()
                    .build()
    );

    public static final TomeItem TOME_OF_BALL_LIGHTNING = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_ball_lightning"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC), // Master
                    TomeTier.MASTER, SpellId.BALL_LIGHTNING, 20
            ).setCooldown(20 * 18) // 18 seconds
    );

    /** How far everyone can see the arcs and the boom from. */
    private static final double VIEW_RANGE = 96.0;

    /** Server -> players nearby: an arc of lightning jumped from {@code from} to {@code to}. Thicker when {@code heavy}. */
    public record ArcPayload(Vector3f from, Vector3f to, boolean heavy) implements CustomPayload {
        public static final Id<ArcPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "ball_lightning_arc"));
        public static final PacketCodec<RegistryByteBuf, ArcPayload> CODEC = PacketCodec.tuple(
                PacketCodecs.VECTOR3F, ArcPayload::from,
                PacketCodecs.VECTOR3F, ArcPayload::to,
                PacketCodecs.BOOL, ArcPayload::heavy,
                ArcPayload::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Server -> players nearby: a ball went off at {@code at}, its blast reaching {@code radius} blocks. */
    public record BoomPayload(Vector3f at, float radius) implements CustomPayload {
        public static final Id<BoomPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "ball_lightning_boom"));
        public static final PacketCodec<RegistryByteBuf, BoomPayload> CODEC = PacketCodec.tuple(
                PacketCodecs.VECTOR3F, BoomPayload::at,
                PacketCodecs.FLOAT, BoomPayload::radius,
                BoomPayload::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    static void arc(ServerWorld world, Vec3d from, Vec3d to, boolean heavy) {
        send(world, from, new ArcPayload(from.toVector3f(), to.toVector3f(), heavy));
    }

    static void boom(ServerWorld world, Vec3d at, float radius) {
        send(world, at, new BoomPayload(at.toVector3f(), radius));
    }

    private static void send(ServerWorld world, Vec3d near, CustomPayload payload) {
        for (ServerPlayerEntity p : world.getPlayers()) {
            if (p.getPos().squaredDistanceTo(near) < VIEW_RANGE * VIEW_RANGE && ServerPlayNetworking.canSend(p, payload.getId())) {
                ServerPlayNetworking.send(p, payload);
            }
        }
    }

    public static void register() {
        // Lets staffs hand the tome back and puts it in the master tome loot pool
        ModItems.TOMES.computeIfAbsent(SpellId.BALL_LIGHTNING, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.MASTER, TOME_OF_BALL_LIGHTNING);
        Spells.register(SpellId.BALL_LIGHTNING, new BallLightningSpell());
        PayloadTypeRegistry.playS2C().register(ArcPayload.ID, ArcPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(BoomPayload.ID, BoomPayload.CODEC);
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.addAfter(ModItems.TOME_METEOR, TOME_OF_BALL_LIGHTNING));
    }
}
