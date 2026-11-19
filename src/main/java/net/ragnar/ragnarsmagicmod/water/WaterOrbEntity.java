package net.ragnar.ragnarsmagicmod.water;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.FluidTags;
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
import net.ragnar.ragnarsmagicmod.entity.IllusionEntity;
import net.ragnar.ragnarsmagicmod.util.Deflectable;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The water ball. The server flies it along its aim at {@link #SPEED}, swaying a little to the sides and up and down
 * (a mix of slow waves, eased in after launch, never more than {@link #MAX_SWAY} off the line). Every mob it washes
 * through takes {@link #HIT_DAMAGE} once. It pops when its front touches a block or after {@link #LIFETIME} ticks:
 * a splash that hurts everything within {@link #POP_RADIUS}, {@link #POP_DAMAGE} in the middle. Never breaks blocks.
 * Puts out anything on fire that it touches.
 * <p>
 * The position is the ball's center. The client eases between synced positions; the renderer and the pop are in
 * {@code water/client}.
 */
public class WaterOrbEntity extends Entity implements Deflectable {
    public static final double SPEED = 0.36;
    public static final int LIFETIME = 20 * 10;
    public static final float HIT_DAMAGE = 10f;
    public static final float POP_RADIUS = 3.5f;
    public static final float POP_DAMAGE = 6f, POP_DAMAGE_EDGE = 2f;
    /** Half the ball's width. */
    public static final double RADIUS = 0.45;
    /** Side-to-side and up-and-down sway at most (together under a block). */
    private static final double SWAY_SIDE = 0.5, SWAY_UP = 0.35;
    public static final double MAX_SWAY = Math.sqrt(SWAY_SIDE * SWAY_SIDE + SWAY_UP * SWAY_UP);
    /** Ticks the sway takes to ease in, so it leaves the staff cleanly. */
    private static final int SWAY_IN = 14;

    // Server
    @Nullable private UUID ownerId;
    private Vec3d origin = Vec3d.ZERO;
    private Vec3d dir = new Vec3d(0, 0, 1), side = new Vec3d(1, 0, 0), up = new Vec3d(0, 1, 0);
    private int flown;
    private float p1, p2, p3, p4;
    private final Set<UUID> soaked = new HashSet<>();

    // Client
    @Nullable private Vec3d lerpTo;

    public WaterOrbEntity(EntityType<? extends WaterOrbEntity> type, World world) {
        super(type, world);
        noClip = true;
        setNoGravity(true);
        ignoreCameraFrustum = true; // the position is its center, not the bottom of its box
    }

    WaterOrbEntity(ServerWorld world, PlayerEntity owner, Vec3d look) {
        this(Water.ORB, world);
        ownerId = owner.getUuid();
        Vec3d eye = owner.getEyePos();
        Vec3d start = eye.add(look.normalize().multiply(1.0)).add(0, -0.2, 0);
        // Point blank into a wall: start at the wall, so it pops there on the first tick
        BlockHitResult wall = world.raycast(new RaycastContext(eye, start, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, owner));
        if (wall.getType() != HitResult.Type.MISS) start = wall.getPos().subtract(look.normalize().multiply(0.05));
        launch(start, look);
        setPosition(start);
        resetPosition();
    }

    /** Sets off from {@code from} along {@code look}, with a fresh sway. */
    private void launch(Vec3d from, Vec3d look) {
        origin = from;
        dir = look.normalize();
        Vec3d s = dir.crossProduct(new Vec3d(0, 1, 0));
        side = s.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : s.normalize();
        up = side.crossProduct(dir).normalize();
        flown = 0;
        p1 = random.nextFloat() * MathHelper.TAU;
        p2 = random.nextFloat() * MathHelper.TAU;
        p3 = random.nextFloat() * MathHelper.TAU;
        p4 = random.nextFloat() * MathHelper.TAU;
        soaked.clear();
    }

    /** Where the ball is {@code t} ticks into its flight. */
    private Vec3d pathAt(int t) {
        double ease = Math.min(1.0, t / (double) SWAY_IN);
        ease = ease * ease * (3 - 2 * ease);
        double s = SWAY_SIDE * (0.7 * Math.sin(t * 0.11 + p1) + 0.3 * Math.sin(t * 0.27 + p2));
        double u = SWAY_UP * (0.7 * Math.sin(t * 0.14 + p3) + 0.3 * Math.sin(t * 0.33 + p4));
        return origin.add(dir.multiply(t * SPEED)).add(side.multiply(s * ease)).add(up.multiply(u * ease));
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {}

    // A Tome of Deflection pane sends it back the way it came, as the deflector's
    @Override
    public Vec3d deflectVelocity() {
        return isRemoved() ? Vec3d.ZERO : dir.multiply(SPEED);
    }

    @Override
    @Nullable
    public UUID deflectOwner() {
        return ownerId;
    }

    @Override
    public void deflect(PlayerEntity deflector, Vec3d dir, double speed) {
        ownerId = deflector.getUuid();
        launch(getPos(), dir);
    }

    @Override
    public boolean shouldSave() {
        return false;
    }

    @Override
    public boolean shouldRender(double distance) {
        return distance < 96 * 96;
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
        PlayerEntity owner = ownerId != null ? sw.getPlayerByUuid(ownerId) : null;

        Vec3d from = getPos();
        Vec3d to = pathAt(flown + 1);
        Vec3d step = to.subtract(from);
        Vec3d heading = step.lengthSquared() > 1e-8 ? step.normalize() : dir;
        // Look ahead by the ball's radius, so it pops as its front touches the block
        BlockHitResult block = sw.raycast(new RaycastContext(from, to.add(heading.multiply(RADIUS)),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this));
        boolean hitBlock = block.getType() != HitResult.Type.MISS;
        Vec3d end = hitBlock ? block.getPos().subtract(heading.multiply(RADIUS)) : to;
        if (hitBlock && end.subtract(from).dotProduct(heading) < 0) end = from;

        washThrough(sw, from, end, owner);
        setPosition(end);
        flown++;

        if (hitBlock || flown >= LIFETIME) {
            pop(sw, owner);
            return;
        }
        flightEffects(sw);
    }

    @Override
    public void updateTrackedPositionAndAngles(double x, double y, double z, float yaw, float pitch, int steps) {
        lerpTo = new Vec3d(x, y, z);
    }

    // ---------------------------------------------------------------------
    // Hurting things
    // ---------------------------------------------------------------------

    /** Every mob the ball passes through this tick is hit once, and washed along a little. */
    private void washThrough(ServerWorld sw, Vec3d from, Vec3d to, @Nullable PlayerEntity owner) {
        Box swept = new Box(from, to).expand(RADIUS + 1.0);
        for (Entity e : sw.getOtherEntities(this, swept, e -> canHurt(e, owner) && !soaked.contains(e.getUuid()))) {
            Box box = e.getBoundingBox().expand(RADIUS);
            if (!box.contains(from) && box.raycast(from, to).isEmpty()) continue;
            LivingEntity victim = (LivingEntity) e;
            soaked.add(victim.getUuid());
            hurt(sw, victim, HIT_DAMAGE, owner);
            victim.takeKnockback(0.35, -dir.x, -dir.z);

            Vec3d c = victim.getBoundingBox().getCenter();
            sw.spawnParticles(ParticleTypes.SPLASH, c.x, c.y, c.z, 30, victim.getWidth() * 0.45, victim.getHeight() * 0.35, victim.getWidth() * 0.45, 0.25);
            sw.spawnParticles(ParticleTypes.FALLING_WATER, c.x, c.y, c.z, 8, victim.getWidth() * 0.4, victim.getHeight() * 0.3, victim.getWidth() * 0.4, 0);
            sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_AXOLOTL_SPLASH, SoundCategory.PLAYERS, 1.0f, 1.1f + random.nextFloat() * 0.2f);
            sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_GUARDIAN_FLOP, SoundCategory.PLAYERS, 0.7f, 1.3f + random.nextFloat() * 0.2f);
        }
    }

    /** The splash. Hurts and nudges everything in reach, more the closer it is. No blocks are touched. */
    private void pop(ServerWorld sw, @Nullable PlayerEntity owner) {
        Vec3d c = getPos();
        for (LivingEntity e : sw.getEntitiesByClass(LivingEntity.class, new Box(c, c).expand(POP_RADIUS + 1), e -> canHurt(e, owner))) {
            Vec3d at = e.getBoundingBox().getCenter();
            double d = Math.max(0, at.distanceTo(c) - e.getWidth() * 0.5);
            if (d > POP_RADIUS || !canReach(sw, c, e)) continue;
            float k = (float) (1.0 - d / POP_RADIUS);
            hurt(sw, e, MathHelper.lerp(k, POP_DAMAGE_EDGE, POP_DAMAGE), owner);
            Vec3d push = at.subtract(c).multiply(1, 0, 1);
            if (push.lengthSquared() > 1e-4) e.takeKnockback(0.25 + 0.45 * k, -push.x, -push.z);
        }

        sw.spawnParticles(ParticleTypes.SPLASH, c.x, c.y, c.z, 140, 1.0, 0.7, 1.0, 0.4);
        sw.spawnParticles(ParticleTypes.FALLING_WATER, c.x, c.y + 0.3, c.z, 50, 1.4, 0.6, 1.4, 0);
        sw.spawnParticles(ParticleTypes.BUBBLE_POP, c.x, c.y, c.z, 30, 0.6, 0.6, 0.6, 0.1);
        if (inWater(sw, c)) sw.spawnParticles(ParticleTypes.BUBBLE, c.x, c.y, c.z, 60, 0.8, 0.8, 0.8, 0.2);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_HOSTILE_SPLASH, SoundCategory.PLAYERS, 1.4f, 0.9f + random.nextFloat() * 0.15f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_DOLPHIN_SPLASH, SoundCategory.PLAYERS, 1.2f, 0.8f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_WET_SPONGE_BREAK, SoundCategory.PLAYERS, 1.0f, 1.2f);
        Water.pop(sw, c);
        discard();
    }

    private void hurt(ServerWorld sw, LivingEntity e, float amount, @Nullable PlayerEntity owner) {
        // The pass and the pop can land a moment apart; neither should bounce off hurt immunity
        e.timeUntilRegen = 0;
        DamageSource source = owner != null ? sw.getDamageSources().playerAttack(owner) : sw.getDamageSources().magic();
        e.damage(source, amount);
        if (e.isOnFire()) e.extinguish();
    }

    private boolean canHurt(Entity e, @Nullable PlayerEntity owner) {
        if (!(e instanceof LivingEntity le) || !le.isAlive() || le.isSpectator() || !le.canHit() || e instanceof ArmorStandEntity) return false;
        if (ownerId != null) {
            if (e.getUuid().equals(ownerId) || e instanceof IllusionEntity) return false;
            if (e instanceof TameableEntity pet && ownerId.equals(pet.getOwnerUuid())) return false;
        }
        return owner == null || !e.isTeammate(owner);
    }

    private boolean canReach(ServerWorld sw, Vec3d from, Entity e) {
        Vec3d to = e.getBoundingBox().getCenter();
        return sw.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this)).getType() == HitResult.Type.MISS;
    }

    private static boolean inWater(ServerWorld sw, Vec3d at) {
        return sw.getFluidState(BlockPos.ofFloored(at)).isIn(FluidTags.WATER);
    }

    // ---------------------------------------------------------------------
    // Effects
    // ---------------------------------------------------------------------

    /** Drips falling off it, a little spray, and a soft slosh now and then. */
    private void flightEffects(ServerWorld sw) {
        Vec3d c = getPos();
        if (inWater(sw, c)) {
            sw.spawnParticles(ParticleTypes.BUBBLE, c.x, c.y, c.z, 3, 0.3, 0.3, 0.3, 0.05);
        } else {
            sw.spawnParticles(ParticleTypes.FALLING_WATER, c.x, c.y - 0.15, c.z, 2, 0.28, 0.2, 0.28, 0);
            if (flown % 2 == 0) sw.spawnParticles(ParticleTypes.SPLASH, c.x, c.y, c.z, 2, 0.3, 0.3, 0.3, 0.05);
        }
        if (flown % 9 == 1) {
            sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_AXOLOTL_SWIM, SoundCategory.PLAYERS, 0.6f, 0.75f + random.nextFloat() * 0.2f);
        }
    }

    // ---------------------------------------------------------------------
    // Never saved (see shouldSave)
    // ---------------------------------------------------------------------

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {}

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {}
}
