package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.network.WingsPayload;
import net.ragnar.ragnarsmagicmod.util.WingsState;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Tome of Wings. Wings of light burst from your back and you're flung forward like an elytra on a firework
 * for a moment - no elytra needed. Real gliding physics: steer well, or hit the wall / the ground hard.
 * Wearing an elytra works too: the wings take over while they last, then you're left gliding on the elytra.
 * The firework push itself runs on the client (WingsClient) like a real rocket does, so it's smooth.
 */
public class WingsSpell implements Spell {
    public static final int FLIGHT_TICKS = 20; // 1 second
    private static final int GRACE_TICKS = 4;  // time to get off the ground before landing counts

    private static final Map<UUID, World> WORLDS = new java.util.HashMap<>();
    private static boolean registered = false;

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            Iterator<Map.Entry<UUID, Integer>> it = WingsState.SERVER.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Integer> e = it.next();
                if (WORLDS.get(e.getKey()) != world) continue;
                PlayerEntity p = world.getPlayerByUuid(e.getKey());
                int left = e.getValue() - 1;
                boolean landed = p != null && left < FLIGHT_TICKS - GRACE_TICKS && (p.isOnGround() || p.isTouchingWater());
                if (p == null || !p.isAlive() || left <= 0 || landed) {
                    it.remove();
                    WORLDS.remove(e.getKey());
                    if (p != null) fold(world, p);
                } else {
                    e.setValue(left);
                }
            }
        });
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        if (!(world instanceof ServerWorld sw)) return false;
        if (player.hasVehicle() || player.isTouchingWater()) return false;
        ensureRegistered();

        WingsState.SERVER.put(player.getUuid(), FLIGHT_TICKS);
        WORLDS.put(player.getUuid(), world);
        WingsPayload.broadcast(sw, player.getId(), FLIGHT_TICKS);

        // Take off: a leap off the ground, or a surge if already airborne
        Vec3d look = player.getRotationVector();
        if (player.isOnGround()) player.addVelocity(look.x * 0.4, 0.75, look.z * 0.4);
        else player.addVelocity(look.x * 0.5, Math.max(0.2, look.y * 0.5), look.z * 0.5);
        player.velocityModified = true;
        player.startFallFlying();

        Vec3d p = player.getPos();
        sw.playSound(null, p.x, p.y, p.z, SoundEvents.ENTITY_ENDER_DRAGON_FLAP, SoundCategory.PLAYERS, 1.2f, 1.6f);
        // An airy, angelic lift instead of a rocket
        sw.playSound(null, p.x, p.y, p.z, SoundEvents.ENTITY_ALLAY_ITEM_GIVEN, SoundCategory.PLAYERS, 1.0f, 1.2f);
        sw.playSound(null, p.x, p.y, p.z, SoundEvents.ENTITY_PHANTOM_FLAP, SoundCategory.PLAYERS, 1.0f, 1.3f);
        sw.spawnParticles(ParticleTypes.CLOUD, p.x, p.y + 0.2, p.z, 20, 0.6, 0.1, 0.6, 0.08);
        sw.spawnParticles(ParticleTypes.END_ROD, p.x, p.y + 1.2, p.z, 16, 0.8, 0.4, 0.8, 0.06);
        return true;
    }

    /** The wings fold away and scatter into light. */
    private static void fold(World world, PlayerEntity player) {
        if (world instanceof ServerWorld sw) {
            WingsPayload.broadcast(sw, player.getId(), 0);
            Vec3d p = player.getPos();
            sw.playSound(null, p.x, p.y, p.z, SoundEvents.ENTITY_PHANTOM_FLAP, SoundCategory.PLAYERS, 0.8f, 0.8f);
            sw.playSound(null, p.x, p.y, p.z, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.8f, 1.4f);
            sw.spawnParticles(ParticleTypes.END_ROD, p.x, p.y + 1.0, p.z, 14, 0.9, 0.4, 0.9, 0.03);
        }
    }
}
