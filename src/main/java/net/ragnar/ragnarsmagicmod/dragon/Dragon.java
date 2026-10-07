package net.ragnar.ragnarsmagicmod.dragon;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
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
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.logs.Logs;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tome of the Dragon: a guided missile you fly yourself. A little dragon leaps from the staff and your view goes with
 * it, watched from just behind like a Tome of the Fairy fairy. You steer it like the fairy, only it never stops flying forwards and carries far more momentum,
 * so it swings wide and drifts through turns - hard to fly well. It goes off the moment it touches anything, or after
 * {@link DragonMissileEntity#LIFETIME} ticks wherever it is: whatever it flew straight into takes
 * {@link DragonMissileEntity#DIRECT_DAMAGE}, and everything around the blast takes less. Then your view snaps back to
 * your own eyes. While you're flying it your body stands still and can't be hurt. Click to set it off early.
 * Everything for it lives in this package.
 * <p>
 * To remove it:
 * <ol>
 *   <li>delete this package ({@code dragon} and {@code dragon/client})</li>
 *   <li>delete {@code Dragon.register()} in RagnarsMagicMod and {@code DragonClient.init()} in
 *       RagnarsMagicModClient, and the {@code DragonClient} lines in CameraMixin, GameRendererMixin,
 *       KeyboardInputMixin and MinecraftClientMixin</li>
 *   <li>delete {@code DRAGON} in SpellId</li>
 *   <li>delete {@code models/item/tome_of_the_dragon.json} and the {@code dragon} lines in en_us.json</li>
 * </ol>
 */
public final class Dragon {
    private Dragon() {}

    public static final EntityType<DragonMissileEntity> MISSILE = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(RagnarsMagicMod.MOD_ID, "dragon_missile"),
            FabricEntityTypeBuilder.<DragonMissileEntity>create(SpawnGroup.MISC, DragonMissileEntity::new)
                    .dimensions(EntityDimensions.fixed(0.9f, 0.9f))
                    .trackRangeBlocks(160)
                    .trackedUpdateRate(1)
                    .disableSaving()
                    .build()
    );

    public static final TomeItem TOME_OF_THE_DRAGON = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_the_dragon"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Advanced
                    TomeTier.ADVANCED, SpellId.DRAGON, 16
            ).setCooldown(20 * 20) // 20 seconds
    );

    // ---------------------------------------------------------------------
    // Network
    // ---------------------------------------------------------------------

    /** Server -> pilot: look through dragon {@code entityId} for up to {@code ticks} ticks. -1 sends you home. */
    public record PilotPayload(int entityId, int ticks) implements CustomPayload {
        public static final Id<PilotPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "dragon_pilot"));
        public static final PacketCodec<RegistryByteBuf, PilotPayload> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, PilotPayload::entityId,
                PacketCodecs.VAR_INT, PilotPayload::ticks,
                PilotPayload::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Pilot -> server, every tick while flying: the movement keys and where they're looking. */
    public record SteerPayload(float forward, float sideways, boolean up, boolean down, float yaw, float pitch) implements CustomPayload {
        public static final Id<SteerPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "dragon_steer"));
        public static final PacketCodec<RegistryByteBuf, SteerPayload> CODEC = PacketCodec.tuple(
                PacketCodecs.FLOAT, SteerPayload::forward,
                PacketCodecs.FLOAT, SteerPayload::sideways,
                PacketCodecs.BOOL, SteerPayload::up,
                PacketCodecs.BOOL, SteerPayload::down,
                PacketCodecs.FLOAT, SteerPayload::yaw,
                PacketCodecs.FLOAT, SteerPayload::pitch,
                SteerPayload::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Server -> players nearby: a dragon went off at {@code at}, its blast reaching {@code radius} blocks. */
    public record BlastPayload(Vector3f at, float radius) implements CustomPayload {
        public static final Id<BlastPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "dragon_blast"));
        public static final PacketCodec<RegistryByteBuf, BlastPayload> CODEC = PacketCodec.tuple(
                PacketCodecs.VECTOR3F, BlastPayload::at,
                PacketCodecs.FLOAT, BlastPayload::radius,
                BlastPayload::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Shows the blast at {@code at} to everyone who can see it. */
    static void blast(net.minecraft.server.world.ServerWorld world, net.minecraft.util.math.Vec3d at, float radius) {
        BlastPayload payload = new BlastPayload(at.toVector3f(), radius);
        for (ServerPlayerEntity p : world.getPlayers()) {
            if (p.getPos().squaredDistanceTo(at) < 160 * 160) send(p, payload);
        }
    }

    /** Pilot -> server: set it off now. */
    public record DetonatePayload() implements CustomPayload {
        public static final DetonatePayload INSTANCE = new DetonatePayload();
        public static final Id<DetonatePayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "dragon_detonate"));
        public static final PacketCodec<RegistryByteBuf, DetonatePayload> CODEC = PacketCodec.unit(INSTANCE);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    // ---------------------------------------------------------------------
    // Who's flying what (server)
    // ---------------------------------------------------------------------

    /** Pilot UUID -> the dragon they're flying. */
    private static final Map<UUID, DragonMissileEntity> FLIGHTS = new HashMap<>();

    public static boolean isPiloting(ServerPlayerEntity player) {
        return FLIGHTS.containsKey(player.getUuid());
    }

    @Nullable
    static DragonMissileEntity flying(ServerPlayerEntity player) {
        return FLIGHTS.get(player.getUuid());
    }

    static void start(ServerPlayerEntity pilot, DragonMissileEntity dragon) {
        FLIGHTS.put(pilot.getUuid(), dragon);
        send(pilot, new PilotPayload(dragon.getId(), DragonMissileEntity.LIFETIME));
    }

    /** The flight's over (the dragon went off or vanished): back to your own eyes. */
    static void end(@Nullable ServerPlayerEntity pilot, UUID pilotId, DragonMissileEntity dragon) {
        if (FLIGHTS.get(pilotId) != dragon) return;
        FLIGHTS.remove(pilotId);
        if (pilot != null) {
            send(pilot, new PilotPayload(-1, 0));
            pilot.fallDistance = 0;
        }
    }

    private static void send(ServerPlayerEntity player, CustomPayload payload) {
        if (ServerPlayNetworking.canSend(player, payload.getId())) ServerPlayNetworking.send(player, payload);
    }

    /** Keeps flights honest: a dragon that's gone, or a pilot who's gone, ends it. */
    private static void tick(MinecraftServer server) {
        if (FLIGHTS.isEmpty()) return;
        FLIGHTS.entrySet().removeIf(e -> {
            DragonMissileEntity dragon = e.getValue();
            ServerPlayerEntity pilot = server.getPlayerManager().getPlayer(e.getKey());
            if (pilot == null || !pilot.isAlive()) {
                if (!dragon.isRemoved()) dragon.discard();
                return true;
            }
            // Gone without going off (unloaded, killed by a command), or stuck somewhere it can't tick
            if (dragon.isRemoved() || dragon.lifeTicks() > DragonMissileEntity.LIFETIME + 20) {
                if (!dragon.isRemoved()) dragon.discard();
                send(pilot, new PilotPayload(-1, 0));
                return true;
            }
            // Your body, left standing: a shimmer of magic shows everyone else it's warded (not you: it'd be in your
            // face when you come back)
            if (pilot.age % 3 == 0) {
                for (ServerPlayerEntity viewer : pilot.getServerWorld().getPlayers()) {
                    if (viewer == pilot || viewer.squaredDistanceTo(pilot) > 48 * 48) continue;
                    pilot.getServerWorld().spawnParticles(viewer, ParticleTypes.ENCHANT, false, pilot.getX(), pilot.getY() + 1.0, pilot.getZ(),
                            6, 0.4, 0.7, 0.4, 0.4);
                }
            }
            return false;
        });
    }

    public static void register() {
        // Lets staffs hand the tome back and puts it in the advanced tome loot pool
        ModItems.TOMES.computeIfAbsent(SpellId.DRAGON, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.ADVANCED, TOME_OF_THE_DRAGON);
        Spells.register(SpellId.DRAGON, new DragonSpell());
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.addAfter(Logs.TOME_OF_LOGS, TOME_OF_THE_DRAGON));

        PayloadTypeRegistry.playS2C().register(PilotPayload.ID, PilotPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(BlastPayload.ID, BlastPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SteerPayload.ID, SteerPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(DetonatePayload.ID, DetonatePayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(SteerPayload.ID, (payload, context) -> {
            DragonMissileEntity dragon = flying(context.player());
            if (dragon != null) dragon.steer(payload);
        });
        ServerPlayNetworking.registerGlobalReceiver(DetonatePayload.ID, (payload, context) -> {
            DragonMissileEntity dragon = flying(context.player());
            if (dragon != null) dragon.detonate();
        });

        // The pilot's body can't be hurt while they're away (but /kill and the void still work)
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !(entity instanceof ServerPlayerEntity p && isPiloting(p)) || source.isIn(DamageTypeTags.BYPASSES_INVULNERABILITY));
        ServerTickEvents.END_SERVER_TICK.register(Dragon::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            DragonMissileEntity dragon = FLIGHTS.remove(handler.player.getUuid());
            if (dragon != null && !dragon.isRemoved()) dragon.discard();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> FLIGHTS.clear());
    }
}
