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
import net.minecraft.util.math.random.Random;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.*;

public class TrackingSpell implements Spell {

    // --- tuning ---
    private static final double CAST_RANGE = 48.0;     // how far we search for a target on cast
    private static final double SPEED = 0.60;          // quite fast
    private static final double TURN = 0.14;           // steering per tick (limited homing)
    private static final int LIFE_TICKS = 60;          // 3s max life
    private static final double HIT_RADIUS = 0.35;     // collision thickness
    private static final float DAMAGE = 5.0f;          // 5 damage (2.5 hearts)
    private static final double KNOCKBACK = 0.50;      // tiny push
    private static final double START_AHEAD = 1.7;     // spawn in front of eyes

    // visuals (purple vibe)
    private static final int SHELL_POINTS = 6;         // tight purple shell each tick
    private static final int CORE_POINTS  = 2;         // faint core sparks
    private static final int BURST_EVERY  = 6;         // small flare cadence
    private static final double ORB_RADIUS = 0.18;

    private static final Map<RegistryKey<World>, List<Bolt>> ACTIVE = new HashMap<>();
    private static boolean TICK_REGISTERED = false;

    private static void ensureTickerRegistered() {
        if (TICK_REGISTERED) return;
        ServerTickEvents.END_WORLD_TICK.register(TrackingSpell::tickWorld);
        TICK_REGISTERED = true;
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        ensureTickerRegistered();

        // launch SFX
        world.playSound(null, player.getBlockPos(),
                SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 0.9f, 1.3f);
        world.playSound(null, player.getBlockPos(),
                SoundEvents.ITEM_FLINTANDSTEEL_USE, SoundCategory.PLAYERS, 0.9f, 1.8f);

        // pick initial target if the player is looking at something alive
        LivingEntity target = findLookTarget((ServerWorld) world, player);

        Vec3d eye = player.getCameraPosVec(0.0f);
        Vec3d dir = player.getRotationVector().normalize();
        Vec3d start = eye.add(dir.multiply(START_AHEAD));

        ServerWorld sw = (ServerWorld) world;
        ACTIVE.computeIfAbsent(sw.getRegistryKey(), k -> new ArrayList<>())
                .add(new Bolt(player.getUuid(), target == null ? null : target.getUuid(),
                        start, dir.multiply(SPEED), dir, sw.getTime()));

        return true;
    }

    // ---- main tick driver ----
    private static void tickWorld(ServerWorld world) {
        List<Bolt> bolts = ACTIVE.get(world.getRegistryKey());
        if (bolts == null || bolts.isEmpty()) return;

        long now = world.getTime();
        Random rand = world.getRandom();

        Iterator<Bolt> it = bolts.iterator();
        while (it.hasNext()) {
            Bolt b = it.next();
            PlayerEntity owner = world.getPlayerByUuid(b.owner);
            if (owner == null) { it.remove(); continue; }
            if (now - b.spawnTick > LIFE_TICKS) { it.remove(); continue; }

            // resolve target (if we have a UUID, try to fetch; else try to acquire if player is looking)
            LivingEntity target = null;
            if (b.targetUuid != null) {
                Entity e = world.getEntity(b.targetUuid);
                if (e instanceof LivingEntity le && le.isAlive()) target = le;
            } else {
                // lazy acquire in early life to feel responsive
                if (now - b.spawnTick < 10) {
                    target = findLookTarget(world, owner);
                    if (target != null) b.targetUuid = target.getUuid();
                }
            }

            // steering: desire towards target (or keep current direction)
            Vec3d desiredVel = (target != null)
                    ? target.getPos().add(0, target.getHeight() * 0.4, 0).subtract(b.pos).normalize().multiply(SPEED)
                    : b.vel.normalize().multiply(SPEED);

            // blend toward desired, but enforce forward-only relative to initialForward
            Vec3d newVel = b.vel.multiply(1.0 - TURN).add(desiredVel.multiply(TURN));
            double comp = newVel.dotProduct(b.initialForward);
            if (comp <= 0) {
                Vec3d lateral = newVel.subtract(b.initialForward.multiply(comp));
                newVel = lateral.add(b.initialForward.multiply(0.08)); // tiny nudge forward
            }
            b.vel = newVel;

            // try block hit along path
            Vec3d oldPos = b.pos;
            Vec3d newPos = b.pos.add(b.vel);
            HitResult blockHit = world.raycast(new RaycastContext(
                    oldPos, newPos, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, owner));
            if (blockHit.getType() == HitResult.Type.BLOCK) {
                impact(world, ((BlockHitResult) blockHit).getPos(), owner);
                it.remove();
                continue;
            }

            // entity check along swept box
            Box path = new Box(oldPos, newPos).expand(HIT_RADIUS);
            List<Entity> hits = world.getOtherEntities(owner, path,
                    e -> e instanceof LivingEntity && e.isAttackable() && !e.isTeammate(owner));
            if (!hits.isEmpty()) {
                Entity e = hits.get(0);
                impact(world, e.getPos(), owner);
                if (e instanceof LivingEntity le) {
                    le.damage(world.getDamageSources().playerAttack(owner), DAMAGE);
                    Vec3d push = e.getPos().subtract(b.pos).normalize().multiply(KNOCKBACK);
                    le.addVelocity(push.x, 0.2, push.z);
                    le.velocityDirty = true;
                }
                it.remove();
                continue;
            }

            // move
            b.pos = newPos;

            // visuals — purple orb with flare, not a plain trail
            // tight shell
            for (int i = 0; i < SHELL_POINTS; i++) {
                double th = (Math.PI * 2 * i) / SHELL_POINTS;
                double xOff = Math.cos(th) * ORB_RADIUS;
                double zOff = Math.sin(th) * ORB_RADIUS;
                double yOff = (rand.nextDouble() - 0.5) * 0.04;
                world.spawnParticles(ParticleTypes.REVERSE_PORTAL, b.pos.x + xOff, b.pos.y + yOff, b.pos.z + zOff, 1, 0, 0, 0, 0);
            }
            // core sparks
            for (int i = 0; i < CORE_POINTS; i++) {
                double s = 0.03;
                world.spawnParticles(ParticleTypes.DRAGON_BREATH,
                        b.pos.x + (rand.nextDouble() - 0.5) * s,
                        b.pos.y + (rand.nextDouble() - 0.5) * s,
                        b.pos.z + (rand.nextDouble() - 0.5) * s,
                        1, 0, 0, 0, 0);
            }
            // periodic flare ring (gives motion feel, still not a boring trail)
            if (((now - b.spawnTick) % BURST_EVERY) == 0) {
                int n = 10;
                double r = ORB_RADIUS + 0.08;
                for (int i = 0; i < n; i++) {
                    double a = (Math.PI * 2 * i) / n;
                    world.spawnParticles(ParticleTypes.ENCHANT, b.pos.x + Math.cos(a) * r, b.pos.y, b.pos.z + Math.sin(a) * r, 1, 0, 0, 0, 0);
                }
                world.playSound(null, BlockPos.ofFloored(b.pos),
                        SoundEvents.BLOCK_AMETHYST_BLOCK_STEP, SoundCategory.PLAYERS, 0.4f, 1.8f);
            }
        }
    }

