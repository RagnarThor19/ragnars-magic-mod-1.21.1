package net.ragnar.ragnarsmagicmod.util;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Who is under the Tome of Slipperiness, on each side. Mobs move on the server, but a player moves on their own client,
 * so the client has to know when its own player is slippery (see SlipperyPayload).
 */
public final class Slippery {
    private Slippery() {}

    /** Ground friction and acceleration are worked out from this, like a block's slipperiness (ice is 0.98). */
    public static final float SLIPPERINESS = 1.08f; // must stay under 1/0.91 = 1.099, or sliding would never slow down
    /** Scales walking input down so the top speed stays a little above normal; turning and stopping take seconds. */
    public static final double INPUT_SCALE = 0.3;

    /** Server side: ticks left per entity. */
    public static final Map<LivingEntity, Integer> SERVER = new IdentityHashMap<>();
    /** Client side: ticks left for your own player. */
    public static int clientTicks = 0;

    public static boolean isSlippery(Entity entity) {
        if (entity.getWorld().isClient) return clientTicks > 0 && entity instanceof PlayerEntity player && player.isMainPlayer();
        return entity instanceof LivingEntity living && SERVER.containsKey(living);
    }
}
