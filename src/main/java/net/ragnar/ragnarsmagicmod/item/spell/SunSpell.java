package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LightBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Conjures a small sun in front of the caster.
 *  1. Ignition: flame is drawn in from all around and the sun swells in front of you, following your aim.
 *  2. Release: it drifts forward, slowly growing, burning away every living thing it touches and
 *     lighting the world around it.
 *  3. Collapse: at full size it goes out in a burst of light and fire.
 */
public class SunSpell implements Spell {
    // --- Timing & size ---
    private static final int IGNITE_TICKS = 30;
    private static final double START_RADIUS = 0.2;
    private static final double IGNITED_RADIUS = 1.6;
    private static final double END_RADIUS = 5.0;
    private static final double GROWTH_PER_TICK = 0.02;
    private static final double SPEED = 0.18;
    private static final double HOLD_DISTANCE = 3.0;    // gap between your eyes and the sun's surface while igniting

    // --- World effects ---
    private static final boolean CAN_IGNITE = true;
    private static final double FIRE_CHANCE = 0.25;
    private static final int FIRE_SAMPLES = 18;
    private static final int LIGHT_LEVEL = 15;
    private static final double VIEW_RANGE = 160.0;     // a sun should be visible from far away

    // --- Look ---
    private static final DustParticleEffect SUN_BODY = new DustParticleEffect(new Vector3f(1.0f, 0.62f, 0.12f), 1.6f);
    private static final DustParticleEffect SUN_HOT = new DustParticleEffect(new Vector3f(1.0f, 0.93f, 0.55f), 2.0f);

    private static final List<Sun> ACTIVE = new ArrayList<>();
    private static boolean registered = false;

    private static final class Sun {
        final ServerWorld world;
        final UUID owner;
        Vec3d pos;
        Vec3d forward;
        double radius = START_RADIUS;
        int age = 0;
        BlockPos light;

        Sun(ServerWorld world, UUID owner, Vec3d pos, Vec3d forward) {
            this.world = world;
            this.owner = owner;
            this.pos = pos;
            this.forward = forward;
        }

