package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Hitscan lightning: the instant you cast, a forked bolt cracks from the staff to whatever is under
 * your crosshair (checked against real hitboxes, point blank included). The bolt flickers for a few
 * ticks like real lightning and the target is left crackling.
 */
public final class ZapSpell implements Spell {
    private static final double RANGE = 32.0;
    private static final float DAMAGE = 7.5f;       // three zaps kill a 20 HP mob, even through light armor
    private static final double KNOCK = 0.45;
    private static final double KNOCK_UP = 0.15;

    // Bolt shape
    private static final double SEGMENT_LENGTH = 1.1;
    private static final double JITTER = 0.35;
    private static final double POINTS_PER_BLOCK = 7.0;
    private static final int FLICKERS = 2;          // extra re-strikes after the first frame
    private static final int FLICKER_GAP = 2;

    private static final DustParticleEffect CORE = new DustParticleEffect(new Vector3f(0.92f, 0.97f, 1.0f), 0.55f);
    private static final DustParticleEffect GLOW = new DustParticleEffect(new Vector3f(0.45f, 0.7f, 1.0f), 0.9f);
    private static final DustParticleEffect BRANCH = new DustParticleEffect(new Vector3f(0.6f, 0.8f, 1.0f), 0.4f);

    private record Flicker(ServerWorld world, Vec3d from, Vec3d to, int ticksLeft, int remaining) {}

