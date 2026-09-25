package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.network.DayShiftPayload;
import net.ragnar.ragnarsmagicmod.util.DayShift;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Tome of Dusk and Dawn. The caster calls a pillar of light into the sky and turns the heavens half a day
 * round - noon to midnight, dusk to dawn - the sun and moon racing overhead while bells toll across the whole
 * server and the ground trembles.
 */
public class DuskAndDawnSpell implements Spell {
    // Bells toll as the hours pass
    private static final int[] TOLLS = {0, 35, 70, 105};

    private static final class Shift {
        final ServerWorld world;
        final long start, end;
        final boolean toNight;
        final Vec3d at;
        int age = 0;

        Shift(ServerWorld world, long start, long end, boolean toNight, Vec3d at) {
            this.world = world;
            this.start = start;
            this.end = end;
            this.toNight = toNight;
            this.at = at;
        }
    }

    private static final Map<ServerWorld, Shift> SHIFTS = new HashMap<>();
    private static boolean registered = false;

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            Shift s = SHIFTS.get(world);
            if (s != null && !tick(s)) SHIFTS.remove(world);
        });
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        if (!(world instanceof ServerWorld sw)) return false;
        if (!sw.getDimension().hasSkyLight() || sw.getDimension().hasFixedTime()) {
            player.sendMessage(Text.literal("There is no sky here to turn.").formatted(Formatting.GRAY), true);
            return false;
        }
        if (SHIFTS.containsKey(sw)) {
            player.sendMessage(Text.literal("The heavens are already turning.").formatted(Formatting.GRAY), true);
            return false;
        }
        ensureRegistered();

        long start = sw.getTimeOfDay();
        long end = DayShift.target(start);
        boolean toNight = !DayShift.isDay(end);
        Vec3d at = player.getPos();
        SHIFTS.put(sw, new Shift(sw, start, end, toNight, at));
        DayShiftPayload.broadcast(sw, new DayShiftPayload(start, end, toNight, at.x, at.y, at.z));

        // Heard by everyone, wherever they are
        Text announcement = Text.literal(toNight
                        ? "☽ " + player.getName().getString() + " calls down the night"
                        : "☀ " + player.getName().getString() + " calls up the dawn")
                .formatted(toNight ? Formatting.BLUE : Formatting.GOLD);
        sw.getServer().getPlayerManager().broadcast(announcement, true);
        everyone(sw, SoundEvents.BLOCK_BEACON_ACTIVATE, 0.8f, toNight ? 0.5f : 0.7f);
        everyone(sw, SoundEvents.BLOCK_BELL_RESONATE, 1.0f, toNight ? 0.6f : 0.9f);
        sw.playSound(null, at.x, at.y, at.z, SoundEvents.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, SoundCategory.PLAYERS, 2.0f, toNight ? 0.5f : 0.8f);
        sw.spawnParticles(ParticleTypes.FLASH, at.x, at.y + 1, at.z, 1, 0, 0, 0, 0);
        return true;
    }

    /** Returns false once the sky has settled. */
    private static boolean tick(Shift s) {
        ServerWorld world = s.world;
        int age = ++s.age;
        double progress = age / (double) DayShift.DURATION;
        long time = Math.round(DayShift.timeAt(s.start, s.end, progress));
        world.setTimeOfDay(time);

        for (int toll : TOLLS) {
            if (age == toll + 1) {
                float pitch = s.toNight ? 0.5f + toll * 0.001f : 0.7f + toll * 0.002f;
                everyone(world, SoundEvents.BLOCK_BELL_USE, 1.0f, pitch);
            }
        }

        // The pillar: light spiralling up into the sky
        Vector3f color = s.toNight ? new Vector3f(0.45f, 0.55f, 1.0f) : new Vector3f(1.0f, 0.82f, 0.35f);
        DustParticleEffect dust = new DustParticleEffect(color, 1.4f);
        for (int i = 0; i < 3; i++) {
            double a = age * 0.4 + i * (Math.PI * 2 / 3);
            double h = (age * 0.8 + i * 3) % 24;
            world.spawnParticles(dust, s.at.x + Math.cos(a) * 1.2, s.at.y + h, s.at.z + Math.sin(a) * 1.2, 1, 0, 0, 0, 0);
        }
        world.spawnParticles(ParticleTypes.END_ROD, s.at.x, s.at.y + 1 + world.getRandom().nextDouble() * 20, s.at.z,
                2, 0.3, 0.5, 0.3, 0.02);
        if (s.toNight && age % 3 == 0) {
            world.spawnParticles(ParticleTypes.REVERSE_PORTAL, s.at.x, s.at.y + 1, s.at.z, 4, 2.5, 0.3, 2.5, 0.02);
        } else if (!s.toNight && age % 3 == 0) {
            world.spawnParticles(ParticleTypes.WAX_ON, s.at.x, s.at.y + 1, s.at.z, 4, 2.5, 0.5, 2.5, 0.05);
        }

        if (age >= DayShift.DURATION) {
            world.setTimeOfDay(s.end);
            // Make sure every client lands on exactly the same time
            world.getServer().getPlayerManager().sendToDimension(new WorldTimeUpdateS2CPacket(world.getTime(), world.getTimeOfDay(),
                    world.getGameRules().getBoolean(GameRules.DO_DAYLIGHT_CYCLE)), world.getRegistryKey());

            everyone(world, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0f, s.toNight ? 0.7f : 1.2f);
            everyone(world, SoundEvents.BLOCK_BELL_USE, 0.8f, s.toNight ? 1.0f : 1.5f);
            world.playSound(null, s.at.x, s.at.y, s.at.z, SoundEvents.BLOCK_BEACON_DEACTIVATE, SoundCategory.PLAYERS, 1.5f, s.toNight ? 0.6f : 1.0f);
            world.spawnParticles(ParticleTypes.END_ROD, s.at.x, s.at.y + 1, s.at.z, 60, 3.0, 0.5, 3.0, 0.15);
            world.spawnParticles(ParticleTypes.FLASH, s.at.x, s.at.y + 1, s.at.z, 1, 0, 0, 0, 0);
            return false;
        }
        return true;
    }

    /** Plays a sound to every player on the server, at their own ears. */
    private static void everyone(ServerWorld world, SoundEvent sound, float volume, float pitch) {
        for (ServerPlayerEntity p : world.getServer().getPlayerManager().getPlayerList()) {
            p.playSoundToPlayer(sound, SoundCategory.AMBIENT, volume, pitch);
        }
    }
}
