package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.*;

public class SunSpell implements Spell {

    // --- behavior tuning ---
    private static final double START_RADIUS = 0.2;     // starts at 1 block radius
    private static final double END_RADIUS   = 5.0;     // grows to 4, then disappears
    private static final double GROWTH_PER_TICK = 0.02; // ~60 ticks from 1 -> 4
    private static final double SPEED = 0.18;           // slow, unstoppable drift

    // fire placement (optional, never breaks blocks)
    private static final boolean CAN_IGNITE = true;     // set to false to disable all fire
    private static final double FIRE_CHANCE = 0.25;     // chance per sampled point (kept low)
    private static final int FIRE_SAMPLES = 18;         // how many surface points to try per tick

    // visuals (no trail, no dragon breath)
    private static final int CORE_PARTICLES_PER_TICK = 5; // dense core “fusion” look
    private static final int SHELL_POINTS_BASE = 300;      // roiling surface density

    private static final Map<RegistryKey<World>, List<SunBall>> ACTIVE = new HashMap<>();
    private static boolean TICK_REGISTERED = false;

    private static void ensureTicker() {
        if (TICK_REGISTERED) return;
        ServerTickEvents.END_WORLD_TICK.register(SunSpell::tickWorld);
        TICK_REGISTERED = true;
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        ensureTicker();

        // summon sounds
        world.playSound(null, player.getBlockPos(),
                SoundEvents.ENTITY_BLAZE_SHOOT, SoundCategory.PLAYERS, 1.2f, 0.9f);
        world.playSound(null, player.getBlockPos(),
                SoundEvents.ENTITY_WITHER_SPAWN, SoundCategory.PLAYERS, 0.6f, 1.6f);

        Vec3d eye = player.getCameraPosVec(0.0f);
        Vec3d forward = player.getRotationVector().normalize();
        Vec3d start = eye.add(forward.multiply(3.0));

        ServerWorld sw = (ServerWorld) world;
        ACTIVE.computeIfAbsent(sw.getRegistryKey(), k -> new ArrayList<>())
                .add(new SunBall(player.getUuid(), start, forward, START_RADIUS, sw.getTime()));
        return true;
    }

    private static void tickWorld(ServerWorld world) {
        List<SunBall> list = ACTIVE.get(world.getRegistryKey());
        if (list == null || list.isEmpty()) return;

        long now = world.getTime();
        Iterator<SunBall> it = list.iterator();
        while (it.hasNext()) {
            SunBall s = it.next();

            // end of life
            if (s.radius >= END_RADIUS) {
                // final flare (no trail: just at current position)
                for (int i = 0; i < 60; i++) {
                    double vx = (world.random.nextDouble() - 0.5) * 0.5;
                    double vy = (world.random.nextDouble() - 0.2) * 0.5;
                    double vz = (world.random.nextDouble() - 0.5) * 0.5;
                    world.spawnParticles(ParticleTypes.LAVA, s.pos.x, s.pos.y, s.pos.z, 1, vx, vy, vz, 0.0);
                }
                world.playSound(null, BlockPos.ofFloored(s.pos),
                        SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.PLAYERS, 1.1f, 1.8f);
                it.remove();
                continue;
            }

            // move forward (no collision: passes through walls)
            s.pos = s.pos.add(s.forward.multiply(SPEED));

            // grow
            s.radius += GROWTH_PER_TICK;

            // optional: ignite *air only* where the shell touches (never replace/break blocks)
            if (CAN_IGNITE) tryIgniteAirOnShell(world, s.pos, s.radius);

            // kill entities inside sphere
            Box box = new Box(
                    s.pos.x - s.radius, s.pos.y - s.radius, s.pos.z - s.radius,
                    s.pos.x + s.radius, s.pos.y + s.radius, s.pos.z + s.radius
            );
            List<Entity> hits = world.getOtherEntities(null, box, e -> e instanceof LivingEntity && e.isAlive());
            for (Entity e : hits) {
                double d = e.getPos().distanceTo(s.pos);
                if (d <= s.radius + 0.1) {
                    LivingEntity le = (LivingEntity) e;
                    le.setOnFireFor(6);
                    le.damage(world.getDamageSources().magic(), 1_000_000.0f); // “instant” kill
                }
            }

            // particles at *current* position only (no trail)
            spawnCoreParticles(world, s.pos, s.radius);
            spawnShellParticles(world, s.pos, s.radius, now);

            // occasional simmer sound
            if (world.random.nextInt(12) == 0) {
                world.playSound(
                        null, BlockPos.ofFloored(s.pos),
                        SoundEvents.BLOCK_LAVA_POP, SoundCategory.PLAYERS, 0.6f, 0.8f + world.random.nextFloat() * 0.4f
                );
            }
        }
    }

