package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.*;

public class InsightSpell implements Spell {

    private static final double MAX_RADIUS = 30.0;     // scan to 30 blocks
    private static final double GROWTH_PER_TICK = 1.25; // how fast the sphere expands
    private static final int SHELL_POINTS = 90;        // particles on the shell each tick (balanced)
    private static final int GLOW_DURATION = 200;      // 10 seconds of Glowing

    private static final Map<RegistryKey<World>, List<Scan>> ACTIVE = new HashMap<>();
    private static boolean TICK_REGISTERED = false;

    private static void ensureTickerRegistered() {
        if (TICK_REGISTERED) return;
        ServerTickEvents.END_WORLD_TICK.register(InsightSpell::tickWorld);
        TICK_REGISTERED = true;
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        ensureTickerRegistered();

        // Start sound (nice, “magical sonar” vibe)
        world.playSound(null, player.getBlockPos(),
                SoundEvents.BLOCK_CONDUIT_ACTIVATE, SoundCategory.PLAYERS, 1.1f, 1.0f);

        // Start a scan from player eyes (feels centered on you)
        Vec3d origin = player.getCameraPosVec(0.0f);
        ServerWorld sw = (ServerWorld) world;
        ACTIVE.computeIfAbsent(sw.getRegistryKey(), k -> new ArrayList<>())
                .add(new Scan(player.getUuid(), origin, 0.0));

        return true;
    }

    private static void tickWorld(ServerWorld world) {
        List<Scan> scans = ACTIVE.get(world.getRegistryKey());
        if (scans == null || scans.isEmpty()) return;

        Iterator<Scan> it = scans.iterator();
        while (it.hasNext()) {
            Scan s = it.next();

            // advance radius
            s.radius += GROWTH_PER_TICK;

            // draw a thin spherical shell at current radius (through walls)
            spawnSphereShell(world, s.origin, s.radius, SHELL_POINTS);

            // done -> reveal entities and remove
            if (s.radius >= MAX_RADIUS) {
                PlayerEntity owner = world.getPlayerByUuid(s.owner);
                Vec3d c = s.origin;
                Box box = new Box(
                        c.x - MAX_RADIUS, c.y - MAX_RADIUS, c.z - MAX_RADIUS,
                        c.x + MAX_RADIUS, c.y + MAX_RADIUS, c.z + MAX_RADIUS);

                List<Entity> list = world.getOtherEntities(owner, box,
                        e -> e instanceof LivingEntity && e.isAlive());

                for (Entity e : list) {
                    if (e instanceof LivingEntity le) {
                        le.addStatusEffect(new StatusEffectInstance(
                                StatusEffects.GLOWING, GLOW_DURATION, 0, false, false, true));
                    }
                }

                // soft reveal sound
                world.playSound(null, owner != null ? owner.getBlockPos() : world.getSpawnPos(),
                        SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.9f, 1.2f);

                it.remove();
            }
        }
    }

    private static void spawnSphereShell(ServerWorld world, Vec3d center, double r, int n) {
        // Fibonacci sphere distribution for even coverage
        double phi = Math.PI * (3.0 - Math.sqrt(5.0));
        for (int i = 0; i < n; i++) {
            double y = 1.0 - (i + 0.5) * (2.0 / n);    // -1..1
            double radius = Math.sqrt(1.0 - y * y);
            double theta = phi * i;
            double x = Math.cos(theta) * radius;
            double z = Math.sin(theta) * radius;

            Vec3d p = center.add(x * r, y * r, z * r);

            // Use clean, non-dust particles: ENCHANT + END_ROD sprinkle
            if (world.random.nextInt(5) == 0) {
                world.spawnParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 1, 0, 0, 0, 0.0);
            } else {
                world.spawnParticles(ParticleTypes.ENCHANT, p.x, p.y, p.z, 1, 0, 0, 0, 0.0);
            }
        }

        // occasional subtle pulse ring for readability
        if ((world.getTime() & 3) == 0) {
            int ringPts = 32;
            double angleOff = (world.getTime() % 360) * 0.05;
            for (int i = 0; i < ringPts; i++) {
                double a = angleOff + (MathHelper.TAU * i / ringPts);
                double x = Math.cos(a) * r;
                double z = Math.sin(a) * r;
                Vec3d p = center.add(x, 0.0, z);
                world.spawnParticles(ParticleTypes.GLOW, p.x, p.y, p.z, 1, 0, 0, 0, 0.0);
            }
        }
    }

    private static class Scan {
        final UUID owner;
        final Vec3d origin;
        double radius;

        Scan(UUID owner, Vec3d origin, double radius) {
            this.owner = owner;
            this.origin = origin;
            this.radius = radius;
        }
    }
}
