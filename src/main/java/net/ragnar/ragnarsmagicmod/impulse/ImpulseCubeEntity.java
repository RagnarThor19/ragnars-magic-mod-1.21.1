package net.ragnar.ragnarsmagicmod.impulse;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.ragnar.ragnarsmagicmod.util.Deflectable;
import net.minecraft.nbt.NbtCompound;
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
import net.ragnar.ragnarsmagicmod.network.ShakePayload;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.Optional;
import java.util.UUID;

/**
 * The impulse cube. Flies like a thrown pearl ({@link #SPEED}, falling under {@link #GRAVITY}) and sticks to the
 * first thing it touches: a block, or any mob but its caster, riding along with it. Once stuck it hums for
 * {@link #FUSE} ticks and goes off, flinging everything within {@link #RADIUS} straight away from it - up to
 * {@link #MAX_PUSH} blocks a tick right on top of it - caster included. The push never hurts and breaks nothing, but
 * whatever it throws falls as far as it was thrown. If it never lands it goes off in mid-air after {@link #MAX_FLIGHT}.
 * <p>
 * On the client it eases between synced positions; {@link #fuse()} tells it how close it is to going off.
 */
public class ImpulseCubeEntity extends Entity implements Deflectable {
    public static final double SPEED = 1.45;
    public static final double GRAVITY = 0.045;
    public static final int FUSE = 16;
    public static final int MAX_FLIGHT = 120;
    public static final float RADIUS = 6f;
    public static final double MAX_PUSH = 5.94, MIN_PUSH = 1.89;
    /** Half the cube's size when it comes to running into mobs. */
    private static final double HIT_RADIUS = 0.15;
    /** Ticks after the throw before it can stick to anything, so it doesn't catch on the caster's own hand. */
    private static final int ARMING = 2;

    /** Ticks until it goes off once stuck, or -1 while still flying. */
    private static final TrackedData<Integer> FUSE_LEFT = DataTracker.registerData(ImpulseCubeEntity.class, TrackedDataHandlerRegistry.INTEGER);

    // Server
    @Nullable private UUID ownerId;
    private Vec3d vel = Vec3d.ZERO;
    @Nullable private Entity stuckTo;
    private Vec3d stuckOffset = Vec3d.ZERO;
    @Nullable private BlockPos stuckBlock;
    /** The face it's stuck to points this way; zero when it's on a mob or still flying. */
    private Vec3d normal = Vec3d.ZERO;

    // Client
    @Nullable private Vec3d lerpTo;
    /** How far it had tumbled when it stuck, so the renderer can freeze it there; -1 until then. */
    public float frozenSpin = -1;

    public ImpulseCubeEntity(EntityType<? extends ImpulseCubeEntity> type, World world) {
        super(type, world);
        noClip = true;
        setNoGravity(true);
    }

    ImpulseCubeEntity(ServerWorld world, PlayerEntity owner, Vec3d look) {
        this(Impulse.CUBE, world);
        ownerId = owner.getUuid();
        Vec3d dir = look.normalize();
        Vec3d eye = owner.getEyePos();
        Vec3d start = eye.add(dir.multiply(0.6)).add(0, -0.1, 0);
        BlockHitResult wall = world.raycast(new RaycastContext(eye, start, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, owner));
        if (wall.getType() != HitResult.Type.MISS) start = wall.getPos().subtract(dir.multiply(0.05));
        // Thrown from a moving caster it carries some of their speed, like any thrown thing
        Vec3d carry = owner.getVelocity();
        vel = dir.multiply(SPEED).add(carry.x, owner.isOnGround() ? 0 : carry.y, carry.z);
        setPosition(start);
        resetPosition();
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        builder.add(FUSE_LEFT, -1);
    }

    /** Ticks until it goes off, or -1 while it's still flying. */
    public int fuse() {
        return dataTracker.get(FUSE_LEFT);
    }

    public boolean isStuck() {
        return fuse() >= 0;
    }

    @Override
    public boolean shouldSave() {
        return false;
    }

    @Override
    public boolean shouldRender(double distance) {
        return distance < 96 * 96;
    }

    // A Tome of Deflection pane bats it back while it's flying, as the deflector's
    @Override
    public Vec3d deflectVelocity() {
        return isStuck() || isRemoved() ? Vec3d.ZERO : vel;
    }

    @Override
    @Nullable
    public UUID deflectOwner() {
        return ownerId;
    }

    @Override
    public double deflectGravity() {
        return GRAVITY;
    }

