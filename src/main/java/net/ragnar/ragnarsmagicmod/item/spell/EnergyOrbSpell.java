package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.registry.RegistryKey;
import org.joml.Vector3f;
import net.minecraft.world.RaycastContext;

import java.util.*;

public class EnergyOrbSpell implements Spell {

    // --- tunables ---
    private static final double CAST_RANGE = 64.0;        // where we raycast your cursor
    private static final double SPEED = 0.75;             // blocks/tick (≈15 m/s)
    private static final double TURN = 0.30;              // how fast it steers toward cursor (0..1 lerp)
    private static final int LIFE_TICKS = 80;             // 4 seconds max
    private static final double HIT_RADIUS = 0.4;         // collision “thickness”
    private static final double AOE_RADIUS = 2.0;         // explosion radius
    private static final float DAMAGE = 5.0f;             // small damage (2.5 hearts)
    private static final double KNOCKBACK = 0.8;          // gentle push
    private static final int PARTICLES_PER_TICK = 16;     // “dense” trail

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

        // launch SFX
        world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_ENDER_PEARL_THROW,
                SoundCategory.PLAYERS, 0.8f, 1.2f);

        // spawn orb at eye pos moving forward
        Vec3d eye = player.getCameraPosVec(0.0f);
        Vec3d dir = player.getRotationVector().normalize();
        Vec3d start = eye.add(dir.multiply(0.6)); // a bit in front of eyes

        ServerWorld sw = (ServerWorld) world;
        ACTIVE.computeIfAbsent(sw.getRegistryKey(), k -> new ArrayList<>())
                .add(new Orb(player.getUuid(), start, dir.multiply(SPEED), sw.getTime()));

        return true;
    }

    // --- ticking / behavior ---
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

            // fetch owner (if gone, kill orb)
            PlayerEntity owner = world.getPlayerByUuid(o.owner);
            if (owner == null) { it.remove(); continue; }

            // find aim point from owner’s current look
            HitResult aim = owner.raycast(CAST_RANGE, 0.0f, false);
            Vec3d aimPos;
            if (aim.getType() == HitResult.Type.BLOCK) {
                BlockHitResult bhr = (BlockHitResult) aim;
                aimPos = Vec3d.ofCenter(bhr.getBlockPos());
            } else {
                aimPos = owner.getCameraPosVec(0.0f)
                        .add(owner.getRotationVector().normalize().multiply(16.0));
            }

            // steer velocity toward aim
            Vec3d desiredDir = aimPos.subtract(o.pos).normalize().multiply(SPEED);
            o.vel = o.vel.multiply(1.0 - TURN).add(desiredDir.multiply(TURN));

            // move & check block hit with a ray
            Vec3d oldPos = o.pos;
            Vec3d newPos = o.pos.add(o.vel);
            HitResult blockHit = world.raycast(new RaycastContext(
                    oldPos, newPos,
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE,
                    owner
            ));

            if (blockHit.getType() == HitResult.Type.BLOCK) {
                // impact at the hit point
                impact(world, blockHit.getPos(), owner, rand);
                it.remove();
                continue;
            }

            // entity hit: check small AABB around path
            Box pathBox = new Box(oldPos, newPos).expand(HIT_RADIUS);
            List<Entity> targets = world.getOtherEntities(owner, pathBox, e -> e instanceof LivingEntity && e.isAttackable());
            if (!targets.isEmpty()) {
                impact(world, targets.get(0).getPos(), owner, rand);
                it.remove();
                continue;
            }

            // commit movement
            o.pos = newPos;

            // trail particles (dense green)
            for (int i = 0; i < PARTICLES_PER_TICK; i++) {
                double t = i / (double) PARTICLES_PER_TICK;
                Vec3d p = oldPos.lerp(newPos, t);
                world.spawnParticles(
                        new DustParticleEffect(new Vector3f(0.1f, 1.0f, 0.3f), 1.2f),
                        p.x, p.y, p.z,
                        1, 0.0, 0.0, 0.0, 0.0
                );
            }

            // subtle hum
            if ((now - o.spawnTick) % 6 == 0) {
                world.playSound(null, BlockPos.ofFloored(o.pos), SoundEvents.BLOCK_AMETHYST_BLOCK_STEP,
                        SoundCategory.PLAYERS, 0.4f, 1.4f);
            }
        }
    }

    private static void impact(ServerWorld world, Vec3d where, PlayerEntity owner, Random rand) {
        // impact particles burst
        for (int i = 0; i < 40; i++) {
            double rx = (rand.nextDouble() - 0.5) * 1.8;
            double ry = (rand.nextDouble() - 0.2) * 1.0;
            double rz = (rand.nextDouble() - 0.5) * 1.8;
            world.spawnParticles(
                    new DustParticleEffect(new Vector3f(0.1f, 1.0f, 0.3f), 1.4f),
                    where.x, where.y, where.z,
                    1, rx * 0.1, ry * 0.1, rz * 0.1, 0.0
            );
        }

        // SFX
        world.playSound(null, BlockPos.ofFloored(where), SoundEvents.ENTITY_GUARDIAN_ATTACK,
                SoundCategory.PLAYERS, 0.9f, 1.25f);

        // damage + knockback in small AoE (doesn't hurt owner)
        List<Entity> hit = world.getOtherEntities(owner,
                new Box(where.subtract(AOE_RADIUS, AOE_RADIUS, AOE_RADIUS),
                        where.add(AOE_RADIUS, AOE_RADIUS, AOE_RADIUS)),
                e -> e instanceof LivingEntity && e.isAttackable() && !e.isTeammate(owner));

        for (Entity e : hit) {
            if (!(e instanceof LivingEntity le)) continue;
            // damage
            le.damage(world.getDamageSources().playerAttack(owner), DAMAGE);
            // knockback away from center
            Vec3d push = e.getPos().subtract(where).normalize().multiply(KNOCKBACK);
            le.addVelocity(push.x, 0.25, push.z);
            le.velocityDirty = true;
        }
    }

    // simple state holder
    private static class Orb {
        final UUID owner;
        Vec3d pos;
        Vec3d vel;
        final long spawnTick;
        Orb(UUID owner, Vec3d pos, Vec3d vel, long spawnTick) {
            this.owner = owner; this.pos = pos; this.vel = vel; this.spawnTick = spawnTick;
        }
    }
}
