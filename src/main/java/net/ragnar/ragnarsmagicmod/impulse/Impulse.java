package net.ragnar.ragnarsmagicmod.impulse;

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
 * Tome of Impulse: lobs a little dark blue magic cube - something like an ender pearl, but cubic - that sticks to
 * whatever it lands on, mob or block, hums for a moment and then goes off in a burst of force that flings everything
 * nearby away from it, hard. Nothing is hurt by the blast itself, but it throws the caster too, so it doubles as a
 * launch pad: mind the fall. Everything for it lives in this package.
 * <p>
 * To remove it:
 * <ol>
 *   <li>delete this package ({@code impulse} and {@code impulse/client})</li>
 *   <li>delete {@code Impulse.register()} in RagnarsMagicMod and {@code ImpulseClient.init()} in
 *       RagnarsMagicModClient</li>
 *   <li>delete {@code IMPULSE} in SpellId</li>
 *   <li>delete {@code models/item/tome_of_impulse.json}, {@code textures/entity/impulse_cube.png} and the
 *       {@code impulse} lines in en_us.json</li>
 * </ol>
 */
public final class Impulse {
    private Impulse() {}

    public static final EntityType<ImpulseCubeEntity> CUBE = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(RagnarsMagicMod.MOD_ID, "impulse_cube"),
            FabricEntityTypeBuilder.<ImpulseCubeEntity>create(SpawnGroup.MISC, ImpulseCubeEntity::new)
                    .dimensions(EntityDimensions.fixed(0.25f, 0.25f))
                    .trackRangeBlocks(96)
                    .trackedUpdateRate(1)
                    .disableSaving()
                    .build()
    );

    public static final TomeItem TOME_OF_IMPULSE = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_impulse"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.UNCOMMON), // Beginner
                    TomeTier.BEGINNER, SpellId.IMPULSE, 7
            ).setCooldown(20 * 10) // 10 seconds
    );

    /** How far everyone can see the shockwave from. */
    private static final double VIEW_RANGE = 96.0;

    /**
     * Server -> players nearby: a cube went off at {@code at}, stuck to a surface facing {@code normal} (zero if it
     * went off in the air or on a mob), its push reaching {@code radius} blocks.
     */
    public record BlastPayload(Vector3f at, Vector3f normal, float radius) implements CustomPayload {
        public static final Id<BlastPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "impulse_blast"));
        public static final PacketCodec<RegistryByteBuf, BlastPayload> CODEC = PacketCodec.tuple(
                PacketCodecs.VECTOR3F, BlastPayload::at,
                PacketCodecs.VECTOR3F, BlastPayload::normal,
                PacketCodecs.FLOAT, BlastPayload::radius,
                BlastPayload::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    static void blast(ServerWorld world, Vec3d at, Vec3d normal, float radius) {
        BlastPayload payload = new BlastPayload(at.toVector3f(), normal.toVector3f(), radius);
        for (ServerPlayerEntity p : world.getPlayers()) {
            if (p.getPos().squaredDistanceTo(at) < VIEW_RANGE * VIEW_RANGE && ServerPlayNetworking.canSend(p, BlastPayload.ID)) {
                ServerPlayNetworking.send(p, payload);
            }
        }
    }

    public static void register() {
        // Lets staffs hand the tome back and puts it in the beginner tome loot pool
        ModItems.TOMES.computeIfAbsent(SpellId.IMPULSE, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.BEGINNER, TOME_OF_IMPULSE);
        Spells.register(SpellId.IMPULSE, new ImpulseSpell());
        PayloadTypeRegistry.playS2C().register(BlastPayload.ID, BlastPayload.CODEC);
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.addAfter(ModItems.TOME_OF_ASCEND, TOME_OF_IMPULSE));
    }
}