    private static final List<Flicker> FLICKERING = new ArrayList<>();
    private static boolean registered = false;

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            List<Flicker> next = new ArrayList<>();
            Iterator<Flicker> it = FLICKERING.iterator();
            while (it.hasNext()) {
                Flicker f = it.next();
                if (f.world() != world) continue;
                it.remove();
                if (f.ticksLeft() > 1) {
                    next.add(new Flicker(f.world(), f.from(), f.to(), f.ticksLeft() - 1, f.remaining()));
                    continue;
                }
                drawBolt(world, f.from(), f.to(), false);
                if (f.remaining() > 1) next.add(new Flicker(f.world(), f.from(), f.to(), FLICKER_GAP, f.remaining() - 1));
            }
            FLICKERING.addAll(next);
        });
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return true;
        ensureRegistered();
        ServerWorld sw = (ServerWorld) world;

        Vec3d eye = player.getEyePos();
        Vec3d look = player.getRotationVec(1.0f).normalize();
        Vec3d reach = eye.add(look.multiply(RANGE));

        // Hitscan: first block, then the nearest hitbox in front of it
        BlockHitResult blockHit = world.raycast(new RaycastContext(eye, reach,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
        Vec3d end = blockHit.getType() == HitResult.Type.MISS ? reach : blockHit.getPos();
        EntityHitResult entityHit = ProjectileUtil.raycast(player, eye, end,
                new Box(eye, end).expand(1.0),
                e -> e instanceof LivingEntity && e.isAlive() && !e.isSpectator() && e.canHit(),
                eye.squaredDistanceTo(end));

        Entity target = entityHit != null ? entityHit.getEntity() : null;
        Vec3d hitPos = target != null ? entityHit.getPos() : end;
        if (target != null) {
            // Aim the visible bolt at the middle of the body rather than the edge of the hitbox
            hitPos = target.getBoundingBox().getCenter().lerp(entityHit.getPos(), 0.5);
        }

        // The bolt leaves from the staff, slightly below and to the right of the eyes
        Vec3d right = look.crossProduct(new Vec3d(0, 1, 0));
        right = right.lengthSquared() > 1.0e-4 ? right.normalize() : new Vec3d(1, 0, 0);
        Vec3d from = eye.add(look.multiply(0.8)).add(right.multiply(0.35)).add(0, -0.35, 0);

        drawBolt(sw, from, hitPos, true);
        FLICKERING.add(new Flicker(sw, from, hitPos, FLICKER_GAP, FLICKERS));
        impact(sw, hitPos, target != null);

        world.playSound(null, player.getBlockPos(), net.ragnar.ragnarsmagicmod.sound.ModSoundEvents.ZAP_CAST, SoundCategory.PLAYERS, 1.0f, 1.0f);
        world.playSound(null, hitPos.x, hitPos.y, hitPos.z, SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.PLAYERS, 0.9f, 1.7f);
        world.playSound(null, hitPos.x, hitPos.y, hitPos.z, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.PLAYERS, 0.25f, 2.0f);

        if (target instanceof LivingEntity le) {
            le.damage(world.getDamageSources().playerAttack(player), DAMAGE);
            le.addVelocity(look.x * KNOCK, KNOCK_UP, look.z * KNOCK);
            le.velocityModified = true;
            // Left crackling
            Vec3d c = le.getBoundingBox().getCenter();
            sw.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, 25, le.getWidth() * 0.5, le.getHeight() * 0.4, le.getWidth() * 0.5, 0.3);
        }
        return true;
    }

    private static void impact(ServerWorld world, Vec3d p, boolean hitEntity) {
        world.spawnParticles(ParticleTypes.FLASH, p.x, p.y, p.z, 1, 0, 0, 0, 0);
        world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, p.x, p.y, p.z, 20, 0.15, 0.15, 0.15, 0.45);
        world.spawnParticles(GLOW, p.x, p.y, p.z, 10, 0.2, 0.2, 0.2, 0.0);
        if (!hitEntity) {
            world.spawnParticles(ParticleTypes.SMOKE, p.x, p.y, p.z, 6, 0.1, 0.1, 0.1, 0.02);
        }
    }

    /** A jagged main channel with a few forks. {@code full} adds the brighter first-frame glow. */
    private static void drawBolt(ServerWorld world, Vec3d from, Vec3d to, boolean full) {
        Random rand = world.random;
        List<Vec3d> nodes = jaggedPath(rand, from, to, JITTER);
        for (int i = 1; i < nodes.size(); i++) {
            traceLine(world, nodes.get(i - 1), nodes.get(i), CORE, POINTS_PER_BLOCK);
            if (full) traceLine(world, nodes.get(i - 1), nodes.get(i), GLOW, POINTS_PER_BLOCK * 0.35);
        }

        // Forks: short offshoots from random joints, pointing roughly along the bolt
        Vec3d dir = to.subtract(from);
        double length = dir.length();
        if (length < 1.0e-3) return;
        dir = dir.multiply(1.0 / length);
        int forks = Math.min(4, (int) (length / 5.0) + 1);
        for (int f = 0; f < forks && nodes.size() > 3; f++) {
            Vec3d base = nodes.get(1 + rand.nextInt(nodes.size() - 2));
            Vec3d spread = new Vec3d(rand.nextDouble() - 0.5, rand.nextDouble() - 0.5, rand.nextDouble() - 0.5).normalize();
            Vec3d tip = base.add(dir.multiply(0.8 + rand.nextDouble() * 1.2)).add(spread.multiply(0.8 + rand.nextDouble()));
            List<Vec3d> fork = jaggedPath(rand, base, tip, JITTER * 0.6);
            for (int i = 1; i < fork.size(); i++) traceLine(world, fork.get(i - 1), fork.get(i), BRANCH, POINTS_PER_BLOCK * 0.7);
            world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, tip.x, tip.y, tip.z, 1, 0, 0, 0, 0.05);
        }
    }

    private static List<Vec3d> jaggedPath(Random rand, Vec3d from, Vec3d to, double jitter) {
        Vec3d span = to.subtract(from);
        double length = span.length();
        int segments = Math.max(2, (int) Math.ceil(length / SEGMENT_LENGTH));
        Vec3d n = length > 1.0e-6 ? span.multiply(1.0 / length) : new Vec3d(0, 1, 0);
        Vec3d helper = Math.abs(n.y) > 0.9 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
        Vec3d u = n.crossProduct(helper).normalize();
        Vec3d v = n.crossProduct(u).normalize();

        List<Vec3d> nodes = new ArrayList<>(segments + 1);
        nodes.add(from);
        for (int i = 1; i < segments; i++) {
            double t = i / (double) segments;
            // Pinned at both ends, widest in the middle
            double envelope = Math.sin(Math.PI * t) * jitter;
            nodes.add(from.lerp(to, t)
                    .add(u.multiply((rand.nextDouble() * 2 - 1) * envelope))
                    .add(v.multiply((rand.nextDouble() * 2 - 1) * envelope)));
        }
        nodes.add(to);
        return nodes;
    }

    private static void traceLine(ServerWorld world, Vec3d a, Vec3d b, DustParticleEffect dust, double perBlock) {
        int steps = Math.max(1, (int) Math.ceil(a.distanceTo(b) * perBlock));
        for (int s = 0; s <= steps; s++) {
            Vec3d p = a.lerp(b, s / (double) steps);
            world.spawnParticles(dust, p.x, p.y, p.z, 1, 0, 0, 0, 0);
        }
    }
}
