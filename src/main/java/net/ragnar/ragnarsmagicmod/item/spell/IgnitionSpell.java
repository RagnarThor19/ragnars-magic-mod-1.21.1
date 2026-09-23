package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A ring of fire bursts out from the caster and sweeps outward to {@link #RADIUS} blocks,
 * setting every living thing it passes over alight. The caster is untouched and no blocks burn.
 */
public class IgnitionSpell implements Spell {
    private static final double RADIUS = 10.0;
    private static final int EXPAND_TICKS = 10;
    private static final int BURN_SECONDS = 8;
    private static final float SCORCH_DAMAGE = 2.0f;

    private static final class Nova {
        final ServerWorld world;
        final Vec3d center;
        final UUID casterId;
        final Set<UUID> ignited = new HashSet<>();
        int age = 0;

        Nova(ServerWorld world, Vec3d center, UUID casterId) {
            this.world = world;
            this.center = center;
            this.casterId = casterId;
        }
    }

    private static final List<Nova> ACTIVE = new ArrayList<>();
    private static boolean registered = false;

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            Iterator<Nova> it = ACTIVE.iterator();
            while (it.hasNext()) {
                Nova n = it.next();
                if (n.world == world && !tick(n)) it.remove();
            }
        });
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return true;
        ensureRegistered();
        ServerWorld sw = (ServerWorld) world;
        Vec3d c = player.getPos();
        ACTIVE.add(new Nova(sw, c, player.getUuid()));

        // Eruption at the caster's feet
        sw.spawnParticles(ParticleTypes.FLASH, c.x, c.y + 1.0, c.z, 1, 0, 0, 0, 0);
        for (int i = 0; i < 40; i++) {
            double a = sw.random.nextDouble() * Math.PI * 2.0;
            double up = 0.2 + sw.random.nextDouble() * 0.5;
            sw.spawnParticles(ParticleTypes.FLAME, c.x, c.y + 0.2, c.z, 0, Math.cos(a) * 0.15, up, Math.sin(a) * 0.15, 1.0);
        }
        sw.spawnParticles(ParticleTypes.LAVA, c.x, c.y + 0.5, c.z, 12, 0.4, 0.2, 0.4, 0.0);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ITEM_FIRECHARGE_USE, SoundCategory.PLAYERS, 1.5f, 0.6f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_BLAZE_SHOOT, SoundCategory.PLAYERS, 1.5f, 0.5f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_WIND_CHARGE_WIND_BURST.value(), SoundCategory.PLAYERS, 1.0f, 0.5f);
        return true;
    }

    /** Returns false once the ring has reached its full size. */
    private static boolean tick(Nova n) {
        n.age++;
        ServerWorld world = n.world;
        double t = n.age / (double) EXPAND_TICKS;
        double r = RADIUS * (1.0 - (1.0 - t) * (1.0 - t)); // fast out, easing to the edge
        Vec3d c = n.center;

        // The wall of fire: flames licking up along the ring, with embers and smoke behind it
        int points = (int) Math.max(16, r * 2 * Math.PI * 2.2);
        for (int i = 0; i < points; i++) {
            double a = i * Math.PI * 2.0 / points + world.random.nextDouble() * 0.05;
            double x = c.x + Math.cos(a) * r, z = c.z + Math.sin(a) * r;
            world.spawnParticles(ParticleTypes.FLAME, x, c.y + 0.1, z, 0, Math.cos(a) * 0.05, 0.12 + world.random.nextDouble() * 0.12, Math.sin(a) * 0.05, 1.0);
            if (i % 4 == 0) world.spawnParticles(ParticleTypes.SMALL_FLAME, x, c.y + 0.6, z, 1, 0.1, 0.2, 0.1, 0.01);
            if (i % 6 == 0) world.spawnParticles(ParticleTypes.LARGE_SMOKE, x, c.y + 0.3, z, 0, 0, 0.05, 0, 1.0);
        }
        if (n.age % 2 == 0) {
            world.playSound(null, c.x + (world.random.nextDouble() - 0.5) * r, c.y, c.z + (world.random.nextDouble() - 0.5) * r,
                    SoundEvents.BLOCK_FIRE_AMBIENT, SoundCategory.PLAYERS, 1.5f, 0.6f + world.random.nextFloat() * 0.4f);
        }

        // Anything the ring has reached catches fire
        Box area = new Box(c, c).expand(r, 4.0, r);
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, area, e -> e.isAlive() && !e.getUuid().equals(n.casterId))) {
            if (e.squaredDistanceTo(c) > r * r || !n.ignited.add(e.getUuid())) continue;
            e.setOnFireFor(BURN_SECONDS);
            e.damage(world.getDamageSources().inFire(), SCORCH_DAMAGE);
            Vec3d body = e.getBoundingBox().getCenter();
            world.spawnParticles(ParticleTypes.FLAME, body.x, body.y, body.z, 15, e.getWidth() * 0.4, e.getHeight() * 0.4, e.getWidth() * 0.4, 0.05);
        }

        return n.age < EXPAND_TICKS;
    }
}
