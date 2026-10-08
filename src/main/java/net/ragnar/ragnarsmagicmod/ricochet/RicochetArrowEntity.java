package net.ragnar.ragnarsmagicmod.ricochet;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.ragnar.ragnarsmagicmod.util.Deflectable;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.entity.IllusionEntity;
import net.ragnar.ragnarsmagicmod.network.ShakePayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The Tome of Ricochet's arrow. It has no physics of its own: the server moves it every tick, first holding it over
 * its caster's head while it winds up, then flying it straight and very fast. Each mob it hits takes
 * {@link #DAMAGE}, and it then darts to the nearest mob within {@link #BOUNCE_RANGE} blocks of that one it hasn't
 * hit yet (and can see), until there are none left. It stops dead on a block.
 * <p>
 * On the client it just eases from one synced spot to the next, and remembers where it's been for the trail.
 */
public class RicochetArrowEntity extends Entity implements Deflectable {
    public static final float DAMAGE = 20f;
    public static final int CHARGE_TICKS = 30;
    /** Blocks per tick in a straight line, and while darting to the next mob. */
    static final double SPEED = 4.5;
    static final double BOUNCE_SPEED = 3.0;
    static final double BOUNCE_RANGE = 5.0;
    /** How far you can aim it, and how long it flies before giving up (about 180 blocks). */
    private static final double AIM_RANGE = 96.0;
    private static final int MAX_FLIGHT = 40;
    /** Ticks to reach the next mob before it gives up on it (it's never more than a couple). */
    private static final int BOUNCE_PATIENCE = 10;
    private static final int MAX_HITS = 16;
    /** Positions the client keeps for the trail. */
    public static final int TRAIL = 6;

    private static final ParticleEffect WHITE = new DustParticleEffect(new Vector3f(1f, 1f, 1f), 1.3f);
    private static final ParticleEffect WHITE_BIG = new DustParticleEffect(new Vector3f(1f, 1f, 1f), 2.2f);

    /** The caster's network id, so the client can hang the arrow over their head while it charges. */
    private static final TrackedData<Integer> OWNER = DataTracker.registerData(RicochetArrowEntity.class, TrackedDataHandlerRegistry.INTEGER);
    /** Ticks of wind-up left; 0 once it's flying. */
    private static final TrackedData<Integer> CHARGE = DataTracker.registerData(RicochetArrowEntity.class, TrackedDataHandlerRegistry.INTEGER);

    // Server
    @Nullable private UUID ownerId;
    private Vec3d dir = new Vec3d(0, 0, 1);
    @Nullable private LivingEntity target;
    private int targetTicks;
    private final Set<UUID> struck = new HashSet<>();
    private int flight;

    // Client
    @Nullable private Vec3d lerpTo;
    private float lerpYaw, lerpPitch;
    public final Deque<Vec3d> trail = new ArrayDeque<>();

    public RicochetArrowEntity(EntityType<? extends RicochetArrowEntity> type, World world) {
        super(type, world);
        noClip = true;
        setNoGravity(true);
    }

    RicochetArrowEntity(World world, PlayerEntity owner) {
        this(Ricochet.ARROW, world);
        ownerId = owner.getUuid();
        dataTracker.set(OWNER, owner.getId());
        dataTracker.set(CHARGE, CHARGE_TICKS);
        setPosition(hoverPos(owner, 1f));
        aim(owner.getRotationVec(1f));
        resetPosition();
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        builder.add(OWNER, -1);
        builder.add(CHARGE, 0);
    }

    public int ownerNetId() {
        return dataTracker.get(OWNER);
    }

    // A Tome of Deflection pane turns it back once it's flying, as the deflector's, ready to bounce on from there
    @Override
    public Vec3d deflectVelocity() {
        return charge() > 0 || isRemoved() ? Vec3d.ZERO : dir.multiply(target != null ? BOUNCE_SPEED : SPEED);
    }

    @Override
    @Nullable
    public UUID deflectOwner() {
        return ownerId;
    }

    @Override
    public void deflect(PlayerEntity deflector, Vec3d dir, double speed) {
        ownerId = deflector.getUuid();
        dataTracker.set(OWNER, deflector.getId());
        target = null;
        struck.clear();
        flight = 0;
        this.dir = dir;
        aim(dir);
    }

    public int charge() {
        return dataTracker.get(CHARGE);
    }

    /**
     * Where it hangs while charging: over the caster's head and a little in front, far enough forward that they
     * can see it rattling at the top of their screen.
     */
    public static Vec3d hoverPos(Entity owner, float tickDelta) {
        Vec3d ahead = Vec3d.fromPolar(0f, owner.getYaw(tickDelta)).multiply(0.9);
        return owner.getLerpedPos(tickDelta).add(ahead.x, owner.getHeight() + 0.35, ahead.z);
    }

    /** Arrow-style yaw and pitch (as the renderer expects) for flying along {@code d}. */
    public static float yawOf(Vec3d d) {
        return (float) (MathHelper.atan2(d.x, d.z) * MathHelper.DEGREES_PER_RADIAN);
    }

    public static float pitchOf(Vec3d d) {
        return (float) (MathHelper.atan2(d.y, d.horizontalLength()) * MathHelper.DEGREES_PER_RADIAN);
    }

    private void aim(Vec3d d) {
        setYaw(yawOf(d));
        setPitch(pitchOf(d));
    }

    @Override
    public boolean shouldSave() {
        return false;
    }

    @Override
    public boolean shouldRender(double distance) {
        return distance < 128 * 128;
    }

    // ---------------------------------------------------------------------
    // Ticking
    // ---------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (getWorld().isClient) {
            clientTick();
            return;
        }
        ServerWorld sw = (ServerWorld) getWorld();
        ServerPlayerEntity owner = ownerId != null && sw.getPlayerByUuid(ownerId) instanceof ServerPlayerEntity p ? p : null;

        int charge = charge();
        if (charge > 0) {
            if (owner == null || !owner.isAlive()) {
                fizzle(sw);
                return;
            }
            setPosition(hoverPos(owner, 1f));
            aim(owner.getRotationVec(1f));
            charging(sw, charge);
            dataTracker.set(CHARGE, --charge);
            if (charge == 0) loose(sw, owner);
            return;
        }
        fly(sw, owner);
    }

    private void clientTick() {
        if (lerpTo != null) {
            setPosition(lerpTo);
            setYaw(lerpYaw);
            setPitch(lerpPitch);
            lerpTo = null;
        }
        if (charge() > 0) {
            trail.clear();
        } else {
            trail.addFirst(getPos());
            while (trail.size() > TRAIL) trail.removeLast();
        }
    }

    /** Synced positions land here; the next tick moves there, so rendering eases across the gap. */
    @Override
    public void updateTrackedPositionAndAngles(double x, double y, double z, float yaw, float pitch, int steps) {
        lerpTo = new Vec3d(x, y, z);
        lerpYaw = yaw;
        lerpPitch = pitch;
    }

    // ---------------------------------------------------------------------
    // Wind-up
    // ---------------------------------------------------------------------

    void chargeStart(ServerWorld sw) {
        Vec3d p = getPos();
        sw.playSound(null, p.x, p.y, p.z, SoundEvents.ITEM_CROSSBOW_LOADING_START, SoundCategory.PLAYERS, 1.0f, 1.3f);
        sw.playSound(null, p.x, p.y, p.z, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 0.9f, 1.6f);
        sw.spawnParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 12, 0.25, 0.25, 0.25, 0.06);
    }

    /** White motes drawn in to the arrow, faster and thicker as it gets closer to going, and a rising rattle. */
    private void charging(ServerWorld sw, int charge) {
        float progress = 1f - charge / (float) CHARGE_TICKS;
        Vec3d c = getPos();
        int motes = 1 + (int) (progress * 4);
        for (int i = 0; i < motes; i++) {
            Vec3d off = new Vec3d(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()).normalize().multiply(0.9 + random.nextDouble() * 0.5);
            // Count 0: the offsets become the velocity, so each mote flies straight into the arrow
            sw.spawnParticles(ParticleTypes.END_ROD, c.x + off.x, c.y + off.y, c.z + off.z, 0, -off.x, -off.y, -off.z, 0.12 + progress * 0.08);
        }
        if (random.nextFloat() < 0.3f + progress * 0.5f) {
            sw.spawnParticles(WHITE, c.x, c.y, c.z, 1, 0.3, 0.15, 0.3, 0);
        }
        if (charge % 4 == 0) {
            sw.playSound(null, c.x, c.y, c.z, SoundEvents.ITEM_CROSSBOW_LOADING_MIDDLE, SoundCategory.PLAYERS, 0.55f, 0.9f + progress * 1.0f);
        }
        if (progress > 0.6f && charge % 2 == 0) {
            sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_CHAIN_HIT, SoundCategory.PLAYERS, 0.35f, 1.4f + progress * 0.6f);
        }
    }

    /** Lets go, straight at whatever is under the caster's crosshair. */
    private void loose(ServerWorld sw, ServerPlayerEntity owner) {
        Vec3d eye = owner.getEyePos();
        Vec3d look = owner.getRotationVec(1f);
        Vec3d far = eye.add(look.multiply(AIM_RANGE));
        BlockHitResult block = sw.raycast(new RaycastContext(eye, far, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, owner));
        Vec3d aimAt = block.getType() == HitResult.Type.MISS ? far : block.getPos();
        EntityHitResult mob = ProjectileUtil.raycast(owner, eye, aimAt, owner.getBoundingBox().stretch(aimAt.subtract(eye)).expand(1.0),
                e -> canStrike(e, owner), eye.squaredDistanceTo(aimAt));
        if (mob != null) aimAt = mob.getEntity().getBoundingBox().getCenter();

        Vec3d from = getPos();
        Vec3d d = aimAt.subtract(from);
        dir = d.lengthSquared() < 1e-4 ? look : d.normalize();
        aim(dir);

        sw.playSound(null, from.x, from.y, from.z, SoundEvents.ITEM_CROSSBOW_SHOOT, SoundCategory.PLAYERS, 1.0f, 1.5f);
        sw.playSound(null, from.x, from.y, from.z, SoundEvents.ENTITY_BREEZE_SHOOT, SoundCategory.PLAYERS, 0.8f, 1.4f);
        sw.playSound(null, from.x, from.y, from.z, SoundEvents.ITEM_TRIDENT_RIPTIDE_1, SoundCategory.PLAYERS, 0.5f, 1.8f);
        sw.spawnParticles(ParticleTypes.FLASH, from.x, from.y, from.z, 1, 0, 0, 0, 0);
        ring(sw, from, dir, 0.25, 14);
        shake(owner, 0.25f, 5);
    }

    // ---------------------------------------------------------------------
    // Flight
    // ---------------------------------------------------------------------

    private void fly(ServerWorld sw, @Nullable ServerPlayerEntity owner) {
        if (++flight > MAX_FLIGHT) {
            fizzle(sw);
            return;
        }
        Vec3d from = getPos();
        Vec3d to;
        if (target != null) {
            if (!target.isAlive() || target.isRemoved() || target.getWorld() != sw || ++targetTicks > BOUNCE_PATIENCE) {
                finale(sw, from);
                return;
            }
            Vec3d at = target.getBoundingBox().getCenter();
            Vec3d d = at.subtract(from);
            double len = d.length();
            if (len > 1e-4) dir = d.multiply(1.0 / len);
            to = len <= BOUNCE_SPEED ? at : from.add(dir.multiply(BOUNCE_SPEED));
        } else {
            to = from.add(dir.multiply(SPEED));
        }

        // Darting between mobs it already knows it can see, it doesn't stop for corners it clips
        BlockHitResult block = target != null ? null
                : sw.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this));
        Vec3d end = block == null || block.getType() == HitResult.Type.MISS ? to : block.getPos();

        EntityHitResult hit = ProjectileUtil.getEntityCollision(sw, this, from, end,
                getBoundingBox().stretch(end.subtract(from)).expand(1.0), e -> canStrike(e, owner));
        if (hit != null && hit.getEntity() instanceof LivingEntity victim) {
            streak(sw, from, victim.getBoundingBox().getCenter());
            strike(sw, victim, owner);
            return;
        }

        streak(sw, from, end);
        if (block != null && block.getType() != HitResult.Type.MISS) {
            setPosition(end);
            thunk(sw, end);
            return;
        }
        setPosition(to);
        aim(dir);
    }

    private void strike(ServerWorld sw, LivingEntity victim, @Nullable ServerPlayerEntity owner) {
        struck.add(victim.getUuid());
        Vec3d c = victim.getBoundingBox().getCenter();
        setPosition(c);

        // Several hits land within a few ticks of each other; none of them should bounce off hurt immunity
        victim.timeUntilRegen = 0;
        DamageSource source = owner != null ? sw.getDamageSources().playerAttack(owner) : sw.getDamageSources().magic();
        victim.damage(source, DAMAGE);
        victim.takeKnockback(0.35, -dir.x, -dir.z);

        int n = struck.size();
        impact(sw, c, n);
        if (owner != null) shake(owner, 0.12f + Math.min(n, 6) * 0.03f, 4);

        LivingEntity next = n < MAX_HITS ? nextTarget(sw, c, owner) : null;
        if (next == null) {
            finale(sw, c);
            return;
        }
        target = next;
        targetTicks = 0;
        Vec3d d = next.getBoundingBox().getCenter().subtract(c);
        if (d.lengthSquared() > 1e-4) dir = d.normalize();
        aim(dir);
    }

    /** The closest mob it hasn't hit yet within bouncing range of {@code from}, with nothing solid in between. */
    @Nullable
    private LivingEntity nextTarget(ServerWorld sw, Vec3d from, @Nullable PlayerEntity owner) {
        LivingEntity best = null;
        double bestDist = BOUNCE_RANGE * BOUNCE_RANGE;
        for (LivingEntity e : sw.getEntitiesByClass(LivingEntity.class, new Box(from, from).expand(BOUNCE_RANGE + 1), e -> canStrike(e, owner))) {
            Vec3d c = e.getBoundingBox().getCenter();
            double d = c.squaredDistanceTo(from);
            if (d > bestDist) continue;
            if (sw.raycast(new RaycastContext(from, c, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this)).getType() != HitResult.Type.MISS) continue;
            best = e;
            bestDist = d;
        }
        return best;
    }

    private boolean canStrike(Entity e, @Nullable PlayerEntity owner) {
        if (!(e instanceof LivingEntity le) || !le.isAlive() || le.isSpectator() || !le.canHit()) return false;
        if (e instanceof ArmorStandEntity || struck.contains(e.getUuid())) return false;
        if (ownerId != null) {
            if (e.getUuid().equals(ownerId) || e instanceof IllusionEntity) return false;
            if (e instanceof TameableEntity pet && ownerId.equals(pet.getOwnerUuid())) return false;
        }
        return owner == null || !e.isTeammate(owner);
    }

    // ---------------------------------------------------------------------
    // Effects
    // ---------------------------------------------------------------------

    /** A dotted white line along where it flew this tick (the client draws the glowing streak on top). */
    private void streak(ServerWorld sw, Vec3d from, Vec3d to) {
        Vec3d d = to.subtract(from);
        double len = d.length();
        int steps = Math.max(1, (int) (len / 0.45));
        for (int i = 0; i < steps; i++) {
            Vec3d p = from.add(d.multiply(i / (double) steps));
            sw.spawnParticles(WHITE, p.x, p.y, p.z, 1, 0.03, 0.03, 0.03, 0);
            if (i % 2 == 0) sw.spawnParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 1, 0.02, 0.02, 0.02, 0.005);
        }
    }

    /** Every hit: a white flash and a burst of sparks, and a ding that climbs with each mob in the chain. */
    private void impact(ServerWorld sw, Vec3d c, int n) {
        sw.spawnParticles(ParticleTypes.FLASH, c.x, c.y, c.z, 1, 0, 0, 0, 0);
        sw.spawnParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, 22, 0.1, 0.1, 0.1, 0.28);
        sw.spawnParticles(ParticleTypes.FIREWORK, c.x, c.y, c.z, 10, 0.1, 0.1, 0.1, 0.18);
        sw.spawnParticles(WHITE_BIG, c.x, c.y, c.z, 14, 0.35, 0.35, 0.35, 0);
        sw.spawnParticles(ParticleTypes.CRIT, c.x, c.y, c.z, 12, 0.3, 0.3, 0.3, 0.4);
        float climb = Math.min(n - 1, 8) * 0.11f;
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_ARROW_HIT_PLAYER, SoundCategory.PLAYERS, 0.9f, 0.9f + climb);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ITEM_TRIDENT_HIT, SoundCategory.PLAYERS, 0.9f, 1.3f + climb * 0.5f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_AMETHYST_BLOCK_HIT, SoundCategory.PLAYERS, 1.2f, 1.0f + climb);
    }

    /** The chain is over: one last bigger bloom of white, then it's gone. */
    private void finale(ServerWorld sw, Vec3d c) {
        sw.spawnParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, 30, 0.15, 0.15, 0.15, 0.2);
        sw.spawnParticles(WHITE_BIG, c.x, c.y, c.z, 20, 0.6, 0.6, 0.6, 0);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_AMETHYST_CLUSTER_BREAK, SoundCategory.PLAYERS, 1.0f, 1.5f);
        discard();
    }

    /** Hit a wall. */
    private void thunk(ServerWorld sw, Vec3d c) {
        sw.spawnParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, 14, 0.05, 0.05, 0.05, 0.15);
        sw.spawnParticles(WHITE, c.x, c.y, c.z, 10, 0.2, 0.2, 0.2, 0);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_ARROW_HIT, SoundCategory.PLAYERS, 1.0f, 1.3f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_AMETHYST_BLOCK_HIT, SoundCategory.PLAYERS, 0.8f, 0.8f);
        discard();
    }

    private void fizzle(ServerWorld sw) {
        Vec3d c = getPos();
        sw.spawnParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, 8, 0.15, 0.15, 0.15, 0.04);
        sw.spawnParticles(ParticleTypes.POOF, c.x, c.y, c.z, 4, 0.1, 0.1, 0.1, 0.02);
        discard();
    }

    /** A ring of white puffs thrown out sideways around {@code axis}, like the air the shot just broke. */
    private void ring(ServerWorld sw, Vec3d c, Vec3d axis, double speed, int count) {
        Vec3d side = Math.abs(axis.y) < 0.9 ? axis.crossProduct(new Vec3d(0, 1, 0)).normalize() : axis.crossProduct(new Vec3d(1, 0, 0)).normalize();
        Vec3d up = axis.crossProduct(side);
        for (int i = 0; i < count; i++) {
            double a = i * Math.PI * 2 / count;
            Vec3d out = side.multiply(Math.cos(a)).add(up.multiply(Math.sin(a)));
            sw.spawnParticles(ParticleTypes.CLOUD, c.x, c.y, c.z, 0, out.x, out.y, out.z, speed);
        }
    }

    private static void shake(ServerPlayerEntity player, float strength, int ticks) {
        if (ServerPlayNetworking.canSend(player, ShakePayload.ID)) ServerPlayNetworking.send(player, new ShakePayload(strength, ticks));
    }

    // ---------------------------------------------------------------------
    // Never saved (see shouldSave)
    // ---------------------------------------------------------------------

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {}

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {}
}
