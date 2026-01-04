package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.RaycastContext;      // <-- required import
import net.minecraft.world.World;
import org.joml.Vector3f;

import java.util.*;

public class EnergyOrbSpell implements Spell {

    // --- Tunables (adjust freely) ---
    private static final double CAST_RANGE = 90.0;         // where we read your cursor
    private static final double SPEED = 0.50;              // slower
    private static final double TURN = 0.25;               // steering strength per tick
    private static final int LIFE_TICKS = 80;              // 4s
    private static final double HIT_RADIUS = 0.6;          // fatter for big orb
    private static final double AOE_RADIUS = 2.0;          // small AoE
    private static final float DAMAGE = 12.0f;              // ~6 hearts
    private static final double KNOCKBACK = 0.8;           // light push
    private static final double ORB_RADIUS = 0.55;         // ~1 block diameter
    private static final int SHELL_POINTS = 30;            // particles on the “ball” shell per tick
    private static final int CORE_POINTS  = 10;            // particles inside the core per tick

    // Colors for the orb (green)
    private static final DustParticleEffect CORE = new DustParticleEffect(new Vector3f(0.1f, 1.0f, 0.3f), 1.5f);
    private static final DustParticleEffect SHELL = new DustParticleEffect(new Vector3f(0.2f, 0.95f, 0.4f), 1.2f);

    private static final Map<RegistryKey<World>, List<Orb>> ACTIVE = new HashMap<>();
    private static boolean TICK_REGISTERED = false;

    private static void ensureTickerRegistered() {
        if (TICK_REGISTERED) return;
        ServerTickEvents.END_WORLD_TICK.register(EnergyOrbSpell::tickWorld);
        TICK_REGISTERED = true;
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        ensureTickerRegistered();

        // Launch SFX
        world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_ENDER_PEARL_THROW,
                SoundCategory.PLAYERS, 0.9f, 1.0f);

        // Start ~1.6 blocks in front of eyes so it's visible
        Vec3d eye = player.getCameraPosVec(0.0f);
        Vec3d fwd = player.getRotationVector().normalize();
        Vec3d start = eye.add(fwd.multiply(2.0));

        ServerWorld sw = (ServerWorld) world;
        ACTIVE.computeIfAbsent(sw.getRegistryKey(), k -> new ArrayList<>())
                .add(new Orb(player.getUuid(), start, fwd.multiply(SPEED), fwd, sw.getTime()));

