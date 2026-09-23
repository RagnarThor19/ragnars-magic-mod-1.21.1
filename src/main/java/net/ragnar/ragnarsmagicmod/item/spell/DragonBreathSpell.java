package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
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
 * Breathe like the Ender Dragon.
 *  1. Inhale: violet energy is drawn into your mouth while the dragon's growl builds.
 *  2. Breath: a roaring cone of dragon's breath pours out wherever you look for 3 seconds. It
 *     stops at walls, burns through armor, and withers what it touches.
 *  3. Where it washes over the ground it leaves smouldering pools that keep burning.
 */
public class DragonBreathSpell implements Spell {
    // --- Timing ---
    private static final int INHALE_TICKS = 12;
    private static final int BREATH_TICKS = 60;

    // --- Cone ---
    private static final double RANGE = 20.0;
    private static final double MOUTH_OFFSET = 0.8;
    private static final double MOUTH_RADIUS = 0.3;
    private static final double END_RADIUS = 4.0;

    // --- Damage ---
    private static final int HIT_EVERY = 10;         // matches vanilla hurt immunity, so every pulse lands
    private static final float BREATH_DAMAGE = 8.0f;   // magic: ignores armor
    private static final double PUSH = 0.12;

    // --- Pools ---
    private static final int POOL_EVERY = 12;
    private static final int MAX_POOLS = 5;
    private static final double POOL_RADIUS = 2.5;
    private static final int POOL_TICKS = 100;
    private static final float POOL_DAMAGE = 4.0f;

    private static final DustParticleEffect VIOLET = new DustParticleEffect(new Vector3f(0.75f, 0.2f, 0.95f), 1.4f);
    private static final DustParticleEffect PINK = new DustParticleEffect(new Vector3f(1.0f, 0.45f, 0.9f), 0.9f);

    private static final class Breath {
        final ServerWorld world;
        final UUID owner;
        final Map<Integer, Integer> lastHit = new HashMap<>();
        int age = 0;
        int pools = 0;

        Breath(ServerWorld world, UUID owner) {
            this.world = world;
            this.owner = owner;
        }
    }

    private static final class Pool {
        final ServerWorld world;
        final Vec3d center;
        final UUID owner;
        int age = 0;

        Pool(ServerWorld world, Vec3d center, UUID owner) {
            this.world = world;
            this.center = center;
            this.owner = owner;
        }
    }

