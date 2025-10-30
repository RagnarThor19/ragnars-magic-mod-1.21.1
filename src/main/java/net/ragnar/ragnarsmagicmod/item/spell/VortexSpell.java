package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.*;

public class VortexSpell implements Spell {

    private static final double RANGE = 48.0;
    private static final double RADIUS = 7.0;
    private static final int DURATION_TICKS = 60; // 4 seconds
    private static final float DAMAGE_PER_TICK = 0.5f;
    private static final double PULL_STRENGTH = 0.60; // stronger pull

    private static final Map<RegistryKey<World>, List<Vortex>> ACTIVE = new HashMap<>();
    private static boolean TICK_REGISTERED = false;

    private static void ensureTicker() {
        if (TICK_REGISTERED) return;
        ServerTickEvents.END_WORLD_TICK.register(VortexSpell::tickWorld);
        TICK_REGISTERED = true;
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        ensureTicker();

        Vec3d pos = getTargetPosition(world, player);

        // Sounds and activation
        world.playSound(null, BlockPos.ofFloored(pos),
                SoundEvents.ENTITY_ENDER_DRAGON_FLAP, SoundCategory.PLAYERS, 1.2f, 0.7f);
        world.playSound(null, BlockPos.ofFloored(pos),
                SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 0.9f, 1.6f);

        ServerWorld sw = (ServerWorld) world;
        ACTIVE.computeIfAbsent(sw.getRegistryKey(), k -> new ArrayList<>())
                .add(new Vortex(pos, sw.getTime()));

        return true;
    }

    private static Vec3d getTargetPosition(World world, PlayerEntity player) {
        HitResult hit = player.raycast(RANGE, 0.0f, false);
        if (hit.getType() == HitResult.Type.BLOCK) {
            return ((BlockHitResult) hit).getPos();
        } else {
            return player.getCameraPosVec(0).add(player.getRotationVector().normalize().multiply(6.0));
        }
    }

    private static void tickWorld(ServerWorld world) {
        List<Vortex> list = ACTIVE.get(world.getRegistryKey());
        if (list == null || list.isEmpty()) return;

        long now = world.getTime();
        Iterator<Vortex> it = list.iterator();
        while (it.hasNext()) {
            Vortex v = it.next();
            int age = (int) (now - v.spawnTick);
            if (age > DURATION_TICKS) {
                world.playSound(null, BlockPos.ofFloored(v.pos),
                        SoundEvents.BLOCK_BEACON_DEACTIVATE, SoundCategory.PLAYERS, 0.7f, 1.0f);
                it.remove();
                continue;
            }

            // Visuals: inward-moving spiral
            spawnParticles(world, v.pos, age);

            // Pull + damage
            Box box = new Box(v.pos.x - RADIUS, v.pos.y - RADIUS, v.pos.z - RADIUS,
                    v.pos.x + RADIUS, v.pos.y + RADIUS, v.pos.z + RADIUS);

            List<Entity> targets = world.getOtherEntities(null, box,
                    e -> e instanceof LivingEntity le && le.isAlive());
            for (Entity e : targets) {
                LivingEntity le = (LivingEntity) e;

                Vec3d toCenter = v.pos.subtract(le.getPos());
                double dist = toCenter.length();
                if (dist < 0.3) continue;

                // Stronger inward pull
                Vec3d pull = toCenter.normalize().multiply(PULL_STRENGTH * (1.0 - dist / RADIUS));
                // Add slight lift so mobs “float” a bit
                le.addVelocity(pull.x, 0.05 + pull.y * 0.3, pull.z);
                le.damage(world.getDamageSources().magic(), DAMAGE_PER_TICK);

                // Small sound every few ticks
                if (world.random.nextInt(35) == 0) {
                    world.playSound(null, le.getBlockPos(),
                            SoundEvents.ENTITY_PHANTOM_FLAP, SoundCategory.PLAYERS, 0.6f, 1.8f);
                }
            }

            // Low ambient hum
            if (age % 12 == 0) {
                world.playSound(null, BlockPos.ofFloored(v.pos),
                        SoundEvents.BLOCK_BEACON_AMBIENT, SoundCategory.PLAYERS, 0.4f, 1.5f);
            }
        }
    }

    private static void spawnParticles(ServerWorld world, Vec3d center, int age) {
        net.minecraft.util.math.random.Random rand = world.getRandom();

        // inward-moving spirals
        int points = 150;
        double swirl = age * 0.25;
        for (int i = 0; i < points; i++) {
            double angle = (i / (double) points) * MathHelper.TAU + swirl;
            double dist = RADIUS * (0.6 + 0.4 * rand.nextDouble());
            double x = center.x + Math.cos(angle) * dist;
            double y = center.y + (rand.nextDouble() - 0.5) * 1.2;
            double z = center.z + Math.sin(angle) * dist;

            // Direction vector toward center
            Vec3d vel = center.subtract(x, y, z).normalize().multiply(0.25 + rand.nextDouble() * 0.1);

            world.spawnParticles(ParticleTypes.PORTAL, x, y, z, 0, vel.x, vel.y, vel.z, 2.0);
            if (i % 10 == 0) {
                world.spawnParticles(ParticleTypes.REVERSE_PORTAL, x, y, z, 0, vel.x, vel.y, vel.z, 1.0);
            }
        }

        // Dense mist at center
        for (int i = 0; i < 30; i++) {
            double dx = (rand.nextDouble() - 0.5) * 1.5;
            double dy = (rand.nextDouble() - 0.5) * 1.5;
            double dz = (rand.nextDouble() - 0.5) * 1.5;
            world.spawnParticles(ParticleTypes.ENCHANT,
                    center.x + dx, center.y + dy, center.z + dz, 1, 0, 0, 0, 0);
        }
    }

    private static class Vortex {
        final Vec3d pos;
        final long spawnTick;
        Vortex(Vec3d pos, long spawnTick) {
            this.pos = pos;
            this.spawnTick = spawnTick;
        }
    }
}
