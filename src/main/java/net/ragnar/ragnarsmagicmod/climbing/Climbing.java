package net.ragnar.ragnarsmagicmod.climbing;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.PassiveTomes;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.PassiveSpell;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.util.FallGuard;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tome of Climbing: a passive tome. While it's in your inventory (or on a staff you carry), walk into a wall in the air
 * and hold jump to climb it, like a spider. Once you're on the wall:
 * <ul>
 *   <li>hold jump to climb at {@link #CLIMB_SPEED};</li>
 *   <li>crouch to hang there;</li>
 *   <li>let go of both to slide down slowly ({@link #SLIDE_SPEED}), like on a ladder;</li>
 *   <li>let go of forward to drop off.</li>
 * </ul>
 * Reaching the top pulls you up over the edge. Grabbing a wall while falling catches you smoothly (it only ever slows
 * a fall, never a rise, so it can't spoil a jump or a leap), and nothing you fell before grabbing on hurts.
 * <p>
 * It only starts with jump or crouch while you're already off the ground and pushing into a wall, so bumping into
 * walls, building and sneaking at edges all work as always. Every grab, step and slide makes the wall's own block
 * sounds, quietly.
 * <p>
 * The client moves the player (see ClimbingClient); the server takes away the fall while they're on the wall and lets
 * everyone else hear them climb. To remove it: delete this package, {@code Climbing.register()} in RagnarsMagicMod,
 * {@code ClimbingClient.init()} in RagnarsMagicModClient, {@code ClimbingClient.onWall()} in JumpingClient and
 * GlidingClient, {@code Climbing.isClimbing} in Jumping, {@code CLIMBING} in SpellId, it in PassiveTomes.register,
 * {@code models/item/tome_of_climbing.json} and its en_us.json line.
 */
public final class Climbing {
    private Climbing() {}

    /** Upward speed while climbing, blocks a tick (a ladder's is 0.2). */
    public static final double CLIMB_SPEED = 0.18;
    /** How fast you slide down with nothing held but forward (a ladder's is 0.15). */
    public static final double SLIDE_SPEED = 0.15;
    /** How much a fall is slowed each tick when you grab on, so catching yourself feels like grip, not a wall of glue. */
    public static final double GRIP = 0.3;
    /** A step sound every this many blocks climbed. */
    public static final double STEP = 0.55;

    public static final TomeItem TOME_OF_CLIMBING = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_climbing"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE), // Passive
                    TomeTier.PASSIVE, SpellId.CLIMBING, 0
            ).setCooldown(0)
    );

    /** Client -> server: I've grabbed onto a wall ({@code on}) or let go of it. */
    public record ClimbPayload(boolean on) implements CustomPayload {
        public static final Id<ClimbPayload> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "climb"));
        public static final PacketCodec<RegistryByteBuf, ClimbPayload> CODEC =
                PacketCodec.tuple(PacketCodecs.BOOL, ClimbPayload::on, ClimbPayload::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    private static final Identifier SAFE_FALL = Identifier.of(RagnarsMagicMod.MOD_ID, "climb_safe_fall");
    /** A grab is dropped after this long with no wall beside them (a slide down a wall always has one). */
    private static final int NO_WALL_TICKS = 20;

    /** Players on a wall (server side): the height their last step sound played at, and ticks since a wall was beside them. */
    private static final Map<UUID, double[]> CLIMBING = new HashMap<>();

    public static void register() {
        ModItems.TOMES.computeIfAbsent(SpellId.CLIMBING, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.PASSIVE, TOME_OF_CLIMBING);
        Spells.register(SpellId.CLIMBING, new PassiveSpell("walk into a wall and hold jump to climb it. Crouch to hang on, let go of jump to slide down."));
        PayloadTypeRegistry.playC2S().register(ClimbPayload.ID, ClimbPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ClimbPayload.ID, (payload, context) -> set(context.player(), payload.on()));
        ServerTickEvents.END_SERVER_TICK.register(Climbing::tick);
    }

    /** True if {@code player} can climb at all: alive, not spectating, and carrying the tome. */
    public static boolean canClimb(PlayerEntity player) {
        return player.isAlive() && !player.isSpectator() && PassiveTomes.has(player, TOME_OF_CLIMBING);
    }

    /** Server: whether {@code player} is on a wall right now. */
    public static boolean isClimbing(PlayerEntity player) {
        return CLIMBING.containsKey(player.getUuid());
    }

    /**
     * Server: {@code player} has grabbed onto a wall or let go. A grab only counts with a wall really beside them; from
     * then until they let go, nothing they fall hurts. Public so tests can call it.
     */
    public static void set(ServerPlayerEntity player, boolean on) {
        if (on && canClimb(player) && wallAt(player) != null) {
            CLIMBING.putIfAbsent(player.getUuid(), new double[]{player.getY(), 0});
            FallGuard.hold(player, SAFE_FALL);
        } else if (!on) {
            CLIMBING.remove(player.getUuid());
            FallGuard.release(player, SAFE_FALL);
        }
    }

    private static void tick(MinecraftServer server) {
        if (CLIMBING.isEmpty()) return;
        CLIMBING.keySet().removeIf(id -> {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
            if (p == null) return true;
            if (!canClimb(p) || p.hasVehicle() || p.isTouchingWater() || !update(p)) {
                FallGuard.release(p, SAFE_FALL);
                return true;
            }
            return false;
        });
    }

    /**
     * Server, each tick on the wall: lets everyone else hear their steps up (or down) it. Returns false once they've
     * gone {@link #NO_WALL_TICKS} without a wall beside them, which ends the grab. Public so tests can call it.
     */
    public static boolean update(ServerPlayerEntity player) {
        double[] last = CLIMBING.get(player.getUuid());
        if (last == null) return false;
        BlockPos wall = wallAt(player);
        if (wall == null) return ++last[1] < NO_WALL_TICKS;
        last[1] = 0;
        if (Math.abs(player.getY() - last[0]) >= STEP) {
            last[0] = player.getY();
            ServerWorld sw = player.getServerWorld();
            BlockSoundGroup g = sw.getBlockState(wall).getSoundGroup();
            // The climber hears their own on their side; this is for everyone else
            sw.playSound(player, player.getX(), player.getY(), player.getZ(), g.getStepSound(), SoundCategory.PLAYERS,
                    g.getVolume() * 0.3f, g.getPitch());
        }
        return true;
    }

    /**
     * The solid block right beside {@code e} that they're holding on to: the way they're facing first, then the other
     * sides, checked at chest, foot and head height. Null if they're not next to anything. Shared by both sides.
     */
    public static @Nullable BlockPos wallAt(Entity e) {
        World w = e.getWorld();
        Direction facing = e.getHorizontalFacing();
        Direction[] sides = {facing, facing.rotateYClockwise(), facing.rotateYCounterclockwise(), facing.getOpposite()};
        double reach = e.getWidth() / 2 + 0.15;
        double[] heights = {e.getHeight() * 0.6, 0.15, e.getHeight() - 0.15};
        for (Direction d : sides) {
            for (double h : heights) {
                BlockPos pos = BlockPos.ofFloored(e.getX() + d.getOffsetX() * reach, e.getY() + h, e.getZ() + d.getOffsetZ() * reach);
                BlockState s = w.getBlockState(pos);
                if (!s.isAir() && !s.getCollisionShape(w, pos).isEmpty()) return pos;
            }
        }
        return null;
    }
}
