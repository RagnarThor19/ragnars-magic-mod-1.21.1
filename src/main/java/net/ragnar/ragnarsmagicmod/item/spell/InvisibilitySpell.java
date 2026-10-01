package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.network.InvisibilityPayload;
import net.ragnar.ragnarsmagicmod.util.AbsoluteInvisibility;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Tome of Invisibility: completely invisible for {@link #DURATION_TICKS} - no body, armor, held items, name, shadow or
 * sprint dust for anyone else, and mobs find you as hard to spot as if you wore no armor. Cast again to show yourself
 * early. The tome's cooldown starts once you're visible again.
 */
public class InvisibilitySpell implements Spell {
    public static final int DURATION_TICKS = 20 * 30;

    private static final class Hidden {
        final PlayerEntity player;
        int left = DURATION_TICKS;

        Hidden(PlayerEntity player) {
            this.player = player;
        }
    }

    private static final Map<UUID, Hidden> HIDDEN = new HashMap<>();

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> tick());
        // Players who come into view need to be told, or they'd see the armor
        EntityTrackingEvents.START_TRACKING.register((entity, viewer) -> {
            if (entity instanceof ServerPlayerEntity sp && HIDDEN.containsKey(sp.getUuid())) InvisibilityPayload.sendTo(viewer, sp);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (HIDDEN.remove(handler.player.getUuid()) != null) {
                AbsoluteInvisibility.SERVER.remove(handler.player.getUuid());
                handler.player.removeStatusEffect(StatusEffects.INVISIBILITY);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            HIDDEN.clear();
            AbsoluteInvisibility.SERVER.clear();
        });
    }

    /** True while the spell is running (either side: the client knows its own effects). */
    public static boolean isActive(PlayerEntity player) {
        if (player.getWorld().isClient) return player.hasStatusEffect(StatusEffects.INVISIBILITY);
        return HIDDEN.containsKey(player.getUuid());
    }

    // Casting again shows you early: free, and allowed while the tome is cooling down
    @Override
    public boolean ignoresCooldown(PlayerEntity player) {
        return isActive(player);
    }

    @Override
    public int xpCost(PlayerEntity player, int tomeCost) {
        return isActive(player) ? 0 : tomeCost;
    }

    @Override
    public int cooldownAfterCast(PlayerEntity player, int tomeCooldown) {
        return 0; // starts when you become visible again (see end)
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        if (HIDDEN.containsKey(player.getUuid())) {
            end(player);
            return true;
        }
        HIDDEN.put(player.getUuid(), new Hidden(player));
        AbsoluteInvisibility.SERVER.add(player.getUuid());
        // No particles, but keep the icon so you can see the time left
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.INVISIBILITY, DURATION_TICKS, 0, false, false, true));
        if (player instanceof ServerPlayerEntity sp) InvisibilityPayload.broadcast(sp, true);

        world.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BLOCK_ENCHANTMENT_TABLE_USE, SoundCategory.PLAYERS, 0.3f, 1.2f);
        world.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ENTITY_ILLUSIONER_PREPARE_MIRROR, SoundCategory.PLAYERS, 0.3f, 1.8f);
        return true;
    }

    private static void tick() {
        if (HIDDEN.isEmpty()) return;
        Iterator<Hidden> it = HIDDEN.values().iterator();
        while (it.hasNext()) {
            Hidden h = it.next();
            PlayerEntity player = h.player;
            if (player.isRemoved() && player.getRemovalReason() != null && !player.getRemovalReason().shouldDestroy()) {
                continue; // changing dimension: same player, carries on
            }
            // Time's up, died, or the effect was taken away (milk): visible again
            if (--h.left <= 0 || !player.isAlive() || player.isRemoved() || !player.hasStatusEffect(StatusEffects.INVISIBILITY)) {
                it.remove();
                reveal(player);
            }
        }
    }

    /** Show yourself early (cast again). */
    private static void end(PlayerEntity player) {
        HIDDEN.remove(player.getUuid());
        reveal(player);
    }

    private static void reveal(PlayerEntity player) {
        AbsoluteInvisibility.SERVER.remove(player.getUuid());
        player.removeStatusEffect(StatusEffects.INVISIBILITY);
        if (player instanceof ServerPlayerEntity sp) InvisibilityPayload.broadcast(sp, false);
        player.getWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ENTITY_ILLUSIONER_MIRROR_MOVE, SoundCategory.PLAYERS, 0.5f, 1.4f);

        // Only now does the cooldown start
        TomeItem tome = (TomeItem) ModItems.TOME_INVISIBILITY;
        ItemStack staff = StaffItem.findStaffWith(player, tome);
        if (!staff.isEmpty()) StaffItem.applyCooldown(player.getWorld(), player, staff, tome, tome.getCooldown());
        else player.getItemCooldownManager().set(tome, tome.getCooldown());
    }

    /** For the game tests: skip ahead to the last few ticks. */
    public static void setTicksLeft(PlayerEntity player, int ticks) {
        Hidden h = HIDDEN.get(player.getUuid());
        if (h != null) h.left = ticks;
    }
}
