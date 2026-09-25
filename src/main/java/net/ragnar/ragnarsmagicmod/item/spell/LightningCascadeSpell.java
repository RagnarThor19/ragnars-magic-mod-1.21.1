package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;
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
 * Summons a thunderstorm over the spot you aim at. A black storm cloud gathers overhead and the ground crackles,
 * then lightning hammers the area for a few seconds, seeking out the creatures under the cloud and arcing on to
 * anything standing near them. It ends with a colossal bolt at the centre that blasts everything away.
 * The bolts are the spell's own: they don't set the ground on fire, and every kill counts as yours.
 */
public class LightningCascadeSpell implements Spell {
    private static final double RANGE = 64.0;
    private static final double RADIUS = 10.0;
    private static final double CLOUD_HEIGHT = 14.0;

    private static final int WARN_TICKS = 50;             // cloud gathering, ground crackling
    private static final int STORM_TICKS = 60;            // lightning
    private static final int FINALE_TICK = WARN_TICKS + STORM_TICKS + 8;
    private static final int END_TICK = FINALE_TICK + 20;

    private static final double SEEK_CHANCE = 0.6;        // chance a bolt goes for a creature instead of the ground
    private static final double BOLT_RADIUS = 1.8;
    private static final float BOLT_DAMAGE = 6.0f;
    private static final int HIT_COOLDOWN = 12;           // ticks before the same creature can be struck again
    private static final double ARC_RANGE = 5.0;
    private static final int ARC_TARGETS = 2;
    private static final float ARC_DAMAGE = 3.0f;
    private static final double FINALE_RADIUS = 6.0;
    private static final float FINALE_DAMAGE = 14.0f;

    private static final DustParticleEffect STORM = new DustParticleEffect(new Vector3f(0.18f, 0.18f, 0.22f), 3.0f);
    private static final DustParticleEffect SPARK = new DustParticleEffect(new Vector3f(1.0f, 0.95f, 0.3f), 1.0f);
    private static final DustParticleEffect ARC = new DustParticleEffect(new Vector3f(0.6f, 0.85f, 1.0f), 0.8f);

    private static final class Storm {
        final ServerWorld world;
        final UUID owner;
        final Vec3d center;
        final Map<UUID, Integer> lastHit = new HashMap<>();
        int age = 0;

        Storm(ServerWorld world, UUID owner, Vec3d center) {
            this.world = world;
            this.owner = owner;
            this.center = center;
        }
    }

