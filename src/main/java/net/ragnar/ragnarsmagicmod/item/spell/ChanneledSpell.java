package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;

/**
 * A spell you hold right-click to keep going, like drawing a bow. When {@link #cast} returns true the staff starts
 * being used; the server then calls {@link #channelTick} every tick the button stays held, and {@link #channelStop}
 * when it's let go (or the spell ends itself). Cooldowns are the spell's own business: the staff applies
 * {@link #cooldownAfterCast} as usual when a use starts, so return 0 there and apply it yourself when it's due.
 */
public interface ChanneledSpell extends Spell {
    /** One held tick, on the server. False ends the use (as if the button were let go). */
    boolean channelTick(ServerWorld world, PlayerEntity player, ItemStack staff, TomeItem tome);

    /** The button was let go, or the use ended some other way. On the server. */
    void channelStop(ServerWorld world, PlayerEntity player);
}
