package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.TameableEntity;
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
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.minecraft.world.WorldEvents;
import net.ragnar.ragnarsmagicmod.entity.IllusionEntity;
import net.ragnar.ragnarsmagicmod.network.DimensionSplitPayload;
import net.ragnar.ragnarsmagicmod.util.SplitPattern;
import org.joml.Vector3f;

import java.util.*;

/**
 * Tome of Dimension Split. A faint spark flies out; where it lands, the world stops, goes black, and is
 * cut apart by a storm of blood-red slashes - then everything they crossed is severed at once and a rift
 * hangs open in the air before sealing itself.
 */
public class DimensionSplitSpell implements Spell {
    // --- the spark ---
    private static final double SPARK_SPEED = 3.0;
    private static final double SPARK_RANGE = 80.0;
    private static final double HIT_MARGIN = 0.4;

    // --- the split ---
    private static final double AREA_RADIUS = 7.0;
    private static final float SLASH_DAMAGE = 6.0f;        // every slash that passes through you
    private static final float SEVER_DIRECT_DAMAGE = 100.0f; // the spark's own target
    private static final float SEVER_AREA_DAMAGE = 40.0f;  // everything else caught in it (falls off to half)
    private static final double CARVE_RADIUS = 9.0;
    private static final double CRATER_RADIUS = 2.6;
    private static final float MAX_CARVE_HARDNESS = 50.0f; // obsidian and up survive

    private static final DustParticleEffect SPARK = new DustParticleEffect(new Vector3f(1.0f, 0.15f, 0.12f), 0.5f);
    private static final DustParticleEffect BLOOD = new DustParticleEffect(new Vector3f(0.85f, 0.02f, 0.02f), 1.6f);
    private static final DustParticleEffect VOID = new DustParticleEffect(new Vector3f(0.02f, 0.0f, 0.0f), 2.5f);

    private static final class Spark {
        final ServerWorld world;
        final UUID owner;
        final Vec3d dir;
        Vec3d pos;
        double travelled = 0;

        Spark(ServerWorld world, UUID owner, Vec3d pos, Vec3d dir) {
            this.world = world;
            this.owner = owner;
            this.pos = pos;
            this.dir = dir;
        }
    }

    private static final class Split {
        final ServerWorld world;
        final UUID owner;
        final Vec3d center;
        final boolean terrain;
        final LivingEntity direct;
        final List<SplitPattern.Slash> slashes;
        final Set<LivingEntity> caught = new HashSet<>();
        int age = 0;

        Split(ServerWorld world, UUID owner, Vec3d center, boolean terrain, LivingEntity direct, List<SplitPattern.Slash> slashes) {
            this.world = world;
            this.owner = owner;
            this.center = center;
            this.terrain = terrain;
            this.direct = direct;
            this.slashes = slashes;
        }
    }

    private static final List<Spark> SPARKS = new ArrayList<>();
    private static final List<Split> SPLITS = new ArrayList<>();
    private static boolean registered = false;

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            Iterator<Spark> sparks = SPARKS.iterator();
            List<Split> started = new ArrayList<>();
            while (sparks.hasNext()) {
                Spark s = sparks.next();
                if (s.world != world) continue;
                Split split = tickSpark(s);
                if (split != null || s.travelled >= SPARK_RANGE) sparks.remove();
                if (split != null) started.add(split);
            }
            SPLITS.addAll(started);