    @Override
    public void deflect(PlayerEntity deflector, Vec3d dir, double speed) {
        ownerId = deflector.getUuid();
        vel = dir.multiply(speed);
    }

    // ---------------------------------------------------------------------
    // Ticking
    // ---------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (getWorld().isClient) {
            if (lerpTo != null) {
                setPosition(lerpTo);
                lerpTo = null;
            }
            return;
        }
        ServerWorld sw = (ServerWorld) getWorld();

        if (isStuck()) {
            holdOn(sw);
            int left = fuse() - 1;
            dataTracker.set(FUSE_LEFT, left);
            fuseEffects(sw, left);
            if (left <= 0) explode(sw);
            return;
        }
        if (age > MAX_FLIGHT) {
            explode(sw);
            return;
        }
        fly(sw);
    }

    /** One tick of flight: moves, and sticks to the first block or mob in the way. */
    private void fly(ServerWorld sw) {
        Vec3d from = getPos();
        Vec3d to = from.add(vel);
        BlockHitResult block = sw.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this));
        Vec3d end = block.getType() != HitResult.Type.MISS ? block.getPos() : to;

        if (age >= ARMING) {
            Entity hit = null;
            Vec3d hitAt = null;
            double best = Double.MAX_VALUE;
            for (Entity e : sw.getOtherEntities(this, new Box(from, end).expand(HIT_RADIUS + 1.0), this::canStickTo)) {
                Box box = e.getBoundingBox().expand(HIT_RADIUS);
                Optional<Vec3d> at = box.contains(from) ? Optional.of(from) : box.raycast(from, end);
                if (at.isPresent() && at.get().squaredDistanceTo(from) < best) {
                    best = at.get().squaredDistanceTo(from);
                    hit = e;
                    hitAt = at.get();
                }
            }
            if (hit != null) {
                setPosition(hitAt);
                stuckTo = hit;
                stuckOffset = hitAt.subtract(hit.getPos());
                stick(sw);
                return;
            }
        }
        if (block.getType() != HitResult.Type.MISS) {
            normal = Vec3d.of(block.getSide().getVector());
            setPosition(block.getPos().add(normal.multiply(0.13)));
            stuckBlock = block.getBlockPos();
            stick(sw);
            return;
        }

        setPosition(to);
        vel = vel.multiply(0.99).add(0, -GRAVITY, 0);
        if (age % 2 == 0) {
            sw.spawnParticles(ParticleTypes.REVERSE_PORTAL, to.x, to.y, to.z, 1, 0.03, 0.03, 0.03, 0.01);
        }
    }

    private boolean canStickTo(Entity e) {
        return e instanceof LivingEntity le && le.isAlive() && !le.isSpectator() && le.canHit()
                && (ownerId == null || !ownerId.equals(e.getUuid()));
    }

    private void stick(ServerWorld sw) {
        vel = Vec3d.ZERO;
        dataTracker.set(FUSE_LEFT, FUSE);
        Vec3d p = getPos();
        sw.playSound(null, p.x, p.y, p.z, SoundEvents.ENTITY_SLIME_SQUISH_SMALL, SoundCategory.PLAYERS, 1.0f, 0.7f);
        sw.playSound(null, p.x, p.y, p.z, SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.PLAYERS, 0.9f, 1.6f);
        sw.spawnParticles(dust(1.0f), p.x, p.y, p.z, 10, 0.12, 0.12, 0.12, 0.0);
    }

    /** Rides along with the mob it's stuck to; if that mob or block goes away, it just drops and goes off where it is. */
    private void holdOn(ServerWorld sw) {
        if (stuckTo != null) {
            if (stuckTo.isAlive() && !stuckTo.isRemoved()) {
                setPosition(stuckTo.getPos().add(stuckOffset));
            } else {
                stuckTo = null;
            }
        } else if (stuckBlock != null && sw.getBlockState(stuckBlock).getCollisionShape(sw, stuckBlock).isEmpty()) {
            stuckBlock = null;
            normal = Vec3d.ZERO;
        }
    }

    /** Synced positions land here; the next tick moves there, so rendering eases across the gap. */
    @Override
    public void updateTrackedPositionAndAngles(double x, double y, double z, float yaw, float pitch, int steps) {
        lerpTo = new Vec3d(x, y, z);
    }

    // ---------------------------------------------------------------------
    // The blast
    // ---------------------------------------------------------------------

    /** Flings everything in reach away from the cube. Hurts nothing, breaks nothing. */
    private void explode(ServerWorld sw) {
        Vec3d c = getPos();
        for (Entity e : sw.getOtherEntities(this, new Box(c, c).expand(RADIUS + 1), this::canPush)) {
            Vec3d push = pushFor(sw, c, e);
            if (push == null) continue;
            e.setVelocity(e.getVelocity().multiply(0.2).add(push));
            e.velocityModified = true;
            // A fall is counted from where it throws you, not from wherever you were falling from before
            e.fallDistance = 0;
        }

        Vector3f dir = normal.toVector3f();
        sw.spawnParticles(ParticleTypes.GUST_EMITTER_SMALL, c.x, c.y, c.z, 1, 0, 0, 0, 0);
        sw.spawnParticles(ParticleTypes.FLASH, c.x, c.y, c.z, 1, 0, 0, 0, 0);
        sw.spawnParticles(dust(2.0f), c.x + dir.x * 0.3, c.y + dir.y * 0.3, c.z + dir.z * 0.3, 60, 1.2, 1.2, 1.2, 0.0);
        sw.spawnParticles(ParticleTypes.REVERSE_PORTAL, c.x, c.y, c.z, 80, 0.3, 0.3, 0.3, 0.9);
        sw.spawnParticles(ParticleTypes.PORTAL, c.x, c.y, c.z, 40, 0.5, 0.5, 0.5, 1.5);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_WIND_CHARGE_WIND_BURST.value(), SoundCategory.PLAYERS, 2.0f, 0.55f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 0.7f, 1.7f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 1.0f, 0.5f);
        Impulse.blast(sw, c, normal, RADIUS);
        ShakePayload.around(sw, c, RADIUS, 0.35f, 8);
        discard();
    }

    private boolean canPush(Entity e) {
        if (e instanceof ImpulseCubeEntity || e.isSpectator() || e.hasVehicle() || !e.isAlive()) return false;
        return !(e instanceof PlayerEntity p && p.getAbilities().flying);
    }

    /**
     * The kick {@code e} gets from a blast at {@code c}, or null if it's out of reach or behind a wall. Straight away
     * from the cube, hardest up close, and always with some lift so mobs on the ground leave it rather than skid.
     */
    @Nullable
    static Vec3d pushFor(World world, Vec3d c, Entity e) {
        Box b = e.getBoundingBox();
        Vec3d at = b.getCenter();
        double d = at.distanceTo(c);
        if (d > RADIUS) return null;
        if (!clear(world, c, at, e) && !clear(world, c, new Vec3d(at.x, b.maxY - 0.1, at.z), e)) return null;

        Vec3d dir = d > 0.3 ? at.subtract(c).normalize() : new Vec3d(0, 1, 0);
        double k = 1.0 - d / RADIUS;
        double strength = MathHelper.lerp(k, MIN_PUSH, MAX_PUSH);
        if (e instanceof LivingEntity le) {
            // Heavy things (golems, wardens) shrug off some of it, but never all of it
            strength *= 1.0 - 0.5 * MathHelper.clamp(le.getAttributeValue(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE), 0.0, 1.0);
        }
        Vec3d push = dir.multiply(strength);
        if (dir.y > -0.5) push = new Vec3d(push.x, Math.max(push.y, 0.945 + 0.945 * k), push.z);
        return push;
    }

    private static boolean clear(World world, Vec3d from, Vec3d to, Entity e) {
        return world.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, e)).getType() == HitResult.Type.MISS;
    }

    // ---------------------------------------------------------------------
    // Effects
    // ---------------------------------------------------------------------

    /** Ticks faster and higher as it gets close, with a wisp of blue each tick. */
    private void fuseEffects(ServerWorld sw, int left) {
        Vec3d p = getPos();
        int every = left > FUSE / 2 ? 4 : 2;
        if (left % every == 0) {
            float pitch = 1.0f + 1.0f * (1f - (float) left / FUSE);
            sw.playSound(null, p.x, p.y, p.z, SoundEvents.BLOCK_NOTE_BLOCK_BIT.value(), SoundCategory.PLAYERS, 0.8f, pitch);
        }
        sw.spawnParticles(dust(0.8f), p.x, p.y, p.z, 2, 0.15, 0.15, 0.15, 0.0);
        sw.spawnParticles(ParticleTypes.REVERSE_PORTAL, p.x, p.y, p.z, 1, 0.05, 0.05, 0.05, 0.02);
    }

    private static DustParticleEffect dust(float size) {
        return new DustParticleEffect(new Vector3f(0.15f, 0.3f, 0.95f), size);
    }

    // ---------------------------------------------------------------------
    // Never saved (see shouldSave)
    // ---------------------------------------------------------------------

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {}

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {}
}
