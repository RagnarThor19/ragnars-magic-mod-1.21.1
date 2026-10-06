package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;

/**
 * Tome of Speed: a passive tome. While it's on a staff you're carrying (in either hand or anywhere in your
 * inventory) you have Speed I; take it off and the Speed goes with it. Costs nothing and has no cooldown; casting it
 * just says how it works.
 */
public class SpeedSpell implements Spell {
    /** How often the Speed is topped up, and how long each top-up lasts: a little longer, so it never flickers. */
    private static final int CHECK_EVERY = 10, TOP_UP = 40;

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(SpeedSpell::tick);
    }

    private static void tick(MinecraftServer server) {
        if (server.getTicks() % CHECK_EVERY != 0) return;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) update(player);
    }

    /** Gives {@code player} the tome's Speed if they carry it on a staff, or takes it away if they don't. */
    public static void update(PlayerEntity player) {
        if (!player.isAlive() || player.isSpectator()) return;
        boolean carrying = !StaffItem.findStaffWith(player, ModItems.TOME_OF_SPEED).isEmpty();
        StatusEffectInstance current = player.getStatusEffect(StatusEffects.SPEED);
        if (carrying) {
            // Ambient and particle-free, like a beacon's: just the icon. Never cuts a stronger or longer Speed short.
            if (current == null || current.getAmplifier() == 0 && current.isAmbient() && current.getDuration() <= TOP_UP) {
                player.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, TOP_UP, 0, true, false, true));
            }
        } else if (current != null && ours(current)) {
            player.removeStatusEffect(StatusEffects.SPEED);
        }
    }

    /** True for the Speed this tome hands out (and not, say, a potion's or a beacon's). */
    private static boolean ours(StatusEffectInstance effect) {
        return effect.getAmplifier() == 0 && effect.isAmbient() && !effect.shouldShowParticles() && effect.getDuration() <= TOP_UP;
    }

    @Override
    public int xpCost(PlayerEntity player, int tomeCost) {
        return 0;
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!world.isClient) {
            player.sendMessage(Text.literal("Passive: Speed I while this tome is on a staff you carry.").formatted(Formatting.AQUA), true);
        }
        return false;
    }
}
