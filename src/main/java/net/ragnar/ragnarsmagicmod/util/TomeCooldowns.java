package net.ragnar.ragnarsmagicmod.util;

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.player.ItemCooldownManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.world.PersistentState;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.mixin.ItemCooldownEntryAccessor;
import net.ragnar.ragnarsmagicmod.mixin.ItemCooldownManagerAccessor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Vanilla never saves item cooldowns: they live only on the player object, so leaving the world or dying wiped every
 * tome cooldown. This keeps them. On leaving, each tome's remaining ticks are saved with the world; on joining they're
 * put back, paused for the time you were away. Dying carries them straight over to the respawned player.
 * Only tomes are kept - vanilla items behave as they always have.
 */
public final class TomeCooldowns {
    private TomeCooldowns() {}

    public static void register() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            Map<Item, Integer> left = remaining(handler.player);
            State s = state(server);
            if (left.isEmpty()) {
                if (s.saved.remove(handler.player.getUuid()) != null) s.markDirty();
            } else {
                s.saved.put(handler.player.getUuid(), left);
                s.markDirty();
            }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            State s = state(server);
            Map<Item, Integer> left = s.saved.remove(handler.player.getUuid());
            if (left == null) return;
            s.markDirty();
            restore(handler.player, left);
        });
        // After the respawn, so the client's new player gets the cooldown overlay too (also covers leaving the End)
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> restore(newPlayer, remaining(oldPlayer)));
    }

    /** Ticks left on each tome cooldown this player has running. */
    public static Map<Item, Integer> remaining(PlayerEntity player) {
        ItemCooldownManager manager = player.getItemCooldownManager();
        ItemCooldownManagerAccessor access = (ItemCooldownManagerAccessor) manager;
        int now = access.ragnarsmagicmod$getTick();
        Map<Item, Integer> left = new LinkedHashMap<>();
        access.ragnarsmagicmod$getEntries().forEach((item, entry) -> {
            if (!(item instanceof TomeItem)) return;
            int ticks = ((ItemCooldownEntryAccessor) entry).ragnarsmagicmod$getEndTick() - now;
            if (ticks > 0) left.put(item, ticks);
        });
        return left;
    }

    public static void restore(PlayerEntity player, Map<Item, Integer> left) {
        left.forEach((item, ticks) -> player.getItemCooldownManager().set(item, ticks));
    }

    // ---------------------------------------------------------------------
    // Saving: remaining ticks per player, kept with the overworld's data
    // ---------------------------------------------------------------------

    private static State state(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE, "ragnarsmagicmod_cooldowns");
    }

    private static final PersistentState.Type<State> TYPE = new PersistentState.Type<>(State::new, State::fromNbt, null);

    public static final class State extends PersistentState {
        final Map<UUID, Map<Item, Integer>> saved = new HashMap<>();

        @Override
        public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
            NbtCompound players = new NbtCompound();
            saved.forEach((id, left) -> {
                NbtCompound tomes = new NbtCompound();
                left.forEach((item, ticks) -> tomes.putInt(Registries.ITEM.getId(item).toString(), ticks));
                players.put(id.toString(), tomes);
            });
            nbt.put("players", players);
            return nbt;
        }

        public static State fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
            State state = new State();
            NbtCompound players = nbt.getCompound("players");
            for (String key : players.getKeys()) {
                UUID id;
                try {
                    id = UUID.fromString(key);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                NbtCompound tomes = players.getCompound(key);
                Map<Item, Integer> left = new LinkedHashMap<>();
                for (String itemId : tomes.getKeys()) {
                    Identifier itemKey = Identifier.tryParse(itemId);
                    // A tome that's since been removed from the mod is simply dropped
                    if (itemKey == null || !Registries.ITEM.containsId(itemKey)) continue;
                    left.put(Registries.ITEM.get(itemKey), tomes.getInt(itemId));
                }
                if (!left.isEmpty()) state.saved.put(id, left);
            }
            return state;
        }
    }
}
