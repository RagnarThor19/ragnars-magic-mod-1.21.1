package net.ragnar.ragnarsmagicmod.bubbles;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.damage.DamageTypes;
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
import net.ragnar.ragnarsmagicmod.upsidedown.UpsideDown;
import org.joml.Vector3f;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tome of Bubbles: blows one big, slow, wobbly soap bubble. It drifts roughly where you aimed, never quite on course,
 * for up to {@link BubbleEntity#LIFE} ticks, and the first thing it touches gets sealed inside and carried off -
 * big enough for an enderman, an iron golem, a ravager or even a warden. Anything sharp pops it: arrows and every
 * other projectile (including one shot from inside), a warden's sonic boom, a sword, cactus, fire. Whatever was
 * inside drops out wherever it happens to be. Everything for it lives in this package.
 * <p>
 * To remove it:
 * <ol>
 *   <li>delete this package ({@code bubbles} and {@code bubbles/client})</li>
 *   <li>delete {@code Bubbles.register()} in RagnarsMagicMod and {@code BubblesClient.init()} in
 *       RagnarsMagicModClient</li>
 *   <li>delete {@code BUBBLES} in SpellId</li>
 *   <li>delete {@code models/item/tome_of_bubbles.json} and the {@code bubble} lines in en_us.json</li>
 * </ol>
 */
public final class Bubbles {
    private Bubbles() {}

    public static final EntityType<BubbleEntity> BUBBLE = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(RagnarsMagicMod.MOD_ID, "bubble"),
            FabricEntityTypeBuilder.<BubbleEntity>create(SpawnGroup.MISC, BubbleEntity::new)
                    .dimensions(EntityDimensions.fixed(BubbleEntity.RADIUS * 2, BubbleEntity.RADIUS * 2))
                    .trackRangeBlocks(96)
                    .trackedUpdateRate(1)
                    .disableSaving()
                    .build()
    );

    public static final TomeItem TOME_OF_BUBBLES = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_bubbles"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.UNCOMMON), // Beginner
                    TomeTier.BEGINNER, SpellId.BUBBLES, 10
            ).setCooldown(20 * 18) // 18 seconds
    );

    private static final double VIEW_RANGE = 96.0;

    /** Who is sealed in which bubble right now. Server only. */
    static final Map<UUID, BubbleEntity> HELD = new HashMap<>();

    /** Server -> players nearby: a bubble of {@code radius} popped at {@code at} (its middle). */
    public record PopPayload(Vector3f at, float radius) implements CustomPayload {
        public static final Id<PopPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "bubble_pop"));
        public static final PacketCodec<RegistryByteBuf, PopPayload> CODEC = PacketCodec.tuple(
                PacketCodecs.VECTOR3F, PopPayload::at,
                PacketCodecs.FLOAT, PopPayload::radius,
                PopPayload::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    static void pop(ServerWorld world, Vec3d at, float radius) {
        PopPayload payload = new PopPayload(at.toVector3f(), radius);
        for (ServerPlayerEntity p : world.getPlayers()) {
            if (p.getPos().squaredDistanceTo(at) < VIEW_RANGE * VIEW_RANGE && ServerPlayNetworking.canSend(p, PopPayload.ID)) {
                ServerPlayNetworking.send(p, payload);
            }
        }
    }

    /** The bubble {@code entity} is sealed in, if any. */
    public static BubbleEntity bubbleOf(Entity entity) {
        BubbleEntity b = HELD.get(entity.getUuid());
        return b != null && !b.isRemoved() ? b : null;
    }

    public static void register() {
        // Lets staffs hand the tome back and puts it in the beginner tome loot pool
        ModItems.TOMES.computeIfAbsent(SpellId.BUBBLES, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.BEGINNER, TOME_OF_BUBBLES);
        Spells.register(SpellId.BUBBLES, new BubbleSpell());
        PayloadTypeRegistry.playS2C().register(PopPayload.ID, PopPayload.CODEC);
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.addAfter(UpsideDown.TOME_OF_UPSIDE_DOWN, TOME_OF_BUBBLES));

        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            // Whatever's inside can't reach out and hit anything: the film soaks up the blow. Shots it fires still
            // fly (and pop the bubble on their way out).
            Entity attacker = source.getAttacker();
            // A sonic boom from inside blasts straight through the film
            if (source.isOf(DamageTypes.SONIC_BOOM) && attacker != null && bubbleOf(attacker) != null) {
                bubbleOf(attacker).burst();
                return true;
            }
            if (attacker != null && source.getSource() == attacker && bubbleOf(attacker) != null && bubbleOf(entity) == null) {
                return false;
            }
            // Hitting what's inside means going through the film: it pops, and the blow still lands
            BubbleEntity holding = bubbleOf(entity);
            if (holding != null && attacker != null && attacker != entity) holding.burst();
            return true;
        });
    }
}