    private static final List<Storm> ACTIVE = new ArrayList<>();
    private static boolean registered = false;

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            Iterator<Storm> it = ACTIVE.iterator();
            while (it.hasNext()) {
                Storm s = it.next();
                if (s.world == world && !tick(s)) it.remove();
            }
        });
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        ensureRegistered();
        ServerWorld sw = (ServerWorld) world;

        Vec3d eye = player.getEyePos();
        Vec3d look = player.getRotationVector();
        BlockHitResult hit = sw.raycast(new RaycastContext(eye, eye.add(look.multiply(RANGE)),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.ANY, player));
        Vec3d aim = hit.getType() == HitResult.Type.BLOCK ? hit.getPos() : eye.add(look.multiply(16.0));
        Vec3d center = Vec3d.ofBottomCenter(ground(sw, aim.x, aim.z, aim.y + 4));

        ACTIVE.add(new Storm(sw, player.getUuid(), center));
        var horn = SoundEvents.GOAT_HORN_SOUNDS.get(5).value();
        sw.playSound(null, player.getBlockPos(), horn, SoundCategory.PLAYERS, 1.5f, 0.65f);
        sw.playSound(null, center.x, center.y, center.z, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.WEATHER, 3.0f, 0.5f);
        return true;
    }

    // ---------------------------------------------------------------------
    // Timeline
    // ---------------------------------------------------------------------

    /** Returns false once the storm has passed. */
    private static boolean tick(Storm s) {
        s.age++;
        ServerWorld world = s.world;
        Random rand = world.getRandom();

        drawCloud(s);

        if (s.age <= WARN_TICKS) {
            // The ground crackles and sparks climb toward the cloud
            for (int i = 0; i < 5; i++) {
                BlockPos base = randomGround(s, rand);
                double x = base.getX() + rand.nextDouble();
                double z = base.getZ() + rand.nextDouble();
                world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, x, base.getY() + 0.1, z, 2, 0.1, 0.05, 0.1, 0.1);
                for (int h = 0; h < 6; h++) {
                    world.spawnParticles(SPARK, x, base.getY() + 0.2 + h * 0.25 + (s.age % 5) * 0.05, z, 1, 0, 0, 0, 0);
                }
            }
            if (s.age % 15 == 0) {
                world.playSound(null, s.center.x, s.center.y + CLOUD_HEIGHT, s.center.z,
                        SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.WEATHER, 2.0f, 0.4f + rand.nextFloat() * 0.2f);
            }
            return true;
        }

        if (s.age <= WARN_TICKS + STORM_TICKS) {
            // One to three bolts a tick, most of them hunting for something alive
            int strikes = 1 + rand.nextInt(3);
            for (int i = 0; i < strikes; i++) {
                LivingEntity prey = rand.nextDouble() < SEEK_CHANCE ? pickPrey(s, rand) : null;
                Vec3d at = prey != null ? prey.getPos() : Vec3d.ofBottomCenter(randomGround(s, rand));
                bolt(s, at, BOLT_RADIUS, BOLT_DAMAGE, true);
            }
            return true;
        }

        if (s.age == FINALE_TICK) finale(s);
        return s.age < END_TICK;
    }

    private static void drawCloud(Storm s) {
        ServerWorld world = s.world;
        Random rand = world.getRandom();
        // Grows in during the warning, then hangs there until the end
        double grow = Math.min(1.0, s.age / (double) (WARN_TICKS * 0.6));
        double y = s.center.y + CLOUD_HEIGHT;
        int puffs = (int) (18 * grow) + 2;
        for (int i = 0; i < puffs; i++) {
            double r = RADIUS * 1.1 * grow * Math.sqrt(rand.nextDouble());
            double a = rand.nextDouble() * MathHelper.TAU;
            world.spawnParticles(STORM, s.center.x + Math.cos(a) * r, y + rand.nextDouble() * 1.5, s.center.z + Math.sin(a) * r,
                    1, 0.3, 0.2, 0.3, 0);
        }
        if (rand.nextInt(3) == 0) {
            world.spawnParticles(ParticleTypes.LARGE_SMOKE, s.center.x, y, s.center.z, 3, RADIUS * 0.6 * grow, 0.4, RADIUS * 0.6 * grow, 0.01);
        }
        if (s.age > WARN_TICKS / 2 && rand.nextInt(4) == 0) {
            // Flickers inside the cloud
            double r = RADIUS * Math.sqrt(rand.nextDouble());
            double a = rand.nextDouble() * MathHelper.TAU;
            world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, s.center.x + Math.cos(a) * r, y + 0.5, s.center.z + Math.sin(a) * r,
                    8, 0.5, 0.3, 0.5, 0.3);
        }
    }

    private static void finale(Storm s) {
        ServerWorld world = s.world;
        Vec3d c = s.center;
        // Several bolts on the same spot read as one enormous strike
        for (int i = 0; i < 5; i++) {
            Vec3d at = c.add((world.random.nextDouble() - 0.5) * 1.2, 0, (world.random.nextDouble() - 0.5) * 1.2);
            spawnBolt(s, at);
        }
        PlayerEntity owner = world.getPlayerByUuid(s.owner);
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, new Box(c, c).expand(FINALE_RADIUS, 4, FINALE_RADIUS),
                e -> e.isAlive() && !e.isSpectator() && !e.getUuid().equals(s.owner))) {
            Vec3d out = new Vec3d(e.getX() - c.x, 0, e.getZ() - c.z);
            double dist = out.length();
            if (dist > FINALE_RADIUS) continue;
            out = dist > 1.0e-3 ? out.multiply(1.0 / dist) : Vec3d.ZERO;
            e.timeUntilRegen = 0;
            e.damage(damageSource(world, owner), FINALE_DAMAGE * (float) (1.0 - 0.5 * dist / FINALE_RADIUS));
            e.addVelocity(out.x * 1.6, 0.7, out.z * 1.6);
            e.velocityModified = true;
            e.setOnFireFor(4);
        }
        world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y + 0.5, c.z, 1, 0, 0, 0, 0);
        world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y + 1, c.z, 200, 3.0, 1.5, 3.0, 0.8);
        world.spawnParticles(ParticleTypes.FLASH, c.x, c.y + 1, c.z, 3, 0.5, 0.5, 0.5, 0);
        // A ring of charge racing out along the ground
        for (int i = 0; i < 64; i++) {
            double a = i * MathHelper.TAU / 64;
            world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y + 0.2, c.z, 0, Math.cos(a), 0.05, Math.sin(a), 0.9);
        }
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.WEATHER, 8.0f, 0.5f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.WEATHER, 4.0f, 0.5f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 3.0f, 0.7f);
    }

    // ---------------------------------------------------------------------
    // Bolts
    // ---------------------------------------------------------------------

    /** A bolt at {@code at} that hurts creatures within {@code radius}, optionally arcing on to their neighbours. */
    private static void bolt(Storm s, Vec3d at, double radius, float damage, boolean arcs) {
        ServerWorld world = s.world;
        spawnBolt(s, at);
        PlayerEntity owner = world.getPlayerByUuid(s.owner);
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, new Box(at, at).expand(radius, 3, radius),
                e -> e.isAlive() && !e.isSpectator() && !e.getUuid().equals(s.owner))) {
            if (!canHit(s, e)) continue;
            shock(s, owner, e, damage);
            e.setOnFireFor(3);
            if (arcs) arcFrom(s, owner, e);
        }
        world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y + 0.2, at.z, 15, 0.6, 0.2, 0.6, 0.4);
    }

    /** Chains from a struck creature to the nearest others around it. */
    private static void arcFrom(Storm s, PlayerEntity owner, LivingEntity from) {
        List<LivingEntity> near = s.world.getEntitiesByClass(LivingEntity.class, from.getBoundingBox().expand(ARC_RANGE),
                e -> e != from && e.isAlive() && !e.isSpectator() && !e.getUuid().equals(s.owner));
        near.sort((a, b) -> Double.compare(a.squaredDistanceTo(from), b.squaredDistanceTo(from)));
        int arcs = 0;
        for (LivingEntity e : near) {
            if (arcs >= ARC_TARGETS) break;
            if (!canHit(s, e)) continue;
            drawArc(s.world, from.getBoundingBox().getCenter(), e.getBoundingBox().getCenter());
            shock(s, owner, e, ARC_DAMAGE);
            arcs++;
        }
    }

    private static void shock(Storm s, PlayerEntity owner, LivingEntity e, float damage) {
        s.lastHit.put(e.getUuid(), s.age);
        e.timeUntilRegen = 0;
        e.damage(damageSource(s.world, owner), damage);
    }

    private static boolean canHit(Storm s, LivingEntity e) {
        Integer last = s.lastHit.get(e.getUuid());
        return last == null || s.age - last >= HIT_COOLDOWN;
    }

    private static net.minecraft.entity.damage.DamageSource damageSource(ServerWorld world, PlayerEntity owner) {
        return owner != null ? world.getDamageSources().indirectMagic(owner, owner) : world.getDamageSources().lightningBolt();
    }

    /** Vanilla's bolt look and thunder, without its fire or damage (the spell deals its own). */
    private static void spawnBolt(Storm s, Vec3d at) {
        LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(s.world);
        if (bolt == null) return;
        bolt.setCosmetic(true);
        bolt.refreshPositionAfterTeleport(at.x, at.y, at.z);
        s.world.spawnEntity(bolt);
    }

    /** A jagged blue-white line of sparks between two points. */
    private static void drawArc(ServerWorld world, Vec3d from, Vec3d to) {
        Vec3d path = to.subtract(from);
        int steps = Math.max(4, (int) (path.length() * 4));
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            double jitter = Math.sin(Math.PI * t) * 0.35;
            Vec3d p = from.add(path.multiply(t)).add(
                    (world.random.nextDouble() - 0.5) * jitter, (world.random.nextDouble() - 0.5) * jitter, (world.random.nextDouble() - 0.5) * jitter);
            world.spawnParticles(ARC, p.x, p.y, p.z, 1, 0, 0, 0, 0);
            if (i % 3 == 0) world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, p.x, p.y, p.z, 1, 0, 0, 0, 0);
        }
        world.playSound(null, to.x, to.y, to.z, SoundEvents.BLOCK_BEACON_POWER_SELECT, SoundCategory.PLAYERS, 0.6f, 2.0f);
    }

    // ---------------------------------------------------------------------
    // Picking spots
    // ---------------------------------------------------------------------

    private static LivingEntity pickPrey(Storm s, Random rand) {
        List<LivingEntity> prey = s.world.getEntitiesByClass(LivingEntity.class,
                new Box(s.center, s.center).expand(RADIUS, 12, RADIUS),
                e -> e.isAlive() && !e.isSpectator() && !e.getUuid().equals(s.owner) && canHit(s, e)
                        && horizontalDistance(e.getPos(), s.center) <= RADIUS);
        return prey.isEmpty() ? null : prey.get(rand.nextInt(prey.size()));
    }

    private static BlockPos randomGround(Storm s, Random rand) {
        double r = RADIUS * Math.sqrt(rand.nextDouble());
        double a = rand.nextDouble() * MathHelper.TAU;
        return ground(s.world, s.center.x + Math.cos(a) * r, s.center.z + Math.sin(a) * r, s.center.y + 6);
    }

    /** The first open spot above solid ground at (x, z), searching down from {@code fromY}, or the heightmap top. */
    private static BlockPos ground(ServerWorld world, double x, double z, double fromY) {
        BlockPos pos = BlockPos.ofFloored(x, fromY, z);
        for (int i = 0; i < 16; i++, pos = pos.down()) {
            if (world.getBlockState(pos).isAir() && !world.getBlockState(pos.down()).getCollisionShape(world, pos.down()).isEmpty()) {
                return pos;
            }
        }
        return world.getTopPosition(Heightmap.Type.MOTION_BLOCKING, BlockPos.ofFloored(x, fromY, z));
    }

    private static double horizontalDistance(Vec3d a, Vec3d b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