    private static final List<Breath> BREATHS = new ArrayList<>();
    private static final List<Pool> POOLS = new ArrayList<>();
    private static boolean registered = false;

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            Iterator<Breath> it = BREATHS.iterator();
            while (it.hasNext()) {
                Breath b = it.next();
                if (b.world == world && !tickBreath(b)) it.remove();
            }
            Iterator<Pool> pools = POOLS.iterator();
            while (pools.hasNext()) {
                Pool p = pools.next();
                if (p.world == world && !tickPool(p)) pools.remove();
            }
        });
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        ensureRegistered();
        BREATHS.add(new Breath((ServerWorld) world, player.getUuid()));
        world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.PLAYERS, 1.6f, 1.2f);
        world.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_END_GATEWAY_SPAWN, SoundCategory.PLAYERS, 0.6f, 1.6f);
        return true;
    }

    // ---------------------------------------------------------------------
    // Breath
    // ---------------------------------------------------------------------

    /** Returns false once the breath is over. */
    private static boolean tickBreath(Breath b) {
        ServerWorld world = b.world;
        PlayerEntity owner = world.getPlayerByUuid(b.owner);
        if (owner == null || !owner.isAlive()) return false;
        b.age++;

        Vec3d dir = owner.getRotationVector().normalize();
        Vec3d mouth = owner.getEyePos().add(dir.multiply(MOUTH_OFFSET)).add(0, -0.15, 0);

        if (b.age <= INHALE_TICKS) {
            inhale(world, mouth, b.age);
            return true;
        }
        int breathAge = b.age - INHALE_TICKS;
        if (breathAge > BREATH_TICKS) {
            world.playSound(null, mouth.x, mouth.y, mouth.z, SoundEvents.ENTITY_ENDER_DRAGON_AMBIENT, SoundCategory.PLAYERS, 0.8f, 1.4f);
            world.spawnParticles(ParticleTypes.DRAGON_BREATH, mouth.x, mouth.y, mouth.z, 15, 0.2, 0.2, 0.2, 0.03);
            return false;
        }
        if (breathAge == 1) {
            world.playSound(null, mouth.x, mouth.y, mouth.z, SoundEvents.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.PLAYERS, 2.0f, 0.8f);
            owner.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, BREATH_TICKS, 0, false, false, false));
        }

        // The breath is cut short by walls
        BlockHitResult wall = world.raycast(new RaycastContext(mouth, mouth.add(dir.multiply(RANGE)),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, owner));
        double reach = wall.getType() == HitResult.Type.BLOCK ? mouth.distanceTo(wall.getPos()) : RANGE;

        renderStream(world, mouth, dir, reach, breathAge == 1);
        if (wall.getType() == HitResult.Type.BLOCK) splash(world, wall.getPos(), dir);
        burnCone(b, owner, mouth, dir, reach);

        // Leave smouldering pools where the breath washes over the ground
        if (breathAge % POOL_EVERY == 0 && b.pools < MAX_POOLS) {
            Vec3d ground = findGround(world, owner, mouth, dir, reach);
            if (ground != null) {
                POOLS.add(new Pool(world, ground, b.owner));
                b.pools++;
                world.playSound(null, ground.x, ground.y, ground.z, SoundEvents.ENTITY_DRAGON_FIREBALL_EXPLODE, SoundCategory.PLAYERS, 0.9f, 0.9f);
            }
        }

        if (breathAge % 6 == 1) {
            world.playSound(null, mouth.x, mouth.y, mouth.z, SoundEvents.ENTITY_ENDER_DRAGON_SHOOT, SoundCategory.PLAYERS, 1.2f, 0.55f + world.random.nextFloat() * 0.2f);
        }
        if (breathAge % 20 == 10) {
            world.playSound(null, mouth.x, mouth.y, mouth.z, SoundEvents.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.PLAYERS, 0.6f, 1.5f);
        }
        return true;
    }

    /** Violet motes spiralling into the mouth. */
    private static void inhale(ServerWorld world, Vec3d mouth, int age) {
        Random rand = world.random;
        for (int i = 0; i < 10; i++) {
            Vec3d dirOut = randomUnit(rand);
            double dist = 2.0 + rand.nextDouble() * 1.5;
            Vec3d p = mouth.add(dirOut.multiply(dist));
            Vec3d v = dirOut.multiply(-dist / 8.0);
            world.spawnParticles(i % 2 == 0 ? ParticleTypes.REVERSE_PORTAL : ParticleTypes.DRAGON_BREATH, p.x, p.y, p.z, 0, v.x, v.y, v.z, 1.0);
        }
        world.spawnParticles(VIOLET, mouth.x, mouth.y, mouth.z, 2 + age / 3, 0.08, 0.08, 0.08, 0);
    }

    /** Particles launched out along the cone so the breath visibly pours out and rolls forward. */
    private static void renderStream(ServerWorld world, Vec3d mouth, Vec3d dir, double reach, boolean firstFrame) {
        Random rand = world.random;
        Vec3d[] basis = perpendicularBasis(dir);
        double spreadSlope = (END_RADIUS - MOUTH_RADIUS) / RANGE;

        for (int i = 0; i < 36; i++) {
            // Random direction inside the cone, launched with enough speed to carry to the reach
            double a = rand.nextDouble() * Math.PI * 2.0;
            double r = Math.sqrt(rand.nextDouble()) * spreadSlope;
            Vec3d launch = dir.add(basis[0].multiply(Math.cos(a) * r)).add(basis[1].multiply(Math.sin(a) * r)).normalize();
            double speed = (0.25 + rand.nextDouble() * 0.65) * (reach / RANGE);
            Vec3d v = launch.multiply(speed);
            Vec3d p = mouth.add(basis[0].multiply(Math.cos(a) * MOUTH_RADIUS * rand.nextDouble()))
                    .add(basis[1].multiply(Math.sin(a) * MOUTH_RADIUS * rand.nextDouble()));
            world.spawnParticles(ParticleTypes.DRAGON_BREATH, p.x, p.y, p.z, 0, v.x, v.y, v.z, 1.0);
            if (i % 4 == 0) world.spawnParticles(ParticleTypes.PORTAL, p.x, p.y, p.z, 0, v.x * 1.4, v.y * 1.4, v.z * 1.4, 1.0);
            if (i % 9 == 0) world.spawnParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 0, v.x * 0.8, v.y * 0.8, v.z * 0.8, 1.0);
        }

        // Body of the cone: coloured haze so it reads as a solid torrent straight away
        int body = firstFrame ? 60 : 16;
        for (int i = 0; i < body; i++) {
            double d = Math.sqrt(rand.nextDouble()) * reach;
            double radius = MOUTH_RADIUS + spreadSlope * d;
            double a = rand.nextDouble() * Math.PI * 2.0;
            double rr = Math.sqrt(rand.nextDouble()) * radius;
            Vec3d p = mouth.add(dir.multiply(d)).add(basis[0].multiply(Math.cos(a) * rr)).add(basis[1].multiply(Math.sin(a) * rr));
            world.spawnParticles(i % 3 == 0 ? PINK : VIOLET, p.x, p.y, p.z, 0, dir.x * 0.2, dir.y * 0.2, dir.z * 0.2, 1.0);
        }
    }

    /** Breath hitting a wall fans out across it. */
    private static void splash(ServerWorld world, Vec3d at, Vec3d dir) {
        Random rand = world.random;
        for (int i = 0; i < 8; i++) {
            Vec3d v = randomUnit(rand).multiply(0.2).subtract(dir.multiply(0.1));
            world.spawnParticles(ParticleTypes.DRAGON_BREATH, at.x, at.y, at.z, 0, v.x, v.y, v.z, 1.0);
        }
    }

    /** Damages everything inside the cone that the breath can actually reach. */
    private static void burnCone(Breath b, PlayerEntity owner, Vec3d mouth, Vec3d dir, double reach) {
        ServerWorld world = b.world;
        Box box = new Box(mouth, mouth.add(dir.multiply(reach))).expand(END_RADIUS + 1.0);
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, box, e -> e.isAlive() && e != owner && !e.isSpectator())) {
            Vec3d c = e.getBoundingBox().getCenter();
            Vec3d rel = c.subtract(mouth);
            double along = rel.dotProduct(dir);
            if (along < 0 || along > reach + e.getWidth()) continue;
            double allowed = MOUTH_RADIUS + (END_RADIUS - MOUTH_RADIUS) * (along / RANGE) + e.getWidth() * 0.5;
            if (rel.subtract(dir.multiply(along)).lengthSquared() > allowed * allowed) continue;
            // No burning through walls
            if (world.raycast(new RaycastContext(mouth, c, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, owner)).getType() == HitResult.Type.BLOCK) continue;

            Integer last = b.lastHit.get(e.getId());
            if (last != null && b.age - last < HIT_EVERY) continue;
            b.lastHit.put(e.getId(), b.age);

            e.damage(world.getDamageSources().indirectMagic(owner, owner), BREATH_DAMAGE);
            e.addStatusEffect(new StatusEffectInstance(StatusEffects.WITHER, 60, 0));
            e.addVelocity(dir.x * PUSH, 0.02, dir.z * PUSH);
            e.velocityModified = true;
            world.spawnParticles(ParticleTypes.DRAGON_BREATH, c.x, c.y, c.z, 8, e.getWidth() * 0.4, e.getHeight() * 0.3, e.getWidth() * 0.4, 0.02);
        }
    }

    /** Ground under the breath: where it hits the floor, or the floor under its far end. */
    private static Vec3d findGround(ServerWorld world, PlayerEntity owner, Vec3d mouth, Vec3d dir, double reach) {
        Vec3d far = mouth.add(dir.multiply(reach * (0.55 + world.random.nextDouble() * 0.4)));
        BlockHitResult down = world.raycast(new RaycastContext(far, far.add(0, -6, 0),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, owner));
        return down.getType() == HitResult.Type.BLOCK ? down.getPos() : null;
    }

    // ---------------------------------------------------------------------
    // Lingering pools
    // ---------------------------------------------------------------------

    private static boolean tickPool(Pool p) {
        p.age++;
        ServerWorld world = p.world;
        Random rand = world.random;
        double fade = 1.0 - p.age / (double) POOL_TICKS;
        double r = POOL_RADIUS * (0.6 + 0.4 * fade);

        for (int i = 0; i < 6; i++) {
            double a = rand.nextDouble() * Math.PI * 2.0;
            double d = Math.sqrt(rand.nextDouble()) * r;
            world.spawnParticles(ParticleTypes.DRAGON_BREATH, p.center.x + Math.cos(a) * d, p.center.y + 0.15, p.center.z + Math.sin(a) * d,
                    0, 0, 0.03, 0, 1.0);
        }
        if (p.age % 3 == 0) {
            double a = rand.nextDouble() * Math.PI * 2.0;
            world.spawnParticles(VIOLET, p.center.x + Math.cos(a) * r, p.center.y + 0.1, p.center.z + Math.sin(a) * r, 1, 0, 0, 0, 0);
        }

        if (p.age % HIT_EVERY == 0) {
            PlayerEntity owner = world.getPlayerByUuid(p.owner);
            Box area = new Box(p.center, p.center).expand(r, 1.5, r);
            for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, area, e -> e.isAlive() && !e.getUuid().equals(p.owner) && !e.isSpectator())) {
                double dx = e.getX() - p.center.x, dz = e.getZ() - p.center.z;
                if (dx * dx + dz * dz > r * r) continue;
                e.damage(owner != null ? world.getDamageSources().indirectMagic(owner, owner) : world.getDamageSources().dragonBreath(), POOL_DAMAGE);
            }
        }
        return p.age < POOL_TICKS;
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

    private static Vec3d[] perpendicularBasis(Vec3d axis) {
        Vec3d helper = Math.abs(axis.y) < 0.9 ? new Vec3d(0, 1, 0) : new Vec3d(1, 0, 0);
        Vec3d a = axis.crossProduct(helper).normalize();
        Vec3d b = axis.crossProduct(a).normalize();
        return new Vec3d[]{a, b};
    }
}