        boolean igniting() {
            return age <= IGNITE_TICKS;
        }
    }

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            Iterator<Sun> it = ACTIVE.iterator();
            while (it.hasNext()) {
                Sun sun = it.next();
                if (sun.world == world && !tick(sun)) it.remove();
            }
        });
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        ensureRegistered();

        Vec3d forward = player.getRotationVector().normalize();
        Vec3d start = player.getEyePos().add(forward.multiply(HOLD_DISTANCE));
        ACTIVE.add(new Sun((ServerWorld) world, player.getUuid(), start, forward));

        world.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 1.5f, 0.5f);
        world.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.PLAYERS, 1.2f, 0.6f);
        world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_WARDEN_SONIC_CHARGE, SoundCategory.PLAYERS, 0.7f, 1.5f);
        return true;
    }

    /** Advances one sun; returns false once it has collapsed. */
    private static boolean tick(Sun sun) {
        ServerWorld world = sun.world;
        PlayerEntity owner = world.getPlayerByUuid(sun.owner);
        sun.age++;

        if (sun.radius >= END_RADIUS) {
            collapse(sun);
            return false;
        }

        Vec3d oldPos = sun.pos;
        double prevRadius = sun.radius;

        if (sun.igniting()) {
            // Swell in front of the caster and follow their aim until released
            float t = sun.age / (float) IGNITE_TICKS;
            sun.radius = MathHelper.lerp(1.0 - Math.pow(1.0 - t, 3), START_RADIUS, IGNITED_RADIUS);
            if (owner != null) {
                sun.forward = owner.getRotationVector().normalize();
                sun.pos = owner.getEyePos().add(sun.forward.multiply(HOLD_DISTANCE + sun.radius));
            }
            renderIgnition(sun);
            if (sun.age == IGNITE_TICKS) release(sun);
        } else {
            sun.pos = sun.pos.add(sun.forward.multiply(SPEED));
            sun.radius += GROWTH_PER_TICK;
            if (CAN_IGNITE) tryIgniteAirOnShell(world, sun.pos, sun.radius);
        }

        burnEntities(world, owner, oldPos, sun.pos, prevRadius, sun.radius);
        moveLight(sun);
        renderSun(sun);
        playAmbience(sun);
        return true;
    }

    private static void release(Sun sun) {
        ServerWorld world = sun.world;
        world.playSound(null, sun.pos.x, sun.pos.y, sun.pos.z, SoundEvents.ENTITY_BLAZE_SHOOT, SoundCategory.PLAYERS, 2.0f, 0.5f);
        world.playSound(null, sun.pos.x, sun.pos.y, sun.pos.z, SoundEvents.ITEM_FIRECHARGE_USE, SoundCategory.PLAYERS, 2.0f, 0.6f);
        world.playSound(null, sun.pos.x, sun.pos.y, sun.pos.z, SoundEvents.BLOCK_BEACON_POWER_SELECT, SoundCategory.PLAYERS, 1.5f, 0.5f);
        // A ring of fire blown outward as it's released
        Random rand = world.getRandom();
        Vec3d[] basis = perpendicularBasis(sun.forward);
        for (int i = 0; i < 48; i++) {
            double a = i * Math.PI * 2.0 / 48;
            Vec3d dir = basis[0].multiply(Math.cos(a)).add(basis[1].multiply(Math.sin(a)));
            Vec3d p = sun.pos.add(dir.multiply(sun.radius));
            Vec3d v = dir.multiply(0.35 + rand.nextDouble() * 0.1);
            emit(world, ParticleTypes.FLAME, p, 0, v.x, v.y, v.z, 1.0);
        }
    }

    private static void collapse(Sun sun) {
        ServerWorld world = sun.world;
        Random rand = world.getRandom();
        Vec3d c = sun.pos;
        clearLight(sun);

        for (int i = 0; i < 3; i++) emit(world, ParticleTypes.FLASH, c, 1, 0.5, 0.5, 0.5, 0.0);
        emit(world, ParticleTypes.EXPLOSION_EMITTER, c, 1, 0, 0, 0, 0.0);
        for (int i = 0; i < 160; i++) {
            Vec3d v = randomUnit(rand).multiply(0.35 + rand.nextDouble() * 0.5);
            emit(world, i % 3 == 0 ? ParticleTypes.SMALL_FLAME : ParticleTypes.FLAME, c.add(v.multiply(sun.radius * 0.8)), 0, v.x, v.y, v.z, 1.0);
        }
        for (int i = 0; i < 40; i++) {
            Vec3d p = c.add(randomUnit(rand).multiply(sun.radius * rand.nextDouble()));
            emit(world, ParticleTypes.LAVA, p, 1, 0, 0, 0, 0.0);
        }
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 2.5f, 0.6f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_BEACON_DEACTIVATE, SoundCategory.PLAYERS, 2.0f, 0.5f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.PLAYERS, 1.5f, 0.5f);
    }

    // ---------------------------------------------------------------------
    // Damage
    // ---------------------------------------------------------------------

    /** Anything the sun's sphere sweeps through this tick is burned away. */
    private static void burnEntities(ServerWorld world, PlayerEntity owner, Vec3d oldPos, Vec3d newPos, double prevRadius, double radius) {
        double maxR = Math.max(prevRadius, radius);
        Box sweep = new Box(oldPos, newPos).expand(maxR);
        for (Entity e : world.getOtherEntities(owner, sweep, e -> e instanceof LivingEntity && e.isAlive())) {
            Vec3d c = e.getBoundingBox().getCenter();
            double entityRadius = Math.max(e.getWidth(), e.getHeight()) * 0.5;
            double t = clamp01(projectParamOnSegment(oldPos, newPos, c));
            double limit = prevRadius + (radius - prevRadius) * t + entityRadius;
            if (distanceSqPointToSegment(c, oldPos, newPos) > limit * limit) continue;

            LivingEntity le = (LivingEntity) e;
            le.setOnFireFor(6);
            if (owner != null) le.damage(world.getDamageSources().playerAttack(owner), 1_000_000.0f);
            else le.damage(world.getDamageSources().magic(), 1_000_000.0f);
        }
    }

    // ---------------------------------------------------------------------
    // Visuals
    // ---------------------------------------------------------------------

    /** Flame drawn inward from all around while the sun ignites. */
    private static void renderIgnition(Sun sun) {
        ServerWorld world = sun.world;
        Random rand = world.getRandom();
        for (int i = 0; i < 24; i++) {
            Vec3d dir = randomUnit(rand);
            double dist = sun.radius + 2.5 + rand.nextDouble() * 1.5;
            Vec3d p = sun.pos.add(dir.multiply(dist));
            Vec3d v = dir.multiply(-(dist - sun.radius) / 10.0);
            emit(world, i % 4 == 0 ? ParticleTypes.SMALL_FLAME : ParticleTypes.FLAME, p, 0, v.x, v.y, v.z, 1.0);
        }
        if (sun.age % 3 == 0) {
            Vec3d dir = randomUnit(rand);
            Vec3d p = sun.pos.add(dir.multiply(sun.radius + 3.0));
            Vec3d v = dir.multiply(-0.3);
            emit(world, ParticleTypes.END_ROD, p, 0, v.x, v.y, v.z, 1.0);
        }
    }

    private static void renderSun(Sun sun) {
        ServerWorld world = sun.world;
        Random rand = world.getRandom();
        Vec3d c = sun.pos;
        double r = sun.radius;
        int age = sun.age;

        // White-hot glow at the heart
        if (age % 2 == 0) emit(world, ParticleTypes.FLASH, c, 1, 0, 0, 0, 0.0);

        // Photosphere: a slowly spinning, gently boiling sphere of flame
        int points = (int) (70 + 46 * r);
        double golden = Math.PI * (3.0 - Math.sqrt(5.0));
        double spin = age * 0.05;
        for (int i = 0; i < points; i++) {
            double y = 1.0 - (i + 0.5) * (2.0 / points);
            double ring = Math.sqrt(1.0 - y * y);
            double theta = golden * i + spin;
            double boil = 1.0 + 0.05 * Math.sin(i * 1.7 + age * 0.45);
            Vec3d p = c.add(Math.cos(theta) * ring * r * boil, y * r * boil, Math.sin(theta) * ring * r * boil);
            ParticleEffect type = i % 5 == 0 ? SUN_BODY : (i % 3 == 0 ? ParticleTypes.SMALL_FLAME : ParticleTypes.FLAME);
            emit(world, type, p, 1, 0, 0, 0, 0.0);
        }

        // Hot, bright interior
        int inner = (int) (6 + 4 * r);
        for (int i = 0; i < inner; i++) {
            Vec3d p = c.add(randomUnit(rand).multiply(r * 0.75 * rand.nextDouble()));
            emit(world, SUN_HOT, p, 1, 0, 0, 0, 0.0);
        }
        if (age % 3 == 0) emit(world, ParticleTypes.LAVA, c.add(randomUnit(rand).multiply(r * 0.6)), 1, 0, 0, 0, 0.0);

        // Solar flares: tongues of fire licking off the surface
        int flares = r > 1.0 ? 2 : 1;
        for (int f = 0; f < flares; f++) {
            Vec3d dir = randomUnit(rand);
            Vec3d base = c.add(dir.multiply(r));
            double speed = 0.08 + rand.nextDouble() * 0.12 * (0.5 + r / END_RADIUS);
            for (int i = 0; i < 4; i++) {
                Vec3d v = dir.multiply(speed * (1.0 + i * 0.35));
                emit(world, ParticleTypes.FLAME, base, 0, v.x, v.y, v.z, 1.0);
            }
        }

        // Faint corona drifting off the surface
        int corona = (int) (8 + 5 * r);
        for (int i = 0; i < corona; i++) {
            Vec3d dir = randomUnit(rand);
            Vec3d p = c.add(dir.multiply(r * (1.08 + rand.nextDouble() * 0.2)));
            Vec3d v = dir.multiply(0.03);
            emit(world, ParticleTypes.SMALL_FLAME, p, 0, v.x, v.y, v.z, 1.0);
        }
    }

    private static void playAmbience(Sun sun) {
        ServerWorld world = sun.world;
        Vec3d c = sun.pos;
        float volume = (float) (1.0 + sun.radius * 0.4);
        if (sun.age % 10 == 0) {
            world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_FIRE_AMBIENT, SoundCategory.PLAYERS, volume, 0.5f);
        }
        if (sun.age % 20 == 5) {
            world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_BLAZE_BURN, SoundCategory.PLAYERS, volume, 0.4f);
        }
        if (world.random.nextInt(10) == 0) {
            world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_LAVA_POP, SoundCategory.PLAYERS, 0.8f, 0.6f + world.random.nextFloat() * 0.4f);
        }
    }

    /** Sends a particle to every player within {@link #VIEW_RANGE}, past the normal 32 block cutoff. */
    private static void emit(ServerWorld world, ParticleEffect type, Vec3d p, int count, double dx, double dy, double dz, double speed) {
        for (ServerPlayerEntity viewer : world.getPlayers()) {
            if (viewer.squaredDistanceTo(p) <= VIEW_RANGE * VIEW_RANGE) {
                world.spawnParticles(viewer, type, true, p.x, p.y, p.z, count, dx, dy, dz, speed);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Light & fire
    // ---------------------------------------------------------------------

    /** Carries a real light source with the sun (only through open air). */
    private static void moveLight(Sun sun) {
        BlockPos pos = BlockPos.ofFloored(sun.pos);
        if (pos.equals(sun.light)) return;
        clearLight(sun);
        if (sun.world.getBlockState(pos).isAir()) {
            sun.world.setBlockState(pos, Blocks.LIGHT.getDefaultState().with(LightBlock.LEVEL_15, LIGHT_LEVEL), 3);
            sun.light = pos;
        }
    }

    private static void clearLight(Sun sun) {
        if (sun.light != null && sun.world.getBlockState(sun.light).isOf(Blocks.LIGHT)) {
            sun.world.setBlockState(sun.light, Blocks.AIR.getDefaultState(), 3);
        }
        sun.light = null;
    }

    /** Places fire in air cells on the sun's surface; never replaces blocks. */
    private static void tryIgniteAirOnShell(ServerWorld world, Vec3d c, double r) {
        double golden = Math.PI * (3.0 - Math.sqrt(5.0));
        for (int i = 0; i < FIRE_SAMPLES; i++) {
            if (world.random.nextDouble() > FIRE_CHANCE) continue;
            double y = 1.0 - (i + 0.5) * (2.0 / FIRE_SAMPLES);
            double ring = Math.sqrt(1.0 - y * y);
            double theta = golden * i;
            BlockPos bp = BlockPos.ofFloored(c.add(Math.cos(theta) * ring * r, y * r, Math.sin(theta) * ring * r));
            if (!world.isAir(bp)) continue;
            BlockState fire = AbstractFireBlock.getState(world, bp);
            if (fire.canPlaceAt(world, bp)) world.setBlockState(bp, fire, 3);
        }
    }

    // ---------------------------------------------------------------------
    // Math helpers
    // ---------------------------------------------------------------------

    private static Vec3d randomUnit(Random r) {
        double z = r.nextDouble() * 2.0 - 1.0;
        double theta = r.nextDouble() * Math.PI * 2.0;
        double s = Math.sqrt(1.0 - z * z);
        return new Vec3d(s * Math.cos(theta), z, s * Math.sin(theta));
    }

    /** Two unit vectors perpendicular to {@code axis} and to each other. */
    private static Vec3d[] perpendicularBasis(Vec3d axis) {
        Vec3d helper = Math.abs(axis.y) < 0.9 ? new Vec3d(0, 1, 0) : new Vec3d(1, 0, 0);
        Vec3d a = axis.crossProduct(helper).normalize();
        Vec3d b = axis.crossProduct(a).normalize();
        return new Vec3d[]{a, b};
    }

    private static double clamp01(double t) {
        return t < 0 ? 0 : (t > 1 ? 1 : t);
    }

    /** Parameter t of the closest point on segment AB to P (unclamped). */
    private static double projectParamOnSegment(Vec3d a, Vec3d b, Vec3d p) {
        Vec3d ab = b.subtract(a);
        double abLenSq = ab.lengthSquared();
        if (abLenSq <= 1.0e-12) return 0.0;
        return p.subtract(a).dotProduct(ab) / abLenSq;
    }

    private static double distanceSqPointToSegment(Vec3d p, Vec3d a, Vec3d b) {
        Vec3d q = a.lerp(b, clamp01(projectParamOnSegment(a, b, p)));
        return p.squaredDistanceTo(q);
    }
}
