package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.world.PersistentState;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tome of Storage: opens a private 36-slot storage (four rows - more than a chest, less than a double chest).
 * The storage belongs to the player, not the book: it's saved with the world under their UUID, so any Tome of
 * Storage opens the caster's own, nobody else can ever reach it, and it survives death and losing the tome.
 */
public class StorageSpell implements Spell {
    public static final int ROWS = 4;
    public static final int SIZE = ROWS * 9;
    private static final Text TITLE = Text.translatable("container.ragnarsmagicmod.storage");

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw)) return false;
        SimpleInventory inventory = inventoryOf(sw.getServer(), player.getUuid());
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory((syncId, playerInventory, p) ->
                new GenericContainerScreenHandler(ScreenHandlerType.GENERIC_9X4, syncId, playerInventory, inventory, ROWS) {
                    @Override
                    public void onClosed(PlayerEntity closer) {
                        super.onClosed(closer);
                        if (closer.getWorld() instanceof ServerWorld w) closeEffects(w, closer);
                    }
                }, TITLE));
        openEffects(sw, player);
        return true;
    }

    /** The player's storage, created empty the first time. */
    public static SimpleInventory inventoryOf(MinecraftServer server, UUID player) {
        return state(server).get(player);
    }

    // ---------------------------------------------------------------------
    // Effects
    // ---------------------------------------------------------------------

    private static void openEffects(ServerWorld world, PlayerEntity p) {
        double x = p.getX(), y = p.getY(), z = p.getZ();
        world.playSound(null, x, y, z, SoundEvents.ITEM_BOOK_PAGE_TURN, SoundCategory.PLAYERS, 1.0f, 0.9f);
        world.playSound(null, x, y, z, SoundEvents.BLOCK_CHEST_OPEN, SoundCategory.PLAYERS, 0.45f, 1.25f);
        world.playSound(null, x, y, z, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.9f, 1.5f);
        // Glyphs stream in from all around and sink into you, like at an enchanting table
        for (int i = 0; i < 24; i++) {
            double a = i * Math.PI * 2 / 24;
            double r = 1.4 + world.random.nextDouble() * 0.6;
            world.spawnParticles(ParticleTypes.ENCHANT, x, y + 1.1, z, 0,
                    Math.cos(a) * r, 0.4 + world.random.nextDouble() * 0.8, Math.sin(a) * r, 1.0);
        }
        world.spawnParticles(ParticleTypes.END_ROD, x, y + 1.2, z, 4, 0.3, 0.4, 0.3, 0.02);
    }

    private static void closeEffects(ServerWorld world, PlayerEntity p) {
        double x = p.getX(), y = p.getY(), z = p.getZ();
        world.playSound(null, x, y, z, SoundEvents.ITEM_BOOK_PUT, SoundCategory.PLAYERS, 1.0f, 1.0f);
        world.playSound(null, x, y, z, SoundEvents.BLOCK_CHEST_CLOSE, SoundCategory.PLAYERS, 0.35f, 1.3f);
        world.spawnParticles(ParticleTypes.ENCHANT, x, y + 1.2, z, 12, 0.3, 0.3, 0.3, 0.6);
    }

    // ---------------------------------------------------------------------
    // Saving: one storage per player, kept with the overworld's data
    // ---------------------------------------------------------------------

    private static State state(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE, "ragnarsmagicmod_storage");
    }

    private static final PersistentState.Type<State> TYPE = new PersistentState.Type<>(State::new, State::fromNbt, null);

    public static final class State extends PersistentState {
        private final Map<UUID, SimpleInventory> storages = new HashMap<>();

        SimpleInventory get(UUID player) {
            return storages.computeIfAbsent(player, id -> watched(new SimpleInventory(SIZE)));
        }

        private SimpleInventory watched(SimpleInventory inventory) {
            inventory.addListener(changed -> markDirty());
            return inventory;
        }

        @Override
        public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
            NbtCompound players = new NbtCompound();
            storages.forEach((id, inventory) -> {
                if (inventory.isEmpty()) return;
                players.put(id.toString(), Inventories.writeNbt(new NbtCompound(), inventory.getHeldStacks(), lookup));
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
                SimpleInventory inventory = state.get(id);
                Inventories.readNbt(players.getCompound(key), inventory.getHeldStacks(), lookup);
            }
            return state;
        }
    }
}
