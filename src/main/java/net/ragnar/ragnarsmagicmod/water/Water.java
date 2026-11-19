package net.ragnar.ragnarsmagicmod.water;

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
import net.ragnar.ragnarsmagicmod.geysir.Geysir;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import org.joml.Vector3f;

import java.util.EnumMap;

/**
 * Tome of Water: throws a blocky ball of water that drifts forward, swaying gently off its line (never more than a
 * block), and washes straight through every mob it meets. It pops in a splash when it hits a block or after ten
 * seconds of flight, soaking everything nearby. Everything for it lives in this package.
 * <p>
 * To remove it:
 * <ol>
 *   <li>delete this package ({@code water} and {@code water/client})</li>
 *   <li>delete {@code Water.register()} in RagnarsMagicMod and {@code WaterClient.init()} in RagnarsMagicModClient</li>
 *   <li>delete {@code WATER} in SpellId</li>
 *   <li>delete {@code models/item/tome_of_water.json} and the {@code water_orb} / {@code tome_of_water} lines in en_us.json</li>
 * </ol>
 */
public final class Water {
    private Water() {}

    public static final int XP_COST = 5;
    public static final int COOLDOWN = 20 * 2;

    public static final EntityType<WaterOrbEntity> ORB = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(RagnarsMagicMod.MOD_ID, "water_orb"),
            FabricEntityTypeBuilder.<WaterOrbEntity>create(SpawnGroup.MISC, WaterOrbEntity::new)
                    .dimensions(EntityDimensions.fixed(0.8f, 0.8f))
                    .trackRangeBlocks(96)
                    .trackedUpdateRate(1)
                    .disableSaving()
                    .build()
    );

    public static final TomeItem TOME_OF_WATER = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_water"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Advanced
                    TomeTier.ADVANCED, SpellId.WATER, XP_COST
            ).setCooldown(COOLDOWN)
    );

    private static final double VIEW_RANGE = 96.0;

    /** Server -> players nearby: a water ball popped at {@code at}. */
    public record PopPayload(Vector3f at) implements CustomPayload {
        public static final Id<PopPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "water_pop"));
        public static final PacketCodec<RegistryByteBuf, PopPayload> CODEC = PacketCodec.tuple(
                PacketCodecs.VECTOR3F, PopPayload::at,
                PopPayload::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    static void pop(ServerWorld world, Vec3d at) {
        PopPayload payload = new PopPayload(at.toVector3f());
        for (ServerPlayerEntity p : world.getPlayers()) {
            if (p.getPos().squaredDistanceTo(at) < VIEW_RANGE * VIEW_RANGE && ServerPlayNetworking.canSend(p, PopPayload.ID)) {
                ServerPlayNetworking.send(p, payload);
            }
        }
    }

    public static void register() {
        // Lets staffs hand the tome back and puts it in the advanced tome loot pool
        ModItems.TOMES.computeIfAbsent(SpellId.WATER, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.ADVANCED, TOME_OF_WATER);
        Spells.register(SpellId.WATER, new WaterSpell());
        PayloadTypeRegistry.playS2C().register(PopPayload.ID, PopPayload.CODEC);
        // Next to the Tome of Geysir (registered after Astral Projection, which also goes after Geysir)
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.addAfter(Geysir.TOME_OF_GEYSIR, TOME_OF_WATER));
    }
}
