// File: src/main/java/net/ragnar/ragnarsmagicmod/util/VoidZone.java
package net.ragnar.ragnarsmagicmod.util;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * A rift into the void torn open in the ground.
 *  1. Rupture: a crack of soul-light races outward, marking the circle.
 *  2. Active: the ground turns to void. Everything inside is dragged toward the centre, slowed,
 *     and drained, and souls torn from the victims spiral into the rift.
 *  3. Collapse: the rift implodes and throws everything out in a shockwave.
 */
public final class VoidZone {
    private VoidZone() {}

    // --- Shape & timing ---
    private static final int RADIUS = 6;
    private static final int RUPTURE_TICKS = 20;
    private static final int ACTIVE_TICKS = 100;           // 5s of void
    private static final int PULSE_EVERY = 10;

    // --- Combat ---
    private static final float PULSE_DAMAGE = 7.0f;         // magic, ignores armor
    private static final float COLLAPSE_DAMAGE = 12.0f;
    private static final double PULL_STRENGTH = 0.09;       // per tick, toward the centre
    private static final double COLLAPSE_KNOCKBACK = 1.6;
    private static final float DRAIN_HEAL = 1.0f;           // caster heals per victim per pulse
    private static final double VIEW_RANGE = 128.0;

    // --- Look ---
    private static final DustParticleEffect VOID_BLACK = new DustParticleEffect(new Vector3f(0.02f, 0.0f, 0.05f), 2.2f);
    private static final DustParticleEffect VOID_PURPLE = new DustParticleEffect(new Vector3f(0.35f, 0.05f, 0.6f), 1.2f);

    private static final List<Zone> ZONES = new ArrayList<>();
    private static boolean registered = false;

    private static final class Zone {
        final ServerWorld world;
        final Vec3d center;              // on the floor, at the centre of the rift
        final List<BlockPos> floor;      // surface block of every column inside the circle
        final UUID casterId;
        int age = 0;

