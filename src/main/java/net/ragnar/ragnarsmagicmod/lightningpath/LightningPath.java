package net.ragnar.ragnarsmagicmod.lightningpath;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.tag.DamageTypeTags;
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

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * Tome of the Lightning Path: paint a path with your crosshair, then run it at the speed of lightning.
 * <ol>
 *   <li>Casting tips your view down at the ground in front of you, and for {@link LightningPathRun#PAINT_TICKS} you
 *       paint: wherever your crosshair goes over the ground, a crackling line follows, up to
 *       {@link PathBuilder#MAX_LENGTH} blocks. Zigzag and the path zigzags. Right-click again to go early.</li>
 *   <li>Then you run it at {@link #SPEED} blocks a tick - hugging the ground up and down hills, untouchable while you
 *       go - tearing through everything in the way for {@link LightningPathRun#HIT_DAMAGE}, and arrive in a crack of
 *       thunder with a bolt striking where you stop.</li>
 * </ol>
 * The path is worked out on the server from where the caster looks; the run itself is moved by the caster's own
 * client (so it's smooth) while the server checks it, does the damage, and keeps the runner from being treated as
 * having "moved wrongly" (see PlayerEntityMixin). Everything else for it lives in this package.
 * <p>
 * To remove it:
 * <ol>
 *   <li>delete this package ({@code lightningpath} and {@code lightningpath/client})</li>
 *   <li>delete {@code LightningPath.register()} in RagnarsMagicMod and {@code LightningPathClient.init()} in
 *       RagnarsMagicModClient, and the {@code LightningPath} lines in PlayerEntityMixin, PlayerTravelMixin,
 *       KeyboardInputMixin, GameRendererMixin, MinecraftClientMixin and MightyPushPoseMixin</li>
 *   <li>delete {@code LIGHTNING_PATH} in SpellId</li>
 *   <li>delete {@code models/item/tome_of_the_lightning_path.json} and the {@code lightning_path} line in en_us.json</li>
 * </ol>
 */
public final class LightningPath {
    private LightningPath() {}

    /** Blocks a tick: a full 150 block path takes about a second and a quarter. */
    public static final double SPEED = 6.0;

    public static final TomeItem TOME_OF_THE_LIGHTNING_PATH = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_the_lightning_path"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC), // Master
                    TomeTier.MASTER, SpellId.LIGHTNING_PATH, 25
            ).setCooldown(20 * 32) // 32 seconds
    );

    static final double VIEW_RANGE = 160;

    // ---------------------------------------------------------------------
    // Network
    // ---------------------------------------------------------------------

    private static final PacketCodec<io.netty.buffer.ByteBuf, List<Vector3f>> POINTS = PacketCodecs.VECTOR3F.collect(PacketCodecs.toList(1024));

    /** Server -> players nearby: {@code casterId} is painting, and this is the path so far. */
    public record PaintPayload(int casterId, int ticksLeft, List<Vector3f> points) implements CustomPayload {
        public static final Id<PaintPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "lightning_path_paint"));
        public static final PacketCodec<RegistryByteBuf, PaintPayload> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, PaintPayload::casterId,
                PacketCodecs.VAR_INT, PaintPayload::ticksLeft,
                POINTS, PaintPayload::points,
                PaintPayload::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Server -> players nearby: {@code casterId} is off, along this painted path (empty: it fizzled). */
    public record RunPayload(int casterId, List<Vector3f> points) implements CustomPayload {
        public static final Id<RunPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "lightning_path_run"));
        public static final PacketCodec<RegistryByteBuf, RunPayload> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, RunPayload::casterId,
                POINTS, RunPayload::points,
                RunPayload::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Server -> players nearby: {@code casterId} has arrived at {@code at}. */
    public record ArrivePayload(int casterId, Vector3f at) implements CustomPayload {
        public static final Id<ArrivePayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "lightning_path_arrive"));
        public static final PacketCodec<RegistryByteBuf, ArrivePayload> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, ArrivePayload::casterId,
                PacketCodecs.VECTOR3F, ArrivePayload::at,
                ArrivePayload::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Runner -> server: I've reached the end (or something stopped me). */
    public record DonePayload() implements CustomPayload {
        public static final DonePayload INSTANCE = new DonePayload();
        public static final Id<DonePayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "lightning_path_done"));
        public static final PacketCodec<RegistryByteBuf, DonePayload> CODEC = PacketCodec.unit(INSTANCE);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    static List<Vector3f> pack(List<Vec3d> points) {
        List<Vector3f> out = new ArrayList<>(points.size());
        for (Vec3d p : points) out.add(p.toVector3f());
        return out;
    }

    public static List<Vec3d> unpack(List<Vector3f> points) {
        List<Vec3d> out = new ArrayList<>(points.size());
        for (Vector3f p : points) out.add(new Vec3d(p));
        return out;
    }

    static void sendNear(ServerWorld world, Vec3d at, ServerPlayerEntity caster, CustomPayload payload) {
        for (ServerPlayerEntity p : world.getPlayers()) {
            if ((p == caster || p.getPos().squaredDistanceTo(at) < VIEW_RANGE * VIEW_RANGE) && ServerPlayNetworking.canSend(p, payload.getId())) {
                ServerPlayNetworking.send(p, payload);
            }
        }
    }

    /** True while {@code player} is mid-run on the server (PlayerEntityMixin lets them pass the movement checks). */
    public static boolean isRunning(PlayerEntity player) {
        LightningPathRun run = LightningPathRun.of(player);
        return run != null && run.running();
    }

    public static void register() {
        // Lets staffs hand the tome back and puts it in the master tome loot pool
        ModItems.TOMES.computeIfAbsent(SpellId.LIGHTNING_PATH, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.MASTER, TOME_OF_THE_LIGHTNING_PATH);
        Spells.register(SpellId.LIGHTNING_PATH, new LightningPathSpell());
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.addAfter(ModItems.TOME_SUN, TOME_OF_THE_LIGHTNING_PATH));

        PayloadTypeRegistry.playS2C().register(PaintPayload.ID, PaintPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(RunPayload.ID, RunPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ArrivePayload.ID, ArrivePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(DonePayload.ID, DonePayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(DonePayload.ID, (payload, context) -> {
            LightningPathRun run = LightningPathRun.of(context.player());
            if (run != null) run.arrive(context.player());
        });

        // Untouchable while running; and no fall damage for a moment after
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayerEntity p) || source.isIn(DamageTypeTags.BYPASSES_INVULNERABILITY)) return true;
            LightningPathRun run = LightningPathRun.of(p);
            if (run == null) return true;
            return !run.running() && !source.isIn(DamageTypeTags.IS_FALL);
        });
        ServerTickEvents.END_WORLD_TICK.register(LightningPathRun::tickAll);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> LightningPathRun.forget(handler.player));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> LightningPathRun.forgetAll());
    }
}