    // pick a target the player is looking at (front-cone)
    private static LivingEntity findLookTarget(ServerWorld world, PlayerEntity player) {
        Vec3d eye = player.getCameraPosVec(0.0f);
        Vec3d look = player.getRotationVector().normalize();
        double maxDist = CAST_RANGE;

        // get nearby entities in front-half space
        Box box = player.getBoundingBox().expand(maxDist);
        List<Entity> nearby = world.getOtherEntities(player, box,
                e -> e instanceof LivingEntity && e.isAlive() && !e.isTeammate(player));

        LivingEntity best = null;
        double bestDot = 0.75; // ~41° cone
        double bestDist = Double.MAX_VALUE;

        for (Entity e : nearby) {
            Vec3d to = e.getPos().add(0, e.getHeight() * 0.4, 0).subtract(eye);
            double dist = to.length();
            if (dist > maxDist || dist < 0.1) continue;
            Vec3d dir = to.normalize();
            double dot = dir.dotProduct(look);
            if (dot > bestDot) {
                bestDot = dot;
                bestDist = dist;
                best = (LivingEntity) e;
            }
        }
        return best;
        // Note: we don't require clear line-of-sight; it's a “magical lock”.
    }

    private static void impact(ServerWorld world, Vec3d where, PlayerEntity owner) {
        // purple puff + chime
        for (int i = 0; i < 20; i++) {
            double vx = (world.getRandom().nextDouble() - 0.5) * 0.3;
            double vy = (world.getRandom().nextDouble() - 0.2) * 0.3;
            double vz = (world.getRandom().nextDouble() - 0.5) * 0.3;
            world.spawnParticles(ParticleTypes.DRAGON_BREATH, where.x, where.y, where.z, 1, vx, vy, vz, 0.0);
        }
        world.playSound(null, BlockPos.ofFloored(where),
                SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.9f, 1.2f);
    }

    private static class Bolt {
        final UUID owner;
        UUID targetUuid;       // can be null
        Vec3d pos;
        Vec3d vel;
        final Vec3d initialForward;
        final long spawnTick;

        Bolt(UUID owner, UUID targetUuid, Vec3d pos, Vec3d vel, Vec3d initialForward, long spawnTick) {
            this.owner = owner;
            this.targetUuid = targetUuid;
            this.pos = pos;
            this.vel = vel;
            this.initialForward = initialForward.normalize();
            this.spawnTick = spawnTick;
        }
    }
}
