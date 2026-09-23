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

    // visuals: an arcane seeker with a twin-helix trail and a lock-on sigil over its target
    private static final double HELIX_RADIUS = 0.22;
    private static final org.joml.Vector3f MAGENTA = new org.joml.Vector3f(1.0f, 0.3f, 0.9f);
    private static final org.joml.Vector3f VIOLET = new org.joml.Vector3f(0.45f, 0.15f, 1.0f);
    private static final net.minecraft.particle.DustParticleEffect CORE =
            new net.minecraft.particle.DustParticleEffect(new org.joml.Vector3f(1.0f, 0.85f, 1.0f), 1.1f);
    private static final net.minecraft.particle.DustColorTransitionParticleEffect TRAIL =
            new net.minecraft.particle.DustColorTransitionParticleEffect(MAGENTA, VIOLET, 0.7f);
    private static final net.minecraft.particle.DustParticleEffect SIGIL =
            new net.minecraft.particle.DustParticleEffect(MAGENTA, 0.8f);

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
        launchSigil(sw, start, dir);
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

            renderBolt(world, b, oldPos, newPos, (int) (now - b.spawnTick));
            if (target != null) renderLockOn(world, target, (int) (now - b.spawnTick));
            if (((now - b.spawnTick) % 6) == 0) {
                world.playSound(null, BlockPos.ofFloored(b.pos),
                        SoundEvents.BLOCK_AMETHYST_BLOCK_STEP, SoundCategory.PLAYERS, 0.5f, 1.8f);
            }
        }
    }

    /** Bright core, twin helix spiralling around the flight path, and the odd spark shed behind. */
    private static void renderBolt(ServerWorld world, Bolt b, Vec3d from, Vec3d to, int age) {
        Vec3d dir = to.subtract(from);
        double len = dir.length();
        if (len < 1.0e-4) return;
        dir = dir.multiply(1.0 / len);
        Vec3d helper = Math.abs(dir.y) > 0.9 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
        Vec3d u = dir.crossProduct(helper).normalize();
        Vec3d v = dir.crossProduct(u).normalize();

        int steps = 4;
        for (int i = 0; i < steps; i++) {
            double t = i / (double) steps;
            Vec3d p = from.lerp(to, t);
            double phase = (age + t) * 1.6;
            for (int strand = 0; strand < 2; strand++) {
                double a = phase + strand * Math.PI;
                Vec3d q = p.add(u.multiply(Math.cos(a) * HELIX_RADIUS)).add(v.multiply(Math.sin(a) * HELIX_RADIUS));
                world.spawnParticles(TRAIL, q.x, q.y, q.z, 1, 0, 0, 0, 0);
            }
        }
        world.spawnParticles(CORE, to.x, to.y, to.z, 2, 0.03, 0.03, 0.03, 0);
        world.spawnParticles(ParticleTypes.WITCH, to.x, to.y, to.z, 1, 0.05, 0.05, 0.05, 0);
        if (age % 3 == 0) {
            world.spawnParticles(ParticleTypes.END_ROD, from.x, from.y, from.z, 0, -dir.x * 0.05, -dir.y * 0.05, -dir.z * 0.05, 1.0);
        }
    }

    /** A spinning reticle over whatever the bolt is hunting. */
    private static void renderLockOn(ServerWorld world, LivingEntity target, int age) {
        if (age % 2 != 0) return;
        Vec3d c = target.getPos().add(0, target.getHeight() + 0.5, 0);
        double r = Math.max(0.5, target.getWidth() * 0.8);
        double spin = age * 0.3;
        for (int i = 0; i < 4; i++) {
            double a = spin + i * Math.PI / 2.0;
            // four chevrons pointing inward
            for (int k = 0; k < 3; k++) {
                double off = (k - 1) * 0.12;
                double ax = Math.cos(a + off) * (r - Math.abs(off) * 0.8);
                double az = Math.sin(a + off) * (r - Math.abs(off) * 0.8);
                world.spawnParticles(SIGIL, c.x + ax, c.y, c.z + az, 1, 0, 0, 0, 0);
            }
        }
    }

    /** A small rune ring flashes at the staff as the bolt leaves. */
    private static void launchSigil(ServerWorld world, Vec3d at, Vec3d dir) {
        Vec3d helper = Math.abs(dir.y) > 0.9 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
        Vec3d u = dir.crossProduct(helper).normalize();
        Vec3d v = dir.crossProduct(u).normalize();
        for (int i = 0; i < 20; i++) {
            double a = i * Math.PI * 2.0 / 20;
            Vec3d p = at.add(u.multiply(Math.cos(a) * 0.45)).add(v.multiply(Math.sin(a) * 0.45));
            world.spawnParticles(SIGIL, p.x, p.y, p.z, 1, 0, 0, 0, 0);
        }
        world.spawnParticles(ParticleTypes.ENCHANT, at.x, at.y, at.z, 12, 0.2, 0.2, 0.2, 0.5);
        world.spawnParticles(ParticleTypes.WITCH, at.x, at.y, at.z, 6, 0.1, 0.1, 0.1, 0.05);
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
        net.minecraft.util.math.random.Random rand = world.getRandom();
        for (int i = 0; i < 24; i++) {
            double z = rand.nextDouble() * 2 - 1, th = rand.nextDouble() * Math.PI * 2, s = Math.sqrt(1 - z * z);
            double speed = 0.15 + rand.nextDouble() * 0.2;
            world.spawnParticles(TRAIL, where.x, where.y, where.z, 0, s * Math.cos(th) * speed, z * speed, s * Math.sin(th) * speed, 1.0);
        }
        world.spawnParticles(ParticleTypes.WITCH, where.x, where.y, where.z, 15, 0.25, 0.25, 0.25, 0.1);
        world.spawnParticles(ParticleTypes.ENCHANTED_HIT, where.x, where.y, where.z, 10, 0.2, 0.2, 0.2, 0.3);
        world.spawnParticles(ParticleTypes.END_ROD, where.x, where.y, where.z, 5, 0.1, 0.1, 0.1, 0.08);
        world.playSound(null, BlockPos.ofFloored(where),
                SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.2f, 1.2f);
        world.playSound(null, BlockPos.ofFloored(where),
                SoundEvents.BLOCK_AMETHYST_CLUSTER_BREAK, SoundCategory.PLAYERS, 0.9f, 1.4f);
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
