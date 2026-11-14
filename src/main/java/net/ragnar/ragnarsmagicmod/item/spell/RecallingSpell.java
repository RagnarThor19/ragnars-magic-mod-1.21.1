package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.PersistentState;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tome of Recalling. The first cast leaves a mark at your feet (free); the next one brings you back to it, from
 * anywhere - even another dimension - for the tome's XP, and uses the mark up.
 * <p>
 * The mark shows as a tiny floating lodestone that only its owner sees (RecallMarkerClient). The server only tells
 * them where it is when it's set, used, or they join: it never sends anything while the mark just sits there.
 * <p>
 * Marks are saved with the world, so one left behind still waits for you after logging out or a server restart.
 */
public class RecallingSpell implements Spell {

    /** A saved mark: which dimension, and where. */
    private record RecallPoint(RegistryKey<World> worldKey, BlockPos pos) {}

    /** Server -> owner: your mark is now at {@code pos} in {@code dimension} (or, if {@code has} is false, gone). */
    public record MarkPayload(boolean has, String dimension, BlockPos pos) implements CustomPayload {
        public static final Id<MarkPayload> ID = new Id<>(Identifier.of(net.ragnar.ragnarsmagicmod.RagnarsMagicMod.MOD_ID, "recall_mark"));
        public static final PacketCodec<RegistryByteBuf, MarkPayload> CODEC = PacketCodec.of(
                (p, buf) -> {
                    buf.writeBoolean(p.has);
                    buf.writeString(p.dimension);
                    buf.writeBlockPos(p.pos);
                },
                buf -> new MarkPayload(buf.readBoolean(), buf.readString(), buf.readBlockPos()));

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    private static boolean registered = false;

    public RecallingSpell() {
        if (registered) return;
        registered = true;
        PayloadTypeRegistry.playS2C().register(MarkPayload.ID, MarkPayload.CODEC);
        // Joining: show them the mark they left behind
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sendMark(handler.player));
    }

    /** Tells {@code player} where their mark is (or that they have none). */
    private static void sendMark(ServerPlayerEntity player) {
        if (player.getServer() == null || !ServerPlayNetworking.canSend(player, MarkPayload.ID)) return;
        RecallPoint mark = state(player.getServer()).marks.get(player.getUuid());
        ServerPlayNetworking.send(player, mark == null ? new MarkPayload(false, "", BlockPos.ORIGIN)
                : new MarkPayload(true, mark.worldKey().getValue().toString(), mark.pos()));
    }

    /** Setting the mark is free; the XP is paid when you recall to it. */
    @Override
    public int xpCost(PlayerEntity player, int tomeCost) {
        return hasMark(player) ? tomeCost : 0;
    }

    /** Whether {@code player} has a mark set (server side; the client can't know, and pays nothing up front). */
    public static boolean hasMark(PlayerEntity player) {
        MinecraftServer server = player.getServer();
        return server != null && state(server).marks.containsKey(player.getUuid());
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staffStack) {
        if (!(world instanceof ServerWorld serverWorld)) return false;
        State state = state(serverWorld.getServer());
        UUID id = player.getUuid();
        RecallPoint existing = state.marks.get(id);

        if (existing == null) {
            // First cast: set a mark at the player's feet
            BlockPos base = BlockPos.ofFloored(player.getX(), player.getY(), player.getZ());
            state.marks.put(id, new RecallPoint(world.getRegistryKey(), base));
            state.markDirty();
            if (player instanceof ServerPlayerEntity sp) sendMark(sp);
            world.playSound(null, base, SoundEvents.BLOCK_ENCHANTMENT_TABLE_USE, SoundCategory.PLAYERS, 1.0f, 1.0f);
            world.playSound(null, base, SoundEvents.BLOCK_LODESTONE_PLACE, SoundCategory.PLAYERS, 0.8f, 1.2f);
            return true;
        }

        // Second cast: back to the mark, from anywhere - even another dimension
        ServerWorld targetWorld = serverWorld.getServer().getWorld(existing.worldKey());
        if (targetWorld == null) {
            state.marks.remove(id);
            state.markDirty();
            return false;
        }
        if (player instanceof ServerPlayerEntity serverPlayer) {
            double x = existing.pos().getX() + 0.5, y = existing.pos().getY(), z = existing.pos().getZ() + 0.5;
            // One puff where they leave, one where they arrive: two packets, not a stream
            serverWorld.spawnParticles(ParticleTypes.REVERSE_PORTAL, player.getX(), player.getY() + 1, player.getZ(), 30, 0.3, 0.6, 0.3, 0.05);
            serverPlayer.teleport(targetWorld, x, y, z, player.getYaw(), player.getPitch());
            targetWorld.spawnParticles(ParticleTypes.ENCHANT, x, y + 1, z, 40, 0.4, 0.6, 0.4, 0.8);
            targetWorld.playSound(null, existing.pos(), SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 1.0f, 1.0f);
            targetWorld.playSound(null, existing.pos(), SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 1.0f, 1.2f);
        }
        // The mark is used up
        state.marks.remove(id);
        state.markDirty();
        if (player instanceof ServerPlayerEntity sp) sendMark(sp);
        return true;
    }

    // ---------------------------------------------------------------------
    // Saving: every player's mark, kept with the overworld's data
    // ---------------------------------------------------------------------

    private static State state(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE, "ragnarsmagicmod_recall_marks");
    }

    private static final PersistentState.Type<State> TYPE = new PersistentState.Type<>(State::new, State::fromNbt, null);

    public static final class State extends PersistentState {
        final Map<UUID, RecallPoint> marks = new HashMap<>();

        @Override
        public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
            NbtCompound players = new NbtCompound();
            marks.forEach((id, mark) -> {
                NbtCompound m = new NbtCompound();
                m.putString("dimension", mark.worldKey().getValue().toString());
                m.putInt("x", mark.pos().getX());
                m.putInt("y", mark.pos().getY());
                m.putInt("z", mark.pos().getZ());
                players.put(id.toString(), m);
            });
            nbt.put("players", players);
            return nbt;
        }

        static State fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
            State s = new State();
            NbtCompound players = nbt.getCompound("players");
            for (String key : players.getKeys()) {
                NbtCompound m = players.getCompound(key);
                Identifier dim = Identifier.tryParse(m.getString("dimension"));
                if (dim == null) continue;
                try {
                    s.marks.put(UUID.fromString(key), new RecallPoint(RegistryKey.of(RegistryKeys.WORLD, dim),
                            new BlockPos(m.getInt("x"), m.getInt("y"), m.getInt("z"))));
                } catch (IllegalArgumentException ignored) {
                    // not a player id
                }
            }
            return s;
        }
    }

    /** For tests: what a saved mark looks like written out and read back in (as across a restart). */
    public static boolean survivesSaving(PlayerEntity player) {
        MinecraftServer server = player.getServer();
        if (server == null) return false;
        State saved = state(server);
        RecallPoint before = saved.marks.get(player.getUuid());
        if (before == null) return false;
        State loaded = State.fromNbt(saved.writeNbt(new NbtCompound(), server.getRegistryManager()), server.getRegistryManager());
        return before.equals(loaded.marks.get(player.getUuid()));
    }
}