            Iterator<Split> splits = SPLITS.iterator();
            while (splits.hasNext()) {
                Split s = splits.next();
                if (s.world == world && !tickSplit(s)) splits.remove();
            }
        });
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        if (!(world instanceof ServerWorld sw)) return false;
        ensureRegistered();

        Vec3d dir = player.getRotationVector().normalize();
        Vec3d start = player.getEyePos().add(dir.multiply(0.6)).add(0, -0.15, 0);
        SPARKS.add(new Spark(sw, player.getUuid(), start, dir));

        // Almost nothing - that's the point
        sw.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.2f, 0.5f);
        sw.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 0.4f, 2.0f);
        sw.spawnParticles(ParticleTypes.ELECTRIC_SPARK, start.x, start.y, start.z, 6, 0.05, 0.05, 0.05, 0.15);
        return true;
    }

    // ---------------------------------------------------------------------
    // The spark
    // ---------------------------------------------------------------------

    /** Moves the spark one tick; returns the split it caused, if it landed. */
    private static Split tickSpark(Spark s) {
        ServerWorld world = s.world;
        PlayerEntity owner = world.getPlayerByUuid(s.owner);
        Vec3d from = s.pos;
        double step = Math.min(SPARK_SPEED, SPARK_RANGE - s.travelled);
        Vec3d to = from.add(s.dir.multiply(step));

        BlockHitResult wall = world.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, owner == null ? null : owner));
        Vec3d end = wall.getType() == HitResult.Type.BLOCK ? wall.getPos() : to;

        // Nearest creature along the path, before any wall
        LivingEntity hit = null;
        double best = Double.MAX_VALUE;
        for (Entity e : world.getOtherEntities(owner, new Box(from, end).expand(HIT_MARGIN + 1.0),
                e -> e instanceof LivingEntity && e.isAlive() && !e.isSpectator() && !(e instanceof IllusionEntity))) {
            Optional<Vec3d> at = e.getBoundingBox().expand(HIT_MARGIN).raycast(from, end);
            if (at.isPresent()) {
                double d = from.squaredDistanceTo(at.get());
                if (d < best) { best = d; hit = (LivingEntity) e; }
            }
        }

        // Faint trail: a thin red thread with the odd crackle
        double len = end.distanceTo(from);
        for (double d = 0; d < len; d += 0.35) {
            Vec3d p = from.add(s.dir.multiply(d));
            world.spawnParticles(SPARK, p.x, p.y, p.z, 1, 0, 0, 0, 0);
        }
        world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, end.x, end.y, end.z, 1, 0.02, 0.02, 0.02, 0.02);

        s.travelled += step;
        s.pos = to;

        if (hit != null) {
            return beginSplit(world, owner, s.owner, hit.getBoundingBox().getCenter(), false, hit, s.dir);
        }
        if (wall.getType() == HitResult.Type.BLOCK) {
            Vec3d center = wall.getPos().add(Vec3d.of(wall.getSide().getVector()).multiply(0.8));
            return beginSplit(world, owner, s.owner, center, true, null, s.dir);
        }
        if (s.travelled >= SPARK_RANGE) {
            // Nothing in reach: space itself splits where the spark gives out
            return beginSplit(world, owner, s.owner, to, false, null, s.dir);
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // The split
    // ---------------------------------------------------------------------

    private static Split beginSplit(ServerWorld world, PlayerEntity owner, UUID ownerId, Vec3d center, boolean terrain,
                                    LivingEntity direct, Vec3d sparkDir) {
        Vec3d view = owner != null ? owner.getEyePos().subtract(center) : sparkDir.negate();
        if (view.lengthSquared() < 1.0e-4) view = sparkDir.negate();
        view = view.normalize();
        long seed = world.getRandom().nextLong();

        Split split = new Split(world, ownerId, center, terrain, direct,
                SplitPattern.generate(center, view, seed));
        DimensionSplitPayload.broadcast(world, center, view, seed);

        // Impact: a hole in the sound, then the world cracks
        world.playSound(null, center.x, center.y, center.z, SoundEvents.ITEM_TRIDENT_THUNDER, SoundCategory.PLAYERS, 4.0f, 0.7f);
        world.playSound(null, center.x, center.y, center.z, SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 3.0f, 0.5f);
        world.playSound(null, center.x, center.y, center.z, SoundEvents.BLOCK_RESPAWN_ANCHOR_DEPLETE, SoundCategory.PLAYERS, 3.0f, 0.6f);
        world.spawnParticles(ParticleTypes.FLASH, center.x, center.y, center.z, 1, 0, 0, 0, 0);
        world.spawnParticles(VOID, center.x, center.y, center.z, 40, 0.8, 0.8, 0.8, 0);
        world.spawnParticles(ParticleTypes.SQUID_INK, center.x, center.y, center.z, 30, 0.2, 0.2, 0.2, 0.25);

        captureArea(split, owner);
        return split;
    }

    /** Everything in the area, apart from the caster and their own. */
    private static void captureArea(Split s, PlayerEntity owner) {
        Box area = new Box(s.center, s.center).expand(AREA_RADIUS);
        for (LivingEntity e : s.world.getEntitiesByClass(LivingEntity.class, area, e -> e.isAlive() && !e.isSpectator())) {
            if (e.squaredDistanceTo(s.center) > AREA_RADIUS * AREA_RADIUS && e != s.direct) continue;
            if (isFriendly(e, owner, s.owner)) continue;
            s.caught.add(e);
        }
        if (s.direct != null) s.caught.add(s.direct);
    }

    private static boolean isFriendly(LivingEntity e, PlayerEntity owner, UUID ownerId) {
        if (e.getUuid().equals(ownerId)) return true;
        if (e instanceof IllusionEntity) return true;
        if (e instanceof TameableEntity pet && ownerId.equals(pet.getOwnerUuid())) return true;
        return owner != null && e.isTeammate(owner);
    }

    /** Returns false once the split is over. */
    private static boolean tickSplit(Split s) {
        ServerWorld world = s.world;
        PlayerEntity owner = world.getPlayerByUuid(s.owner);
        int age = ++s.age;

        if (age < SplitPattern.SEVER) {
            // Time stops for everything caught in it
            for (LivingEntity e : s.caught) {
                if (!e.isAlive()) continue;
                e.setVelocity(Vec3d.ZERO);
                e.velocityModified = true;
                e.fallDistance = 0;
                if (e instanceof MobEntity mob) mob.getNavigation().stop();
                e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 3, 9, false, false, false));
            }
            // The air around the impact dims and trembles
            if (age % 2 == 0) {
                world.spawnParticles(VOID, s.center.x, s.center.y, s.center.z, 6, 2.0, 1.5, 2.0, 0);
            }
        }

        for (SplitPattern.Slash slash : s.slashes) {
            if (age == slash.start()) {
                float pitch = 0.5f + world.getRandom().nextFloat() * 0.35f;
                Vec3d m = slash.point(0.5);
                world.playSound(null, m.x, m.y, m.z, SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 2.5f, pitch);
                world.playSound(null, m.x, m.y, m.z, SoundEvents.ITEM_TRIDENT_RIPTIDE_1, SoundCategory.PLAYERS, 1.2f, 1.6f + pitch * 0.4f);
            }
            if (age == slash.landsAt()) cutThrough(s, slash, owner);
        }

        if (age == SplitPattern.SEVER) sever(s, owner);

        if (age > SplitPattern.SEVER && age < SplitPattern.RIFT_CLOSE) {
            // The rift drinks the light around it
            SplitPattern.Slash main = s.slashes.get(0);
            for (int i = 0; i < 3; i++) {
                Vec3d p = main.point(0.1 + world.getRandom().nextDouble() * 0.8);
                world.spawnParticles(ParticleTypes.REVERSE_PORTAL, p.x, p.y, p.z, 2, 0.8, 0.8, 0.8, 0.05);
            }
            if (age % 3 == 0) {
                Vec3d p = main.point(world.getRandom().nextDouble());
                world.spawnParticles(ParticleTypes.SQUID_INK, p.x, p.y, p.z, 1, 0.05, 0.05, 0.05, 0.02);
            }
            if (age == SplitPattern.SEVER + 2) {
                world.playSound(null, s.center.x, s.center.y, s.center.z, SoundEvents.BLOCK_PORTAL_AMBIENT, SoundCategory.PLAYERS, 2.0f, 0.5f);
            }
        }

        if (age == SplitPattern.RIFT_CLOSE) {
            world.playSound(null, s.center.x, s.center.y, s.center.z, SoundEvents.BLOCK_BEACON_DEACTIVATE, SoundCategory.PLAYERS, 3.0f, 0.5f);
            world.playSound(null, s.center.x, s.center.y, s.center.z, SoundEvents.BLOCK_END_PORTAL_FRAME_FILL, SoundCategory.PLAYERS, 2.0f, 0.5f);
        }
        if (age == SplitPattern.END) {
            world.spawnParticles(ParticleTypes.FLASH, s.center.x, s.center.y, s.center.z, 1, 0, 0, 0, 0);
            world.spawnParticles(ParticleTypes.SQUID_INK, s.center.x, s.center.y, s.center.z, 20, 0.3, 0.3, 0.3, 0.15);
            return false;
        }
        return true;
    }

    /** One slash has finished its sweep: everything it passed through takes a cut. */
    private static void cutThrough(Split s, SplitPattern.Slash slash, PlayerEntity owner) {
        ServerWorld world = s.world;
        for (LivingEntity e : s.caught) {
            if (!e.isAlive()) continue;
            Vec3d c = e.getBoundingBox().getCenter();
            double reach = e.getWidth() / 2 + e.getHeight() / 2 + 0.3;
            if (SplitPattern.distanceOutside(slash, c) > reach) continue;
            hurt(world, e, owner, SLASH_DAMAGE);
            world.spawnParticles(BLOOD, c.x, c.y, c.z, 10, 0.3, 0.4, 0.3, 0);
            world.spawnParticles(ParticleTypes.CRIT, c.x, c.y, c.z, 8, 0.3, 0.4, 0.3, 0.3);
        }
        // Sparks thrown off along the cut
        for (int i = 0; i <= 8; i++) {
            Vec3d p = slash.point(i / 8.0);
            world.spawnParticles(ParticleTypes.CRIT, p.x, p.y, p.z, 1, 0.1, 0.1, 0.1, 0.4);
        }
    }

    /** Everything lands at once. */
    private static void sever(Split s, PlayerEntity owner) {
        ServerWorld world = s.world;
        Vec3d c = s.center;

        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 4.0f, 0.6f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_WITHER_BREAK_BLOCK, SoundCategory.PLAYERS, 3.0f, 0.55f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.PLAYERS, 3.0f, 0.6f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ITEM_TRIDENT_THUNDER, SoundCategory.PLAYERS, 4.0f, 0.5f);

        world.spawnParticles(ParticleTypes.FLASH, c.x, c.y, c.z, 2, 0.5, 0.5, 0.5, 0);
        world.spawnParticles(ParticleTypes.SQUID_INK, c.x, c.y, c.z, 120, 0.6, 0.6, 0.6, 0.55);
        world.spawnParticles(BLOOD, c.x, c.y, c.z, 150, 3.0, 2.0, 3.0, 0);
        world.spawnParticles(ParticleTypes.CRIT, c.x, c.y, c.z, 80, 1.0, 1.0, 1.0, 1.2);
        if (s.terrain) world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y, c.z, 1, 0, 0, 0, 0);

        for (LivingEntity e : s.caught) {
            if (!e.isAlive()) continue;
            float damage;
            if (e == s.direct) {
                damage = SEVER_DIRECT_DAMAGE;
            } else {
                double dist = e.getBoundingBox().getCenter().distanceTo(c);
                damage = (float) (SEVER_AREA_DAMAGE * MathHelper.clampedLerp(1.0, 0.5, (dist - 2.0) / (AREA_RADIUS - 2.0)));
            }
            hurt(world, e, owner, damage);

            // Flung away from the cut
            Vec3d away = e.getPos().subtract(c);
            Vec3d push = away.lengthSquared() < 1.0e-4 ? new Vec3d(0, 1, 0) : away.normalize();
            double kb = e == s.direct ? 1.6 : 1.1;
            e.addVelocity(push.x * kb, 0.45 + Math.max(0, push.y) * 0.3, push.z * kb);
            e.velocityModified = true;
            Vec3d ec = e.getBoundingBox().getCenter();
            world.spawnParticles(BLOOD, ec.x, ec.y, ec.z, 25, 0.4, 0.5, 0.4, 0);
            world.spawnParticles(ParticleTypes.DAMAGE_INDICATOR, ec.x, ec.y, ec.z, 8, 0.3, 0.4, 0.3, 0.2);
        }

        if (s.terrain) carve(s);
    }

    private static void hurt(ServerWorld world, LivingEntity e, PlayerEntity owner, float amount) {
        e.timeUntilRegen = 0; // every cut lands
        DamageSource source = owner != null
                ? world.getDamageSources().indirectMagic(owner, owner)
                : world.getDamageSources().magic();
        e.damage(source, amount);
    }

    /** Cuts the slash shapes into the ground, plus a crater where the spark struck. */
    private static void carve(Split s) {
        ServerWorld world = s.world;
        Set<BlockPos> cut = new LinkedHashSet<>();

        BlockPos.iterateOutwards(BlockPos.ofFloored(s.center), (int) Math.ceil(CRATER_RADIUS), (int) Math.ceil(CRATER_RADIUS), (int) Math.ceil(CRATER_RADIUS))
                .forEach(p -> {
                    if (Vec3d.ofCenter(p).squaredDistanceTo(s.center) <= CRATER_RADIUS * CRATER_RADIUS) cut.add(p.toImmutable());
                });

        for (SplitPattern.Slash slash : s.slashes) {
            int steps = (int) Math.ceil(slash.length() / 0.4);
            for (int i = 0; i <= steps; i++) {
                double t = i / (double) steps;
                double hw = Math.max(0.5, slash.halfWidthAt(t) * 1.6);
                Vec3d p = slash.point(t);
                for (double o = -hw; o <= hw; o += 0.4) {
                    Vec3d q = p.add(slash.v().multiply(o));
                    if (q.squaredDistanceTo(s.center) <= CARVE_RADIUS * CARVE_RADIUS) cut.add(BlockPos.ofFloored(q));
                }
            }
        }

        int effects = 0;
        for (BlockPos pos : cut) {
            BlockState state = world.getBlockState(pos);
            if (state.isAir() || !state.getFluidState().isEmpty() || state.hasBlockEntity()) continue;
            float hardness = state.getHardness(world, pos);
            if (hardness < 0 || hardness >= MAX_CARVE_HARDNESS) continue;
            if (effects < 80 && world.getRandom().nextInt(3) == 0) {
                world.syncWorldEvent(WorldEvents.BLOCK_BROKEN, pos, Block.getRawIdFromState(state));
                effects++;
            }
            world.setBlockState(pos, net.minecraft.block.Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
    }
}
