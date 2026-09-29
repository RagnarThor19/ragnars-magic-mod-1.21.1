package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.util.PortalNetwork;

/**
 * Tome of Portals. The first cast opens an amber portal on whatever you're looking at (wall, floor, ceiling,
 * or thin air); the second opens its cyan twin and links them. Anything that goes into one comes out of the
 * other, momentum and all, even from another dimension. Casting again closes both. Sneak-casting while only
 * the first is open closes it.
 *
 * <p>The XP is paid for the first portal, and the cooldown only starts once both are open. Closing them works
 * even while the tome is cooling down.
 */
public class PortalsSpell implements Spell {

    private static int stage(PlayerEntity player) {
        if (player.getWorld().isClient || player.getServer() == null) return 0;
        return PortalNetwork.stage(player.getServer(), player.getUuid());
    }

    @Override
    public boolean ignoresCooldown(PlayerEntity player) {
        return stage(player) == 2;
    }

    @Override
    public int xpCost(PlayerEntity player, int tomeCost) {
        if (player.getWorld().isClient) return 0;
        return stage(player) == 0 ? tomeCost : 0;
    }

    /** Runs after the cast: only a freshly linked pair starts the cooldown. */
    @Override
    public int cooldownAfterCast(PlayerEntity player, int tomeCooldown) {
        return stage(player) == 2 ? tomeCooldown : 0;
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient || !(world instanceof ServerWorld sw) || sw.getServer() == null) return false;
        var server = sw.getServer();
        int stage = PortalNetwork.stage(server, player.getUuid());

        if (stage == 2 || (stage == 1 && player.isSneaking())) {
            for (PortalNetwork.Portal p : PortalNetwork.close(server, player.getUuid())) {
                ServerWorld at = server.getWorld(p.world());
                if (at == null) continue;
                sound(at, p.center(), SoundEvents.BLOCK_BEACON_DEACTIVATE, 1.0f, 1.3f);
                at.playSound(null, p.center().x, p.center().y, p.center().z, SoundEvents.BLOCK_RESPAWN_ANCHOR_DEPLETE.value(),
                        SoundCategory.PLAYERS, 0.8f, 1.2f);
            }
            player.sendMessage(Text.literal(stage == 2 ? "The portals collapse." : "The portal collapses."), true);
            return true;
        }

        PortalNetwork.Portal portal = PortalNetwork.aim(sw, player);
        if (portal == null) return false;

        if (stage == 0) {
            PortalNetwork.open(server, player.getUuid(), portal);
            sound(sw, portal.center(), SoundEvents.BLOCK_END_PORTAL_FRAME_FILL, 1.0f, 0.7f);
            sound(sw, portal.center(), SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.7f, 1.4f);
            player.sendMessage(Text.literal("Portal opened • Cast again to open its twin"), true);
            return true;
        }

        PortalNetwork.Portal first = PortalNetwork.waiting(server, player.getUuid());
        if (first != null && PortalNetwork.tooClose(first, portal)) {
            player.sendMessage(Text.literal("Too close to your other portal."), true);
            return false;
        }
        PortalNetwork.open(server, player.getUuid(), portal);
        sound(sw, portal.center(), SoundEvents.BLOCK_END_PORTAL_FRAME_FILL, 1.0f, 0.9f);
        sound(sw, portal.center(), SoundEvents.BLOCK_BEACON_ACTIVATE, 1.0f, 1.5f);
        if (first != null) {
            ServerWorld firstWorld = server.getWorld(first.world());
            if (firstWorld != null) sound(firstWorld, first.center(), SoundEvents.BLOCK_BEACON_ACTIVATE, 1.0f, 1.5f);
        }
        player.sendMessage(Text.literal("The portals are linked • Cast again to close them"), true);
        return true;
    }

    private static void sound(ServerWorld world, Vec3d at, SoundEvent sound, float volume, float pitch) {
        world.playSound(null, at.x, at.y, at.z, sound, SoundCategory.PLAYERS, volume, pitch);
    }
}
