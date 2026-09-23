package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.DustColorTransitionParticleEffect;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A ball of energy that rides your crosshair. It travels outward along your line of sight and
 * swings wherever you look, so you can steer it through mobs. Sneak to hold it at its current
 * distance. It passes through up to {@link #MAX_HITS} mobs, then bursts; it also bursts on
 * blocks or when it runs out of time.
 */
public class EnergyOrbSpell implements Spell {
    // --- Control ---
    private static final double START_DIST = 2.5;   // spawns just past the staff
    private static final double ADVANCE = 0.75;     // how fast it pushes out along your aim (blocks/tick)
    private static final double MAX_DIST = 40.0;
    private static final double MAX_STEP = 1.8;     // top speed while chasing your crosshair
    private static final double RESPONSIVENESS = 0.6; // 0..1, how quickly it answers mouse movement
    private static final int LIFE_TICKS = 100;      // 5s

    // --- Combat ---
    private static final double HIT_RADIUS = 0.45;
    private static final float HIT_DAMAGE = 11.0f;  // two hits kill a 20 HP mob, even through light armor
    private static final int MAX_HITS = 3;
    private static final int REHIT_TICKS = 12;      // same mob can be hit again after this
    private static final double KNOCKBACK = 0.7;
    private static final double BURST_RADIUS = 2.5;
    private static final float BURST_DAMAGE = 6.0f;

    // --- Look ---
    private static final double ORB_RADIUS = 0.3;
    private static final int SHELL_POINTS = 14;
    private static final Vector3f BRIGHT = new Vector3f(0.75f, 1.0f, 0.85f);
    private static final Vector3f GREEN = new Vector3f(0.15f, 1.0f, 0.45f);
    private static final Vector3f DEEP = new Vector3f(0.0f, 0.35f, 0.3f);
    private static final DustParticleEffect CORE = new DustParticleEffect(BRIGHT, 1.4f);
    private static final DustParticleEffect SHELL = new DustParticleEffect(GREEN, 0.7f);
    private static final DustColorTransitionParticleEffect TRAIL = new DustColorTransitionParticleEffect(GREEN, DEEP, 0.9f);

    private static final List<Orb> ACTIVE = new ArrayList<>();
    private static boolean registered = false;

    private static final class Orb {
        final ServerWorld world;
        final UUID owner;
        Vec3d pos;
        Vec3d vel = Vec3d.ZERO;
        double dist = START_DIST;
        int age = 0;
        int hits = 0;
        final Map<UUID, Integer> lastHit = new HashMap<>();

        Orb(ServerWorld world, UUID owner, Vec3d pos) {
            this.world = world;
            this.owner = owner;
            this.pos = pos;
        }
    }

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            Iterator<Orb> it = ACTIVE.iterator();
            while (it.hasNext()) {
                Orb orb = it.next();
                if (orb.world == world && !tick(orb)) it.remove();
            }
        });
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        ensureRegistered();

        ServerWorld sw = (ServerWorld) world;
        Vec3d start = player.getEyePos().add(player.getRotationVector().multiply(START_DIST));
        ACTIVE.add(new Orb(sw, player.getUuid(), start));

        world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_ILLUSIONER_CAST_SPELL, SoundCategory.PLAYERS, 1.0f, 1.4f);
        world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_BREEZE_SHOOT, SoundCategory.PLAYERS, 0.6f, 1.6f);
        sw.spawnParticles(ParticleTypes.ELECTRIC_SPARK, start.x, start.y, start.z, 12, 0.2, 0.2, 0.2, 0.15);
        return true;
    }

    /** Advances one orb; returns false once it is gone. */
    private static boolean tick(Orb orb) {
        ServerWorld world = orb.world;
        PlayerEntity owner = world.getPlayerByUuid(orb.owner);
        if (owner == null || !owner.isAlive()) {
            fizzle(orb);
            return false;
        }
        if (++orb.age > LIFE_TICKS) {
            burst(orb, owner, orb.pos);
            return false;
        }

        // Chase the point on the crosshair ray; sneaking holds the orb at its current range
        if (!owner.isSneaking()) orb.dist = Math.min(MAX_DIST, orb.dist + ADVANCE);
        Vec3d target = owner.getEyePos().add(owner.getRotationVector().multiply(orb.dist));
        Vec3d toTarget = target.subtract(orb.pos);
        double len = toTarget.length();
        Vec3d wanted = len > MAX_STEP ? toTarget.multiply(MAX_STEP / len) : toTarget;
        orb.vel = orb.vel.multiply(1.0 - RESPONSIVENESS).add(wanted.multiply(RESPONSIVENESS));

        Vec3d from = orb.pos;
        Vec3d to = from.add(orb.vel);

        HitResult blockHit = world.raycast(new RaycastContext(from, to,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, owner));
        if (blockHit.getType() == HitResult.Type.BLOCK) {
            burst(orb, owner, blockHit.getPos());
            return false;
        }

        // Pierce through mobs along the path
        Box sweep = new Box(from, to).expand(HIT_RADIUS + 1.0);
        for (Entity e : world.getOtherEntities(owner, sweep, e -> e instanceof LivingEntity && e.isAlive() && e.isAttackable())) {
            Box hitBox = e.getBoundingBox().expand(HIT_RADIUS);
            if (!hitBox.contains(to) && hitBox.raycast(from, to).isEmpty()) continue;
            Integer last = orb.lastHit.get(e.getUuid());
            if (last != null && orb.age - last < REHIT_TICKS) continue;

            orb.lastHit.put(e.getUuid(), orb.age);
            strike(world, owner, (LivingEntity) e, orb.vel);
            if (++orb.hits >= MAX_HITS) {
                burst(orb, owner, e.getBoundingBox().getCenter());
                return false;
            }
        }

        orb.pos = to;
        render(world, orb, from, to);
        return true;
    }

    private static void strike(ServerWorld world, PlayerEntity owner, LivingEntity target, Vec3d motion) {
        target.damage(world.getDamageSources().playerAttack(owner), HIT_DAMAGE);
        Vec3d dir = motion.lengthSquared() > 1.0e-6 ? motion.normalize() : owner.getRotationVector();
        target.addVelocity(dir.x * KNOCKBACK, 0.25, dir.z * KNOCKBACK);
        target.velocityModified = true;

        Vec3d c = target.getBoundingBox().getCenter();
        world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, 16, 0.3, 0.4, 0.3, 0.25);
        world.spawnParticles(CORE, c.x, c.y, c.z, 6, 0.25, 0.3, 0.25, 0.0);
        world.playSound(null, target.getBlockPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_BREAK, SoundCategory.PLAYERS, 1.0f, 1.5f);
        world.playSound(null, target.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, SoundCategory.PLAYERS, 0.8f, 1.3f);
    }

    private static void burst(Orb orb, PlayerEntity owner, Vec3d where) {
        ServerWorld world = orb.world;
        Random rand = world.getRandom();

        world.spawnParticles(ParticleTypes.FLASH, where.x, where.y, where.z, 1, 0, 0, 0, 0);
        for (int i = 0; i < 40; i++) {
            Vec3d v = randomUnit(rand).multiply(0.25 + rand.nextDouble() * 0.35);
            world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, where.x, where.y, where.z, 0, v.x, v.y, v.z, 1.0);
        }
        for (int i = 0; i < 24; i++) {
            Vec3d v = randomUnit(rand).multiply(0.15 + rand.nextDouble() * 0.2);
            world.spawnParticles(TRAIL, where.x, where.y, where.z, 0, v.x, v.y, v.z, 1.0);
        }
        world.playSound(null, where.x, where.y, where.z, SoundEvents.ENTITY_FIREWORK_ROCKET_BLAST, SoundCategory.PLAYERS, 1.0f, 0.8f);
        world.playSound(null, where.x, where.y, where.z, SoundEvents.BLOCK_BEACON_DEACTIVATE, SoundCategory.PLAYERS, 0.8f, 1.8f);

        Box area = new Box(where, where).expand(BURST_RADIUS);
        for (Entity e : world.getOtherEntities(owner, area, e -> e instanceof LivingEntity && e.isAlive() && e.isAttackable() && !e.isTeammate(owner))) {
            if (e.getBoundingBox().getCenter().squaredDistanceTo(where) > BURST_RADIUS * BURST_RADIUS) continue;
            LivingEntity le = (LivingEntity) e;
            le.damage(world.getDamageSources().playerAttack(owner), BURST_DAMAGE);
            Vec3d push = e.getPos().subtract(where);
            push = push.lengthSquared() > 1.0e-6 ? push.normalize().multiply(KNOCKBACK) : Vec3d.ZERO;
            le.addVelocity(push.x, 0.35, push.z);
            le.velocityModified = true;
        }
    }

    private static void fizzle(Orb orb) {
        orb.world.spawnParticles(TRAIL, orb.pos.x, orb.pos.y, orb.pos.z, 12, 0.2, 0.2, 0.2, 0.02);
    }

    private static void render(ServerWorld world, Orb orb, Vec3d from, Vec3d to) {
        // Trail filled in along the whole step so fast swings stay continuous
        Vec3d step = to.subtract(from);
        int trailSteps = Math.max(1, (int) Math.ceil(step.length() / 0.25));
        for (int i = 0; i < trailSteps; i++) {
            Vec3d p = from.add(step.multiply(i / (double) trailSteps));
            world.spawnParticles(TRAIL, p.x, p.y, p.z, 1, 0.03, 0.03, 0.03, 0.0);
        }

        // Spinning shell (fibonacci sphere) around a bright core
        double spin = orb.age * 0.35;
        double golden = Math.PI * (3.0 - Math.sqrt(5.0));
        for (int i = 0; i < SHELL_POINTS; i++) {
            double y = 1.0 - (i + 0.5) * (2.0 / SHELL_POINTS);
            double r = Math.sqrt(1.0 - y * y);
            double theta = golden * i + spin;
            world.spawnParticles(SHELL,
                    to.x + Math.cos(theta) * r * ORB_RADIUS, to.y + y * ORB_RADIUS, to.z + Math.sin(theta) * r * ORB_RADIUS,
                    1, 0, 0, 0, 0);
        }
        world.spawnParticles(CORE, to.x, to.y, to.z, 2, 0.04, 0.04, 0.04, 0.0);

        // Crackle
        if (orb.age % 2 == 0) {
            Vec3d v = randomUnit(world.getRandom()).multiply(0.12);
            world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, to.x, to.y, to.z, 0, v.x, v.y, v.z, 1.0);
        }
        if (orb.age % 12 == 1) {
            world.playSound(null, to.x, to.y, to.z, SoundEvents.BLOCK_CONDUIT_AMBIENT_SHORT, SoundCategory.PLAYERS, 0.6f, 1.7f);
        }
    }

    private static Vec3d randomUnit(Random r) {
        double z = r.nextDouble() * 2.0 - 1.0;
        double theta = r.nextDouble() * Math.PI * 2.0;
        double s = Math.sqrt(1.0 - z * z);
        return new Vec3d(s * Math.cos(theta), z, s * Math.sin(theta));
    }
}