    // dense bright core: LAVA + SMALL_FLAME inside sphere (no trail)
    private static void spawnCoreParticles(ServerWorld world, Vec3d c, double r) {
        for (int i = 0; i < CORE_PARTICLES_PER_TICK; i++) {
            double rx = (world.random.nextDouble() * 2 - 1) * r * 0.6;
            double ry = (world.random.nextDouble() * 2 - 1) * r * 0.6;
            double rz = (world.random.nextDouble() * 2 - 1) * r * 0.6;
            world.spawnParticles(ParticleTypes.LAVA, c.x + rx, c.y + ry, c.z + rz, 1, 0, 0, 0, 0.0);
            if (world.random.nextBoolean()) {
                world.spawnParticles(ParticleTypes.SMALL_FLAME, c.x + rx, c.y + ry, c.z + rz, 1, 0, 0, 0, 0.0);
            }
        }
    }

    // roiling surface: FLAME + occasional ASH shimmer (no reverse_portal, no dragon_breath)
    private static void spawnShellParticles(ServerWorld world, Vec3d c, double r, long now) {
        int points = (int) (SHELL_POINTS_BASE * (0.6 + 0.4 * (r / END_RADIUS))); // scale with size
        double phi = Math.PI * (3.0 - Math.sqrt(5.0));
        for (int i = 0; i < points; i++) {
            double y = 1.0 - (i + 0.5) * (2.0 / points);
            double radius = Math.sqrt(1.0 - y * y);
            double theta = phi * i + (now * 0.06); // slow spin
            double x = Math.cos(theta) * radius;
            double z = Math.sin(theta) * radius;

            Vec3d p = c.add(x * r, y * r, z * r);

            if ((i + now) % 7 == 0) {
                world.spawnParticles(ParticleTypes.ASH, p.x, p.y, p.z, 1, 0, 0, 0, 0.0);
            } else {
                world.spawnParticles(ParticleTypes.FLAME, p.x, p.y, p.z, 1, 0, 0, 0, 0.0);
            }
        }
    }

    // tries to place fire in air cells on the shell; never replaces solid blocks
    private static void tryIgniteAirOnShell(ServerWorld world, Vec3d c, double r) {
        if (FIRE_SAMPLES <= 0 || FIRE_CHANCE <= 0) return;

        int samples = FIRE_SAMPLES;
        double phi = Math.PI * (3.0 - Math.sqrt(5.0));
        for (int i = 0; i < samples; i++) {
            if (world.random.nextDouble() > FIRE_CHANCE) continue;

            double y = 1.0 - (i + 0.5) * (2.0 / samples);
            double radius = Math.sqrt(1.0 - y * y);
            double theta = phi * i;
            double x = Math.cos(theta) * radius;
            double z = Math.sin(theta) * radius;

            BlockPos bp = BlockPos.ofFloored(c.add(x * r, y * r, z * r));

            // only in AIR — never replace any existing block
            if (!world.isAir(bp)) continue;

            BlockState fire = AbstractFireBlock.getState(world, bp);
            if (fire != null) {
                world.setBlockState(bp, fire, 3);
            } else {
                // optional: if air above a solid block, light the air cell (as normal fire)
                BlockPos below = bp.down();
                if (world.getBlockState(below).isSolidBlock(world, below)) {
                    BlockState fb = AbstractFireBlock.getState(world, bp);
                    if (fb != null) world.setBlockState(bp, fb, 3);
                }
            }
        }
    }

    private static class SunBall {
        final UUID owner;
        Vec3d pos;
        final Vec3d forward;
        double radius;
        final long spawnTick;

        SunBall(UUID owner, Vec3d pos, Vec3d forward, double radius, long spawnTick) {
            this.owner = owner;
            this.pos = pos;
            this.forward = forward;
            this.radius = radius;
            this.spawnTick = spawnTick;
        }
    }
}
