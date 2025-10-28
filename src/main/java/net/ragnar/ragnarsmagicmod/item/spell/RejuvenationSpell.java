package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.*;

public class RejuvenationSpell implements Spell {

    private static final int REGEN_DURATION = 100; // 5 seconds
    private static final int HEART_TICKS = 100;    // 5 seconds of heart particles
    private static final double EMERALD_RADIUS = 1.3;

    private static final Map<RegistryKey<World>, List<ActiveRegen>> ACTIVE = new HashMap<>();
    private static boolean TICK_REGISTERED = false;

    private static void ensureTick() {
        if (TICK_REGISTERED) return;
        ServerTickEvents.END_WORLD_TICK.register(RejuvenationSpell::tickWorld);
        TICK_REGISTERED = true;
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        ensureTick();

        // Sound + emerald burst
        world.playSound(null, player.getBlockPos(),
                SoundEvents.BLOCK_AMETHYST_CLUSTER_BREAK, SoundCategory.PLAYERS,
                1.2f, 1.2f);

        ServerWorld sw = (ServerWorld) world;

        // emerald sparkle particles
        Vec3d center = player.getPos().add(0, 1.0, 0);
        for (int i = 0; i < 40; i++) {
            double angle = (Math.PI * 2 * i) / 40;
            double x = center.x + Math.cos(angle) * EMERALD_RADIUS;
            double y = center.y + (sw.getRandom().nextDouble() - 0.5) * 0.6;
            double z = center.z + Math.sin(angle) * EMERALD_RADIUS;
            sw.spawnParticles(ParticleTypes.HAPPY_VILLAGER, x, y, z, 1, 0, 0, 0, 0);
        }

        // instant heal (4 hearts)
        player.heal(8.0f);

        // give regen + absorption
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, REGEN_DURATION, 1, false, false, true));
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, REGEN_DURATION, 1, false, false, true));

        // Track heart particles for 5 seconds
        ACTIVE.computeIfAbsent(sw.getRegistryKey(), k -> new ArrayList<>())
                .add(new ActiveRegen(player.getUuid(), sw.getTime()));

        return true;
    }

    private static void tickWorld(ServerWorld world) {
        List<ActiveRegen> list = ACTIVE.get(world.getRegistryKey());
        if (list == null || list.isEmpty()) return;

        long now = world.getTime();
        Iterator<ActiveRegen> it = list.iterator();
        while (it.hasNext()) {
            ActiveRegen r = it.next();
            PlayerEntity p = world.getPlayerByUuid(r.player);
            if (p == null) { it.remove(); continue; }

            if (now - r.startTick > HEART_TICKS) {
                it.remove();
                continue;
            }

            // small floating hearts
            if (world.getRandom().nextInt(5) == 0) { // sparse
                double x = p.getX() + (world.getRandom().nextDouble() - 0.5) * 0.6;
                double y = p.getY() + 1.0 + world.getRandom().nextDouble() * 0.8;
                double z = p.getZ() + (world.getRandom().nextDouble() - 0.5) * 0.6;
                world.spawnParticles(ParticleTypes.HEART, x, y, z, 1, 0, 0, 0, 0);
            }
        }
    }

    private static class ActiveRegen {
        final UUID player;
        final long startTick;
        ActiveRegen(UUID player, long startTick) {
            this.player = player;
            this.startTick = startTick;
        }
    }
}
