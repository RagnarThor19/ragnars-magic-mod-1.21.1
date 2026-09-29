package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.CrafterBlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ItemStackParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Tome of Sorting. Look at a chest, barrel or any other container to tidy it: matching stacks are merged and
 * everything is laid out in order. Sneak to tidy every container within a few blocks, one after another. Look at
 * nothing to tidy your own inventory (the hotbar is left alone). Each item type pops up out of the container with a
 * rising note as it's put in its place.
 */
public class SortingSpell implements Spell {
    private static final double REACH = 8.0;
    private static final int SNEAK_RADIUS = 5;
    private static final int MAX_POPS = 10;       // item types shown popping out per container
    private static final int POP_INTERVAL = 2;    // ticks between pops
    private static final int CHAIN_INTERVAL = 6;  // ticks between containers when sneaking

    private record Timed(ServerWorld world, long at, Runnable action) {}

    private static final List<Timed> QUEUE = new ArrayList<>();
    private static boolean registered = false;

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            if (QUEUE.isEmpty()) return;
            List<Timed> due = new ArrayList<>();
            for (Iterator<Timed> it = QUEUE.iterator(); it.hasNext(); ) {
                Timed t = it.next();
                if (t.world() == world && t.at() <= world.getTime()) { due.add(t); it.remove(); }
            }
            due.forEach(t -> t.action().run());
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> QUEUE.clear());
    }

    private static void later(ServerWorld world, int delay, Runnable action) {
        QUEUE.add(new Timed(world, world.getTime() + delay, action));
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        if (!(world instanceof ServerWorld sw)) return false;
        ensureRegistered();

        Vec3d eye = player.getEyePos();
        HitResult hit = world.raycast(new RaycastContext(eye, eye.add(player.getRotationVector().multiply(REACH)),
                RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player));
        BlockPos looked = hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK ? bhr.getBlockPos() : null;
        Inventory lookedAt = looked != null ? container(sw, looked) : null;

        if (lookedAt == null) return sortPlayer(sw, player);

        List<BlockPos> targets = new ArrayList<>();
        targets.add(looked);
        if (player.isSneaking()) {
            Set<BlockPos> seen = new HashSet<>();
            seen.add(looked);
            seen.add(otherHalf(sw, looked));
            BlockPos c = player.getBlockPos();
            for (BlockPos pos : BlockPos.iterate(c.add(-SNEAK_RADIUS, -SNEAK_RADIUS, -SNEAK_RADIUS), c.add(SNEAK_RADIUS, SNEAK_RADIUS, SNEAK_RADIUS))) {
                if (seen.contains(pos) || container(sw, pos) == null) continue;
                BlockPos found = pos.toImmutable();
                seen.add(found);
                seen.add(otherHalf(sw, found));
                targets.add(found);
            }
            // Ripple outwards from the one you're looking at
            targets.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(looked)));
        }

        int stacksBefore = 0, stacksAfter = 0, sorted = 0;
        for (int i = 0; i < targets.size(); i++) {
            BlockPos pos = targets.get(i);
            Inventory inv = container(sw, pos);
            if (inv == null) continue;
            int before = countStacks(inv, 0, inv.size());
            if (before == 0) continue;
            List<ItemStack> kinds = sort(inv, 0, inv.size());
            stacksBefore += before;
            stacksAfter += countStacks(inv, 0, inv.size());
            sorted++;
            celebrate(sw, pos, kinds, i * CHAIN_INTERVAL);
        }

        if (sorted == 0) {
            player.sendMessage(Text.literal("There's nothing in there to sort."), true);
            return false;
        }
        player.sendMessage(Text.literal(summary(sorted == 1 ? "Sorted 1 container" : "Sorted " + sorted + " containers", stacksBefore, stacksAfter)), true);
        return true;
    }

    /** Your own inventory, not counting the hotbar. */
    private static boolean sortPlayer(ServerWorld world, PlayerEntity player) {
        Inventory inv = player.getInventory();
        int before = countStacks(inv, 9, 36);
        if (before == 0) {
            player.sendMessage(Text.literal("Your inventory is already empty."), true);
            return false;
        }
        List<ItemStack> kinds = sort(inv, 9, 36);
        int after = countStacks(inv, 9, 36);

        Vec3d c = player.getPos().add(0, 1.0, 0);
        for (int i = 0; i < Math.min(kinds.size(), MAX_POPS); i++) {
            ItemStack kind = kinds.get(i);
            float pitch = 0.7f + i * (1.3f / MAX_POPS);
            double a = i * Math.PI * 2 / Math.min(kinds.size(), MAX_POPS);
            later(world, i * POP_INTERVAL, () -> {
                // Items orbit out around you and drop back in
                world.spawnParticles(new ItemStackParticleEffect(ParticleTypes.ITEM, kind), c.x + Math.cos(a) * 0.7, c.y, c.z + Math.sin(a) * 0.7,
                        0, Math.cos(a) * 0.1, 0.25, Math.sin(a) * 0.1, 1.0);
                world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.5f, pitch);
            });
        }
        finish(world, c, Math.min(kinds.size(), MAX_POPS) * POP_INTERVAL + 2);
        player.sendMessage(Text.literal(summary("Sorted your inventory", before, after)), true);
        return true;
    }

    // Sorting

    /**
     * Merges and orders the stacks in slots {@code from..to-1}. Returns one stack of each item type, in the new order,
     * for the effects.
     */
    private static List<ItemStack> sort(Inventory inv, int from, int to) {
        List<ItemStack> merged = new ArrayList<>();
        for (int slot = from; slot < to; slot++) {
            ItemStack stack = inv.getStack(slot);
            if (stack.isEmpty()) continue;
            ItemStack left = stack.copy();
            for (ItemStack into : merged) {
                if (left.isEmpty()) break;
                if (!ItemStack.areItemsAndComponentsEqual(into, left) || into.getCount() >= into.getMaxCount()) continue;
                int move = Math.min(left.getCount(), into.getMaxCount() - into.getCount());
                into.increment(move);
                left.decrement(move);
            }
            if (!left.isEmpty()) merged.add(left);
        }

        // Registry order keeps like with like (stone together, wood together, tools together...), fullest stacks first
        merged.sort(Comparator
                .comparingInt((ItemStack s) -> Registries.ITEM.getRawId(s.getItem()))
                .thenComparing(s -> s.getName().getString())
                .thenComparing(Comparator.comparingInt(ItemStack::getCount).reversed()));

        for (int slot = from; slot < to; slot++) {
            int i = slot - from;
            inv.setStack(slot, i < merged.size() ? merged.get(i) : ItemStack.EMPTY);
        }
        inv.markDirty();

        List<ItemStack> kinds = new ArrayList<>();
        for (ItemStack s : merged) {
            if (kinds.isEmpty() || !ItemStack.areItemsAndComponentsEqual(kinds.get(kinds.size() - 1), s)) kinds.add(s.copyWithCount(1));
        }
        return kinds;
    }

    private static int countStacks(Inventory inv, int from, int to) {
        int n = 0;
        for (int slot = from; slot < to; slot++) if (!inv.getStack(slot).isEmpty()) n++;
        return n;
    }

    private static String summary(String what, int before, int after) {
        int merged = before - after;
        return merged > 0 ? what + " - " + merged + (merged == 1 ? " stack" : " stacks") + " merged!" : what + "!";
    }

    // Effects

    /** The lid pops, each item type hops out in order with a rising note, then a chime. */
    private static void celebrate(ServerWorld world, BlockPos pos, List<ItemStack> kinds, int delay) {
        Vec3d top = Vec3d.ofCenter(pos).add(0, 0.6, 0);
        BlockState state = world.getBlockState(pos);
        boolean chest = state.getBlock() instanceof ChestBlock;
        int pops = Math.min(kinds.size(), MAX_POPS);

        later(world, delay, () -> {
            if (chest) world.addSyncedBlockEvent(pos, state.getBlock(), 1, 1);
            world.playSound(null, top.x, top.y, top.z, SoundEvents.ITEM_BUNDLE_DROP_CONTENTS, SoundCategory.BLOCKS, 0.7f, 1.2f);
            world.spawnParticles(ParticleTypes.ENCHANT, top.x, top.y + 0.6, top.z, 25, 0.4, 0.4, 0.4, 0.6);
        });
        for (int i = 0; i < pops; i++) {
            ItemStack kind = kinds.get(i);
            float pitch = 0.7f + i * (1.3f / MAX_POPS);
            later(world, delay + 3 + i * POP_INTERVAL, () -> {
                world.spawnParticles(new ItemStackParticleEffect(ParticleTypes.ITEM, kind), top.x, top.y, top.z,
                        0, (world.random.nextDouble() - 0.5) * 0.15, 0.35, (world.random.nextDouble() - 0.5) * 0.15, 1.0);
                world.playSound(null, top.x, top.y, top.z, SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.BLOCKS, 0.5f, pitch);
            });
        }
        int end = delay + 3 + pops * POP_INTERVAL;
        if (chest) {
            later(world, end + 4, () -> {
                BlockState now = world.getBlockState(pos);
                if (now.getBlock() instanceof ChestBlock) {
                    world.addSyncedBlockEvent(pos, now.getBlock(), 1, ChestBlockEntity.getPlayersLookingInChestCount(world, pos));
                    world.playSound(null, top.x, top.y, top.z, SoundEvents.BLOCK_CHEST_CLOSE, SoundCategory.BLOCKS, 0.4f, 1.3f);
                }
            });
        }
        finish(world, top, end + 2);
    }

    /** The "all done" chime and sparkle. */
    private static void finish(ServerWorld world, Vec3d at, int delay) {
        later(world, delay, () -> {
            world.playSound(null, at.x, at.y, at.z, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.BLOCKS, 1.0f, 1.5f);
            world.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.BLOCKS, 0.4f, 1.6f);
            world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, at.x, at.y + 0.2, at.z, 8, 0.35, 0.2, 0.35, 0);
        });
    }

    // Helpers

    /**
     * The storage container at {@code pos} (both halves of a double chest together), or null. Only plain storage:
     * furnaces, brewing stands and crafters care which slot things are in.
     */
    private static Inventory container(ServerWorld world, BlockPos pos) {
        BlockEntity be = world.getBlockEntity(pos);
        if (!(be instanceof LootableContainerBlockEntity) || be instanceof CrafterBlockEntity) return null;
        return HopperBlockEntity.getInventoryAt(world, pos);
    }

    /** The other half of a double chest, or the same position. */
    private static BlockPos otherHalf(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
            return pos.offset(ChestBlock.getFacing(state)).toImmutable();
        }
        return pos;
    }
}
