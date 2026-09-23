package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Wraps the caster in an air bubble: Conduit Power (water breathing, full mining speed and
 * clear vision underwater), refills air instantly, and trails a shell of bubbles while submerged.
 */
public class BreathingSpell implements Spell {
    private static final int DURATION_TICKS = 20 * 180; // 3 minutes
    private static final int BUBBLE_EVERY = 4;
    private static final double BUBBLE_RADIUS = 1.3;

    // Player -> server tick the bubble visuals end
    private static final Map<UUID, Long> BUBBLED = new HashMap<>();
    private static boolean registered = false;

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (BUBBLED.isEmpty() || server.getTicks() % BUBBLE_EVERY != 0) return;
            BUBBLED.entrySet().removeIf(entry -> {
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
                if (player == null || server.getTicks() >= entry.getValue()
                        || !player.hasStatusEffect(StatusEffects.CONDUIT_POWER)) return true;
                if (player.isSubmergedInWater()) spawnShell(player.getServerWorld(), player, 10);
                return false;
            });
        });
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return true;
        ensureRegistered();

        player.addStatusEffect(new StatusEffectInstance(StatusEffects.CONDUIT_POWER, DURATION_TICKS, 0, false, true, true));
        player.setAir(player.getMaxAir());

        ServerWorld sw = (ServerWorld) world;
        BUBBLED.put(player.getUuid(), (long) sw.getServer().getTicks() + DURATION_TICKS);

        world.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_CONDUIT_ACTIVATE, SoundCategory.PLAYERS, 1.0f, 1.3f);
        world.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT, SoundCategory.PLAYERS, 1.0f, 1.0f);

        if (player.isSubmergedInWater()) {
            spawnShell(sw, player, 60);
        } else {
            // Bubbles only render in water, so pop splashes on land instead
            sw.spawnParticles(ParticleTypes.SPLASH, player.getX(), player.getY() + 1.0, player.getZ(), 40, 0.6, 0.6, 0.6, 0.1);
            sw.spawnParticles(ParticleTypes.BUBBLE_POP, player.getX(), player.getY() + 1.0, player.getZ(), 25, 0.6, 0.6, 0.6, 0.02);
        }
        return true;
    }

    /** A loose sphere of bubbles around the player's upper body. */
    private static void spawnShell(ServerWorld world, PlayerEntity player, int count) {
        double cx = player.getX();
        double cy = player.getY() + player.getHeight() * 0.6;
        double cz = player.getZ();
        for (int i = 0; i < count; i++) {
            double u = world.random.nextDouble() * 2.0 - 1.0;
            double theta = world.random.nextDouble() * Math.PI * 2.0;
            double r = Math.sqrt(1.0 - u * u) * BUBBLE_RADIUS;
            world.spawnParticles(ParticleTypes.BUBBLE,
                    cx + r * Math.cos(theta), cy + u * BUBBLE_RADIUS, cz + r * Math.sin(theta),
                    1, 0.0, 0.0, 0.0, 0.0);
        }
    }
}