        return true;
    }

    // ---- Tick driver ----
    private static void tickWorld(ServerWorld world) {
        List<Orb> orbs = ACTIVE.get(world.getRegistryKey());
        if (orbs == null || orbs.isEmpty()) return;

        long now = world.getTime();
        Random rand = world.getRandom();

        Iterator<Orb> it = orbs.iterator();
        while (it.hasNext()) {
            Orb o = it.next();

            // lifetime
            if (now - o.spawnTick > LIFE_TICKS) { it.remove(); continue; }

            // owner check
            PlayerEntity owner = world.getPlayerByUuid(o.owner);
            if (owner == null) { it.remove(); continue; }

            // Desired aim point from cursor
            HitResult aim = owner.raycast(CAST_RANGE, 0.0f, false);
            Vec3d aimPos = (aim.getType() == HitResult.Type.BLOCK)
                    ? Vec3d.ofCenter(((BlockHitResult)aim).getBlockPos())
                    : owner.getCameraPosVec(0.0f).add(owner.getRotationVector().normalize().multiply(16.0));

            // Steering (forward-only):
            // 1) Desired velocity toward aim
            Vec3d desiredVel = aimPos.subtract(o.pos).normalize().multiply(SPEED);

            // 2) Lerp current velocity toward desired
            Vec3d newVel = o.vel.multiply(1.0 - TURN).add(desiredVel.multiply(TURN));

            // 3) Enforce forward-only: keep non-negative component along initialForward
            double comp = newVel.dotProduct(o.initialForward);
            if (comp <= 0) {
                // remove backward component, keep a tiny forward push so it never stalls
                Vec3d lateral = newVel.subtract(o.initialForward.multiply(comp));
                newVel = lateral.add(o.initialForward.multiply(0.05));
            }
            o.vel = newVel;

            // Move + block hit ray
            Vec3d oldPos = o.pos;
            Vec3d newPos = o.pos.add(o.vel);
            HitResult blockHit = world.raycast(new RaycastContext(
                    oldPos, newPos,
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE,
                    owner
            ));
            if (blockHit.getType() == HitResult.Type.BLOCK) {
                impact(world, blockHit.getPos(), owner, rand);
                it.remove();
                continue;
            }

            // Entity collision along path
            Box path = new Box(oldPos, newPos).expand(HIT_RADIUS);
            List<Entity> targets = world.getOtherEntities(owner, path,
                    e -> e instanceof LivingEntity && e.isAttackable());
            if (!targets.isEmpty()) {
                impact(world, targets.get(0).getPos(), owner, rand);
                it.remove();
                continue;
            }

            // Commit movement
            o.pos = newPos;

            // Render a BIG ball: a core + spherical shell each tick
            // Shell points on random directions with fixed radius
            for (int i = 0; i < SHELL_POINTS; i++) {
                Vec3d n = randomUnit(rand);
                Vec3d p = o.pos.add(n.multiply(ORB_RADIUS));
                world.spawnParticles(SHELL, p.x, p.y, p.z, 4, 0, 0, 0, 0);
            }
            // Core points inside the ball
            //for (int i = 0; i < CORE_POINTS; i++) {
                //Vec3d p = o.pos.add(randomInSphere(rand, ORB_RADIUS * 0.5));
                //world.spawnParticles(CORE, p.x, p.y, p.z, 1, 0, 0, 0, 0);
            //}

            // Soft hum every few ticks
            if ((now - o.spawnTick) % 6 == 0) {
                world.playSound(null, BlockPos.ofFloored(o.pos), SoundEvents.ENTITY_EVOKER_PREPARE_WOLOLO,
                        SoundCategory.PLAYERS, 0.20f, 0.85f);
            }
        }
    }

    // ---- Impact ----
    private static void impact(ServerWorld world, Vec3d where, PlayerEntity owner, Random rand) {
        // Burst
        for (int i = 0; i < 50; i++) {
            Vec3d v = randomUnit(rand).multiply(rand.nextDouble() * 0.5);
            world.spawnParticles(CORE, where.x, where.y, where.z, 1, v.x, v.y, v.z, 0.0);
        }
        // Impact SFX
        world.playSound(null, BlockPos.ofFloored(where), SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE,
                SoundCategory.PLAYERS, 0.6f, 1.2f);

        // Damage + knockback (no owner)
        List<Entity> hit = world.getOtherEntities(owner,
                new Box(where.subtract(AOE_RADIUS, AOE_RADIUS, AOE_RADIUS),
                        where.add(AOE_RADIUS, AOE_RADIUS, AOE_RADIUS)),
                e -> e instanceof LivingEntity && e.isAttackable() && !e.isTeammate(owner));

        for (Entity e : hit) {
            if (e instanceof LivingEntity le) {
                le.damage(world.getDamageSources().playerAttack(owner), DAMAGE);
                Vec3d push = e.getPos().subtract(where).normalize().multiply(KNOCKBACK);
                le.addVelocity(push.x, 0.3, push.z);
                le.velocityDirty = true;
            }
        }
    }

    // ---- Helpers ----
    private static Vec3d randomUnit(Random r) {
        // Sample uniformly on the sphere
        double u = r.nextDouble();
        double v = r.nextDouble();
        double theta = 2 * Math.PI * u;
        double z = 2 * v - 1;
        double s = Math.sqrt(1 - z * z);
        return new Vec3d(s * Math.cos(theta), z, s * Math.sin(theta));
    }

    private static Vec3d randomInSphere(Random r, double radius) {
        // Rejection sampling
        while (true) {
            double x = (r.nextDouble() * 2 - 1) * radius;
            double y = (r.nextDouble() * 2 - 1) * radius;
            double z = (r.nextDouble() * 2 - 1) * radius;
            if (x*x + y*y + z*z <= radius*radius) return new Vec3d(x, y, z);
        }
    }

    private static class Orb {
        final UUID owner;
        Vec3d pos;
        Vec3d vel;
        final Vec3d initialForward;  // used to prevent backward motion
        final long spawnTick;
        Orb(UUID owner, Vec3d pos, Vec3d vel, Vec3d initialForward, long spawnTick) {
            this.owner = owner; this.pos = pos; this.vel = vel;
            this.initialForward = initialForward.normalize();
            this.spawnTick = spawnTick;
        }
    }
}
