package net.ragnar.ragnarsmagicmod.jaunting;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.PersistentState;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Which kunai each player has out, and where it was last seen. Saved with the world, so the owner can flash back to
 * a kunai whose chunk isn't loaded (it was left behind on purpose), even after logging out.
 * <p>
 * This is also what keeps it to one kunai a player: a kunai that isn't the one listed here quietly goes away the
 * next time it ticks.
 */
public final class JauntingMarks {
    private JauntingMarks() {}

    public record Mark(UUID kunai, RegistryKey<World> world, Vec3d pos) {
        NbtCompound toNbt(UUID owner) {
            NbtCompound nbt = new NbtCompound();
            nbt.putUuid("owner", owner);
            nbt.putUuid("kunai", kunai);
            nbt.putString("world", world.getValue().toString());
            nbt.putDouble("x", pos.x);
            nbt.putDouble("y", pos.y);
            nbt.putDouble("z", pos.z);
            return nbt;
        }

        @Nullable
        static Mark fromNbt(NbtCompound nbt) {
            Identifier id = Identifier.tryParse(nbt.getString("world"));
            if (id == null || !nbt.containsUuid("kunai")) return null;
            return new Mark(nbt.getUuid("kunai"), RegistryKey.of(RegistryKeys.WORLD, id),
                    new Vec3d(nbt.getDouble("x"), nbt.getDouble("y"), nbt.getDouble("z")));
        }
    }

    static final class State extends PersistentState {
        final Map<UUID, Mark> marks = new HashMap<>();

        @Override
        public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
            NbtList list = new NbtList();
            marks.forEach((owner, mark) -> list.add(mark.toNbt(owner)));
            nbt.put("marks", list);
            return nbt;
        }

        static State fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
            State state = new State();
            NbtList list = nbt.getList("marks", NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < list.size(); i++) {
                NbtCompound entry = list.getCompound(i);
                Mark mark = Mark.fromNbt(entry);
                if (mark != null && entry.containsUuid("owner")) state.marks.put(entry.getUuid("owner"), mark);
            }
            return state;
        }
    }

    private static final PersistentState.Type<State> TYPE = new PersistentState.Type<>(State::new, State::fromNbt, null);
    private static State state;

    private static State state(MinecraftServer server) {
        if (state == null) {
            state = server.getOverworld().getPersistentStateManager().getOrCreate(TYPE, "ragnarsmagicmod_jaunting");
        }
        return state;
    }

    static void register() {
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> state = null);
    }

    @Nullable
    public static Mark get(MinecraftServer server, UUID owner) {
        return state(server).marks.get(owner);
    }

    static void set(MinecraftServer server, UUID owner, Mark mark) {
        State s = state(server);
        s.marks.put(owner, mark);
        s.markDirty();
    }

    @Nullable
    static Mark remove(MinecraftServer server, UUID owner) {
        State s = state(server);
        Mark mark = s.marks.remove(owner);
        if (mark != null) s.markDirty();
        return mark;
    }

    /** True if {@code kunai} is the one {@code owner} has out. */
    static boolean isCurrent(MinecraftServer server, UUID owner, UUID kunai) {
        Mark mark = state(server).marks.get(owner);
        return mark != null && mark.kunai().equals(kunai);
    }

    /** Notes where the owner's kunai is now. Only saved again once it has actually moved. */
    static void track(MinecraftServer server, UUID owner, UUID kunai, RegistryKey<World> world, Vec3d pos) {
        State s = state(server);
        Mark mark = s.marks.get(owner);
        if (mark == null || !mark.kunai().equals(kunai)) return;
        if (mark.world() == world && mark.pos().squaredDistanceTo(pos) < 0.01) return;
        s.marks.put(owner, new Mark(kunai, world, pos));
        s.markDirty();
    }

    /** Drops the owner's mark if it still points at {@code kunai}. Returns whether it did. */
    static boolean forget(MinecraftServer server, UUID owner, UUID kunai) {
        if (!isCurrent(server, owner, kunai)) return false;
        remove(server, owner);
        return true;
    }
}
