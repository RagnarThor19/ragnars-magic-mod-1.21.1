package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.util.TempEntities;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Hurls a spinning chunk of ice. After {@link #BURST_DISTANCE} blocks (or sooner, if it hits something) it bursts
 * into a solid cube of real ice that entombs everything inside: {@link #DAMAGE} on impact, held in place while
 * the ice stands, and freeze damage ticking for a while after. The ice shatters on its own and puts back
 * whatever was there before.
 */
public class GlacierSpell implements Spell {
    private static final double SPEED = 1.5;
    private static final double BURST_DISTANCE = 18.0;
    private static final int CUBE_RADIUS = 2;             // a 5x5x5 block of ice
    private static final float DAMAGE = 8.0f;
    private static final int ENCASE_TICKS = 20 * 5;       // how long the ice stands and holds its victims
    private static final int CHILL_TICKS = 20 * 8;        // freeze damage keeps ticking this long after impact
    private static final int CHILL_INTERVAL = 20;
    private static final float CHILL_DAMAGE = 2.0f;
    private static final float SHARD_SCALE = 0.7f;
    private static final int PLACE_FLAGS = Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS;

    private static final class Shard {
        final ServerWorld world;
        final UUID owner;
        final DisplayEntity.BlockDisplayEntity display;
        final Vec3d dir;
        Vec3d pos;
        double travelled = 0;
        int age = 0;

        Shard(ServerWorld world, UUID owner, DisplayEntity.BlockDisplayEntity display, Vec3d pos, Vec3d dir) {
            this.world = world;
            this.owner = owner;
            this.display = display;
            this.pos = pos;
            this.dir = dir;
        }
    }

    private static final class Victim {
        final LivingEntity entity;
        final Vec3d pinnedAt;

        Victim(LivingEntity entity) {
            this.entity = entity;
            this.pinnedAt = entity.getPos();
        }
    }

    private static final class Tomb {
        final ServerWorld world;
        final BlockPos center;
        // What each ice block replaced, so it can be put back
        final Map<BlockPos, BlockState> replaced = new LinkedHashMap<>();
        final List<Victim> victims = new ArrayList<>();
        int age = 0;

        Tomb(ServerWorld world, BlockPos center) {
            this.world = world;
            this.center = center;
        }
    }

    private static final List<Shard> SHARDS = new ArrayList<>();
    private static final List<Tomb> TOMBS = new ArrayList<>();
    private static boolean registered = false;

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            Iterator<Shard> shards = SHARDS.iterator();
            while (shards.hasNext()) {
                Shard s = shards.next();
                if (s.world == world && !tickShard(s)) {
                    TempEntities.discard(s.display);
                    shards.remove();
                }
            }
            Iterator<Tomb> tombs = TOMBS.iterator();
            while (tombs.hasNext()) {
                Tomb t = tombs.next();
                if (t.world == world && !tickTomb(t)) tombs.remove();
            }
        });
        // Being inside the ice is the point; don't let it suffocate them as well
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !(source.isOf(DamageTypes.IN_WALL) && isEncased(entity)));
        // Never leave the ice behind in the world
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (Tomb t : TOMBS) melt(t);
            TOMBS.clear();
        });
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        ensureRegistered();
        ServerWorld sw = (ServerWorld) world;

        Vec3d dir = player.getRotationVector().normalize();
        Vec3d start = player.getEyePos().add(dir.multiply(1.0)).add(0, -0.2, 0);

        DisplayEntity.BlockDisplayEntity d = EntityType.BLOCK_DISPLAY.create(sw);
        if (d == null) return false;
        d.setBlockState(Blocks.ICE.getDefaultState());
        d.setTeleportDuration(1);
        d.setViewRange(2.0f);
        d.refreshPositionAndAngles(start.x, start.y, start.z, 0f, 0f);
        d.setTransformation(spin(0));
        TempEntities.track(d);
        sw.spawnEntity(d);
        SHARDS.add(new Shard(sw, player.getUuid(), d, start, dir));

        sw.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENTITY_SNOWBALL_THROW, SoundCategory.PLAYERS, 1.0f, 0.5f);
        sw.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BLOCK_GLASS_PLACE, SoundCategory.PLAYERS, 1.0f, 0.7f);
        return true;
    }

    // ---------------------------------------------------------------------
    // Flight
    // ---------------------------------------------------------------------

    /** A tumbling ice block centred on the display's position. */
    private static AffineTransformation spin(int age) {
        Matrix4f m = new Matrix4f()
                .rotateXYZ(age * 0.35f, age * 0.25f, age * 0.15f)
                .scale(SHARD_SCALE)
                .translate(-0.5f, -0.5f, -0.5f);
        return new AffineTransformation(m);
    }

    /** Returns false once the shard is gone. */
    private static boolean tickShard(Shard s) {
        ServerWorld world = s.world;
        s.age++;
        double step = Math.min(SPEED, BURST_DISTANCE - s.travelled);
        Vec3d from = s.pos;
        Vec3d to = from.add(s.dir.multiply(step));

        // Bursts early on the first wall or creature in its way
        BlockHitResult wall = world.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, net.minecraft.block.ShapeContext.absent()));
        Vec3d stop = wall.getType() == HitResult.Type.BLOCK ? wall.getPos() : null;
        double stopDist = stop != null ? from.squaredDistanceTo(stop) : Double.MAX_VALUE;
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, new Box(from, to).expand(0.5),
                e -> e.isAlive() && !e.isSpectator() && !e.getUuid().equals(s.owner))) {
            var clip = e.getBoundingBox().expand(0.3).raycast(from, to);
            if (clip.isPresent() && from.squaredDistanceTo(clip.get()) < stopDist) {
                stop = clip.get();
                stopDist = from.squaredDistanceTo(stop);
            }
        }
        if (stop != null) {
            burst(world, s.owner, stop.subtract(s.dir.multiply(0.3)));
            return false;
        }

        s.pos = to;
        s.travelled += step;
        s.display.setPosition(to.x, to.y, to.z);
        s.display.setTransformation(spin(s.age));
        s.display.setStartInterpolation(0);
        s.display.setInterpolationDuration(1);
        world.spawnParticles(ParticleTypes.SNOWFLAKE, to.x, to.y, to.z, 3, 0.15, 0.15, 0.15, 0.01);
        if (s.age % 2 == 0) {
            world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.ICE.getDefaultState()), to.x, to.y, to.z, 1, 0.1, 0.1, 0.1, 0);
        }

        if (s.travelled >= BURST_DISTANCE - 1.0e-6) {
            burst(world, s.owner, to);
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------------
    // The ice
    // ---------------------------------------------------------------------

    private static void burst(ServerWorld world, UUID ownerId, Vec3d at) {
        BlockPos center = BlockPos.ofFloored(at);
        Tomb tomb = new Tomb(world, center);
        PlayerEntity owner = world.getPlayerByUuid(ownerId);

        // Catch everything inside the cube before the ice goes in around them
        Box cube = new Box(center.add(-CUBE_RADIUS, -CUBE_RADIUS, -CUBE_RADIUS).toCenterPos().subtract(0.5, 0.5, 0.5),
                center.add(CUBE_RADIUS, CUBE_RADIUS, CUBE_RADIUS).toCenterPos().add(0.5, 0.5, 0.5));
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, cube,
                e -> e.isAlive() && !e.isSpectator() && !e.getUuid().equals(ownerId))) {
            e.damage(owner != null ? world.getDamageSources().indirectMagic(owner, owner) : world.getDamageSources().freeze(), DAMAGE);
            if (!e.isAlive()) continue;
            e.setVelocity(Vec3d.ZERO);
            e.velocityModified = true;
            e.setFrozenTicks(Math.max(e.getFrozenTicks(), e.getMinFreezeDamageTicks() + CHILL_TICKS));
            e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, CHILL_TICKS, 2, false, true));
            tomb.victims.add(new Victim(e));
        }

        for (BlockPos pos : BlockPos.iterate(center.add(-CUBE_RADIUS, -CUBE_RADIUS, -CUBE_RADIUS), center.add(CUBE_RADIUS, CUBE_RADIUS, CUBE_RADIUS))) {
            if (world.isOutOfHeightLimit(pos)) continue;
            BlockState state = world.getBlockState(pos);
            // Only fill open space (air, water, grass...); real blocks stay untouched
            if (!(state.isAir() || state.isReplaceable()) || state.hasBlockEntity()) continue;
            BlockPos key = pos.toImmutable();
            tomb.replaced.put(key, state);
            world.setBlockState(key, Blocks.ICE.getDefaultState(), PLACE_FLAGS);
        }
        TOMBS.add(tomb);

        Vec3d c = center.toCenterPos();
        world.spawnParticles(ParticleTypes.SNOWFLAKE, c.x, c.y, c.z, 120, 2.5, 2.5, 2.5, 0.1);
        world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.ICE.getDefaultState()), c.x, c.y, c.z, 80, 2.0, 2.0, 2.0, 0.2);
        world.spawnParticles(ParticleTypes.EXPLOSION, c.x, c.y, c.z, 1, 0, 0, 0, 0);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 1.5f, 0.6f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_PLAYER_HURT_FREEZE, SoundCategory.PLAYERS, 1.5f, 0.8f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_AMETHYST_BLOCK_BREAK, SoundCategory.PLAYERS, 1.5f, 0.5f);
    }

    /** Returns false once the tomb has shattered and its victims have thawed. */
    private static boolean tickTomb(Tomb t) {
        t.age++;
        boolean standing = t.age <= ENCASE_TICKS;
        if (t.age == ENCASE_TICKS + 1) shatter(t);

        Iterator<Victim> it = t.victims.iterator();
        while (it.hasNext()) {
            Victim v = it.next();
            LivingEntity e = v.entity;
            if (!e.isAlive() || e.isRemoved() || e.getWorld() != t.world) {
                it.remove();
                continue;
            }
            if (standing) {
                // Frozen solid: no moving, no turning around
                e.setVelocity(Vec3d.ZERO);
                e.velocityModified = true;
                if (e.getPos().squaredDistanceTo(v.pinnedAt) > 0.01) e.requestTeleport(v.pinnedAt.x, v.pinnedAt.y, v.pinnedAt.z);
            }
            if (t.age % CHILL_INTERVAL == 0) {
                e.timeUntilRegen = 0;
                e.damage(t.world.getDamageSources().freeze(), CHILL_DAMAGE);
                Vec3d c = e.getBoundingBox().getCenter();
                t.world.spawnParticles(ParticleTypes.SNOWFLAKE, c.x, c.y, c.z, 8, e.getWidth() * 0.4, e.getHeight() * 0.4, e.getWidth() * 0.4, 0.02);
            }
        }
        return t.age < CHILL_TICKS;
    }

    private static void shatter(Tomb t) {
        melt(t);
        Vec3d c = t.center.toCenterPos();
        t.world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.ICE.getDefaultState()), c.x, c.y, c.z, 150, 2.0, 2.0, 2.0, 0.3);
        t.world.spawnParticles(ParticleTypes.SNOWFLAKE, c.x, c.y, c.z, 60, 2.5, 2.5, 2.5, 0.05);
        t.world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 1.5f, 0.8f);
        t.world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 1.5f, 1.1f);
    }

    /** Puts back what the ice replaced, wherever the ice (or water it melted into) is still there. */
    private static void melt(Tomb t) {
        for (Map.Entry<BlockPos, BlockState> entry : t.replaced.entrySet()) {
            BlockState now = t.world.getBlockState(entry.getKey());
            if (now.isOf(Blocks.ICE) || (now.getFluidState().isOf(Fluids.WATER) && !entry.getValue().getFluidState().isOf(Fluids.WATER))) {
                t.world.setBlockState(entry.getKey(), entry.getValue(), PLACE_FLAGS);
            }
        }
        t.replaced.clear();
    }

    private static boolean isEncased(LivingEntity entity) {
        for (Tomb t : TOMBS) {
            if (t.age > ENCASE_TICKS) continue;
            for (Victim v : t.victims) if (v.entity == entity) return true;
        }
        return false;
    }
}