        Zone(ServerWorld world, Vec3d center, List<BlockPos> floor, UUID casterId) {
            this.world = world;
            this.center = center;
            this.floor = floor;
            this.casterId = casterId;
        }
    }

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            Iterator<Zone> it = ZONES.iterator();
            while (it.hasNext()) {
                Zone zone = it.next();
                if (zone.world == world && !tick(zone)) it.remove();
            }
        });
    }

    public static void create(ServerWorld world, Vec3d centerPos, UUID casterId) {
        ensureRegistered();

        BlockPos center = BlockPos.ofFloored(centerPos);
        List<BlockPos> floor = new ArrayList<>();
        for (int x = -RADIUS; x <= RADIUS; x++) {
            for (int z = -RADIUS; z <= RADIUS; z++) {
                if (x * x + z * z > RADIUS * RADIUS) continue;
                BlockPos surface = findSurface(world, center.add(x, 0, z));
                if (surface != null) floor.add(surface);
            }
        }
        if (floor.isEmpty()) return;

        BlockPos centerSurface = findSurface(world, center);
        double floorY = (centerSurface != null ? centerSurface.getY() : center.getY()) + 1.0;
        ZONES.add(new Zone(world, new Vec3d(center.getX() + 0.5, floorY, center.getZ() + 0.5), floor, casterId));

        world.playSound(null, center, SoundEvents.BLOCK_SCULK_SHRIEKER_SHRIEK, SoundCategory.PLAYERS, 1.5f, 0.5f);
        world.playSound(null, center, SoundEvents.ENTITY_WARDEN_EMERGE, SoundCategory.PLAYERS, 1.0f, 1.2f);
    }

    /** Advances one zone; returns false once it has collapsed. */
    private static boolean tick(Zone zone) {
        zone.age++;
        if (zone.age <= RUPTURE_TICKS) {
            renderRupture(zone);
            if (zone.age == RUPTURE_TICKS) open(zone);
            return true;
        }
        if (zone.age <= RUPTURE_TICKS + ACTIVE_TICKS) {
            int activeAge = zone.age - RUPTURE_TICKS;
            renderVoid(zone, activeAge);
            pull(zone);
            if (activeAge % PULSE_EVERY == 0) pulse(zone);
            if (activeAge % 20 == 0) {
                zone.world.playSound(null, zone.center.x, zone.center.y, zone.center.z,
                        SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 2.0f, 0.6f);
            }
            if (activeAge % 40 == 1) {
                zone.world.playSound(null, zone.center.x, zone.center.y, zone.center.z,
                        SoundEvents.BLOCK_PORTAL_AMBIENT, SoundCategory.PLAYERS, 1.2f, 0.5f);
            }
            return true;
        }
        collapse(zone);
        return false;
    }

    // ---------------------------------------------------------------------
    // Phases
    // ---------------------------------------------------------------------

    /** A ring of soul-light racing outward to the edge of the rift. */
    private static void renderRupture(Zone zone) {
        double progress = zone.age / (double) RUPTURE_TICKS;
        double ringRadius = RADIUS * progress;
        int points = (int) (8 + 40 * progress);
        double spin = zone.world.random.nextDouble();
        for (int i = 0; i < points; i++) {
            double a = (i + spin) * Math.PI * 2.0 / points;
            Vec3d p = zone.center.add(Math.cos(a) * ringRadius, 0.1, Math.sin(a) * ringRadius);
            emit(zone.world, i % 3 == 0 ? ParticleTypes.SCULK_SOUL : ParticleTypes.SOUL_FIRE_FLAME, p, 1, 0.05, 0.02, 0.05, 0.01);
        }
        if (zone.age % 4 == 0) {
            emit(zone.world, ParticleTypes.SCULK_CHARGE_POP, zone.center.add(0, 0.2, 0), 10, RADIUS * progress * 0.5, 0.05, RADIUS * progress * 0.5, 0.02);
        }
    }

    private static void open(Zone zone) {
        ServerWorld world = zone.world;
        Vec3d c = zone.center;
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.PLAYERS, 2.0f, 0.5f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_WITHER_SPAWN, SoundCategory.PLAYERS, 1.0f, 0.5f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_END_PORTAL_SPAWN, SoundCategory.PLAYERS, 0.8f, 0.7f);
        emit(world, ParticleTypes.SONIC_BOOM, c.add(0, 0.5, 0), 1, 0, 0, 0, 0);
        // The ground gives way: void bursts up across the whole circle
        for (BlockPos pos : zone.floor) {
            emit(world, VOID_BLACK, Vec3d.ofBottomCenter(pos.up()), 2, 0.35, 0.3, 0.35, 0.0);
            if (world.random.nextInt(3) == 0) emit(world, ParticleTypes.REVERSE_PORTAL, Vec3d.ofBottomCenter(pos.up()), 2, 0.3, 0.2, 0.3, 0.1);
        }
    }

    private static void renderVoid(Zone zone, int activeAge) {
        ServerWorld world = zone.world;
        Random rand = world.random;
        Vec3d c = zone.center;

        // The floor: a churning sheet of black and violet
        for (int i = 0; i < 45; i++) {
            BlockPos pos = zone.floor.get(rand.nextInt(zone.floor.size()));
            Vec3d p = new Vec3d(pos.getX() + rand.nextDouble(), pos.getY() + 1.05, pos.getZ() + rand.nextDouble());
            emit(world, i % 4 == 0 ? VOID_PURPLE : VOID_BLACK, p, 1, 0, 0.02, 0, 0.0);
        }

        // Three spiral arms of void flame dragged into the centre
        double swirl = activeAge * 0.12;
        for (int arm = 0; arm < 3; arm++) {
            for (int i = 1; i <= 8; i++) {
                double t = i / 8.0;
                double r = RADIUS * t;
                double a = swirl + arm * (Math.PI * 2.0 / 3.0) + t * 2.6;
                Vec3d p = c.add(Math.cos(a) * r, 0.15, Math.sin(a) * r);
                // Velocity tangent-and-inward so the arms visibly wind into the rift
                Vec3d v = new Vec3d(-Math.sin(a), 0, Math.cos(a)).multiply(0.06).add(new Vec3d(-Math.cos(a), 0, -Math.sin(a)).multiply(0.05));
                emit(world, i % 2 == 0 ? ParticleTypes.REVERSE_PORTAL : ParticleTypes.SOUL_FIRE_FLAME, p, 0, v.x, v.y, v.z, 1.0);
            }
        }

        // Edge of the rift
        if (activeAge % 2 == 0) {
            int ring = 40;
            for (int i = 0; i < ring; i++) {
                double a = -swirl * 0.5 + i * Math.PI * 2.0 / ring;
                emit(world, ParticleTypes.SOUL_FIRE_FLAME, c.add(Math.cos(a) * RADIUS, 0.1, Math.sin(a) * RADIUS), 1, 0, 0.02, 0, 0.0);
            }
        }

        // A dark column rising out of the centre
        for (int i = 0; i < 5; i++) {
            Vec3d p = c.add((rand.nextDouble() - 0.5) * 0.8, rand.nextDouble() * 0.5, (rand.nextDouble() - 0.5) * 0.8);
            emit(world, i < 3 ? VOID_BLACK : ParticleTypes.PORTAL, p, 0, 0, 0.3 + rand.nextDouble() * 0.2, 0, 1.0);
        }
        if (rand.nextInt(3) == 0) {
            emit(world, ParticleTypes.SCULK_SOUL, c.add((rand.nextDouble() - 0.5) * RADIUS, 0.3, (rand.nextDouble() - 0.5) * RADIUS), 1, 0.1, 0.1, 0.1, 0.03);
        }
    }

    private static void collapse(Zone zone) {
        ServerWorld world = zone.world;
        Random rand = world.random;
        Vec3d c = zone.center;

        // Everything rushes inward...
        for (int i = 0; i < 80; i++) {
            double a = rand.nextDouble() * Math.PI * 2.0;
            double r = RADIUS * (0.6 + rand.nextDouble() * 0.5);
            Vec3d p = c.add(Math.cos(a) * r, 0.2 + rand.nextDouble() * 1.5, Math.sin(a) * r);
            Vec3d v = c.add(0, 0.8, 0).subtract(p).multiply(0.12);
            emit(world, i % 2 == 0 ? ParticleTypes.REVERSE_PORTAL : ParticleTypes.SCULK_SOUL, p, 0, v.x, v.y, v.z, 1.0);
        }
        // ...then blows out
        emit(world, ParticleTypes.SONIC_BOOM, c.add(0, 0.8, 0), 1, 0, 0, 0, 0);
        emit(world, ParticleTypes.EXPLOSION_EMITTER, c.add(0, 0.5, 0), 1, 0, 0, 0, 0);
        for (int i = 0; i < 60; i++) {
            double a = i * Math.PI * 2.0 / 60;
            Vec3d v = new Vec3d(Math.cos(a), 0.05, Math.sin(a)).multiply(0.6);
            emit(world, ParticleTypes.SOUL_FIRE_FLAME, c.add(0, 0.3, 0), 0, v.x, v.y, v.z, 1.0);
        }
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.PLAYERS, 2.5f, 0.4f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 2.0f, 0.5f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_RESPAWN_ANCHOR_DEPLETE.value(), SoundCategory.PLAYERS, 2.0f, 0.6f);

        for (LivingEntity e : victims(zone, 1.5)) {
            e.damage(world.getDamageSources().magic(), COLLAPSE_DAMAGE);
            Vec3d out = e.getPos().subtract(c).multiply(1, 0, 1);
            out = out.lengthSquared() > 1.0e-4 ? out.normalize() : new Vec3d(1, 0, 0);
            e.addVelocity(out.x * COLLAPSE_KNOCKBACK, 0.7, out.z * COLLAPSE_KNOCKBACK);
            e.velocityModified = true;
        }
    }

    // ---------------------------------------------------------------------
    // Effects on entities
    // ---------------------------------------------------------------------

    /** Living things inside the rift, excluding the caster. {@code grow} widens the circle. */
    private static List<LivingEntity> victims(Zone zone, double grow) {
        double r = RADIUS + grow;
        Box box = new Box(zone.center.x - r, zone.center.y - 2.0, zone.center.z - r,
                zone.center.x + r, zone.center.y + 3.5, zone.center.z + r);
        return zone.world.getEntitiesByClass(LivingEntity.class, box, e -> {
            if (!e.isAlive() || e.isSpectator()) return false;
            if (zone.casterId != null && e.getUuid().equals(zone.casterId)) return false;
            if (e instanceof PlayerEntity p && p.isCreative()) return false;
            double dx = e.getX() - zone.center.x, dz = e.getZ() - zone.center.z;
            return dx * dx + dz * dz <= r * r;
        });
    }

    /** Drags everything toward the centre, harder the further out it is. */
    private static void pull(Zone zone) {
        for (LivingEntity e : victims(zone, 0.5)) {
            Vec3d in = zone.center.subtract(e.getPos()).multiply(1, 0, 1);
            double dist = in.length();
            if (dist < 0.6) continue;
            Vec3d v = in.multiply(PULL_STRENGTH * Math.min(1.0, dist / RADIUS + 0.35) / dist);
            e.addVelocity(v.x, -0.02, v.z);
            e.velocityModified = true;
        }
    }

    /** Drain pulse: damages and slows victims, tears their souls into the rift, and feeds the caster. */
    private static void pulse(Zone zone) {
        ServerWorld world = zone.world;
        PlayerEntity caster = zone.casterId != null ? world.getPlayerByUuid(zone.casterId) : null;
        int drained = 0;

        for (LivingEntity e : victims(zone, 0.0)) {
            if (!e.damage(world.getDamageSources().magic(), PULSE_DAMAGE)) continue;
            drained++;
            e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 25, 1, false, false, true));

            // Soul stream from the victim to the centre
            Vec3d from = e.getBoundingBox().getCenter();
            Vec3d to = zone.center.add(0, 0.4, 0);
            for (int i = 0; i < 6; i++) {
                Vec3d p = from.lerp(to, i / 6.0);
                emit(world, ParticleTypes.SCULK_SOUL, p, 1, 0.05, 0.05, 0.05, 0.01);
            }
        }

        if (drained > 0) {
            world.playSound(null, zone.center.x, zone.center.y, zone.center.z,
                    SoundEvents.PARTICLE_SOUL_ESCAPE.value(), SoundCategory.PLAYERS, 2.0f, 0.6f);
            if (caster != null && caster.isAlive()) caster.heal(Math.min(4.0f, drained * DRAIN_HEAL));
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static BlockPos findSurface(ServerWorld w, BlockPos start) {
        for (int i = 4; i >= -4; i--) {
            BlockPos p = start.up(i);
            BlockState state = w.getBlockState(p);
            BlockState above = w.getBlockState(p.up());
            if (state.isSolidBlock(w, p) && !above.isSolidBlock(w, p.up())) return p;
        }
        return null;
    }

    /** Sends a particle to every player within {@link #VIEW_RANGE}, past the normal 32 block cutoff. */
    private static void emit(ServerWorld world, ParticleEffect type, Vec3d p, int count, double dx, double dy, double dz, double speed) {
        for (ServerPlayerEntity viewer : world.getPlayers()) {
            if (viewer.squaredDistanceTo(p) <= VIEW_RANGE * VIEW_RANGE) {
                world.spawnParticles(viewer, type, true, p.x, p.y, p.z, count, dx, dy, dz, speed);
            }
        }
    }
}
