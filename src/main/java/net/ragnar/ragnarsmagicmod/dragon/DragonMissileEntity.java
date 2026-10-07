package net.ragnar.ragnarsmagicmod.dragon;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.entity.IllusionEntity;
import net.ragnar.ragnarsmagicmod.network.ShakePayload;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * The little dragon. The server flies it from its pilot's keys and aim (see Dragon.SteerPayload): it's always pushing
 * forwards wherever the pilot looks, W pushes harder, S brakes, A/D slide it sideways, Space and Shift climb and dive.
 * It holds on to its momentum (low drag) and gets buffeted about, so it swings wide through every turn. It goes off on
 * touching anything, when the pilot clicks, or after {@link #LIFETIME} ticks.
 * <p>
 * On the client it eases between synced positions and remembers which way it's been facing, so its neck and tail can
 * curve through the turns (see DragonMissileRenderer).
 */
public class DragonMissileEntity extends Entity {
    public static final int LIFETIME = 100; // 5 seconds

    public static final float DIRECT_DAMAGE = 20f;
    public static final float SPLASH_DAMAGE = 12f, SPLASH_DAMAGE_EDGE = 4f;
    public static final double SPLASH_RADIUS = 6.0;

    // Flight tuning (per tick)
    public static final double LAUNCH_SPEED = 0.6;
    private static final double THRUST = 0.036;      // always on: it's a missile
    private static final double BOOST = 0.06;        // holding W
    private static final double BRAKE = 0.94;        // holding S
    private static final double STRAFE = 0.022;
    private static final double LIFT = 0.03;
    private static final double SINK = 0.035;
    private static final double GRAVITY = 0.01;
    private static final double DRAG = 0.96;         // the fairy's is 0.955 with three times the thrust: this glides
    private static final double MAX_SPEED = 1.4;
    private static final double TURBULENCE = 0.012;
    /** Half its size when it comes to touching things. */
    private static final double HIT_RADIUS = 0.45;

    // Server
    @Nullable private UUID pilotId;
    private long bornAt;
    private int life;
    private boolean exploded;
    private float inForward, inSideways, inYaw, inPitch;
    private boolean inUp, inDown;

    // Client
    /** True for the dragon this client is flying (set by DragonClient), whose trail would otherwise be in its face. */
    public static java.util.function.Predicate<Entity> PILOTED_HERE = e -> false;
    @Nullable private Vec3d lerpTo;
    private float lerpYaw, lerpPitch;
    /** Which way it faced, most recent first, one per tick (for the body to curve through turns). */
    public final float[] yawHistory = new float[64];
    public int historyIndex;

    public DragonMissileEntity(EntityType<? extends DragonMissileEntity> type, World world) {
        super(type, world);
        noClip = true;
        setNoGravity(true);
    }

    DragonMissileEntity(ServerWorld world, ServerPlayerEntity pilot) {
        this(Dragon.MISSILE, world);
        pilotId = pilot.getUuid();
        bornAt = world.getTime();
        inYaw = pilot.getYaw();
        inPitch = pilot.getPitch();
        Vec3d look = pilot.getRotationVec(1f);
        Vec3d eye = pilot.getEyePos();
        Vec3d start = eye.add(look.multiply(1.0));
        // Point blank into a wall: start right at it, so the first tick sets it off there
        BlockHitResult wall = world.raycast(new RaycastContext(eye, start, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, pilot));
        if (wall.getType() != HitResult.Type.MISS) start = wall.getPos().subtract(look.multiply(0.05));
        setPosition(start.subtract(0, getHeight() / 2, 0));
        resetPosition();
        setVelocity(look.multiply(LAUNCH_SPEED));
        face(getVelocity());
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {}

    @Override
    public boolean shouldSave() {
        return false;
    }

    @Override
    public boolean shouldRender(double distance) {
        return distance < 160 * 160;
    }

    /** Ticks since it was launched, by the world clock (so it counts even if it's stuck somewhere it can't tick). */
    long lifeTicks() {
        return getWorld().getTime() - bornAt;
    }

    void steer(Dragon.SteerPayload in) {
        inForward = MathHelper.clamp(in.forward(), -1f, 1f);
        inSideways = MathHelper.clamp(in.sideways(), -1f, 1f);
        inUp = in.up();
        inDown = in.down();
        inYaw = in.yaw();
        inPitch = MathHelper.clamp(in.pitch(), -90f, 90f);
    }

    /** The pilot clicked: go off right here. */
    void detonate() {
        if (getWorld() instanceof ServerWorld sw) explode(sw, getPos().add(0, getHeight() / 2, 0), null);
    }

    // ---------------------------------------------------------------------
    // Flying
    // ---------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (getWorld().isClient) {
            clientTick();
            return;
        }
        ServerWorld sw = (ServerWorld) getWorld();
        if (exploded) return;
        if (++life > LIFETIME) {
            explode(sw, center(), null);
            return;
        }

        Vec3d v = fly();
        Vec3d from = center();
        Vec3d dir = v.lengthSquared() < 1e-6 ? Vec3d.fromPolar(inPitch, inYaw) : v.normalize();
        Vec3d to = from.add(v);
        Vec3d reach = to.add(dir.multiply(HIT_RADIUS));

        // Whichever it reaches first this tick: a block or something alive
        BlockHitResult block = sw.raycast(new RaycastContext(from, reach, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this));
        double blockDist = block.getType() == HitResult.Type.MISS ? Double.MAX_VALUE : block.getPos().distanceTo(from);
        LivingEntity target = null;
        double targetDist = Double.MAX_VALUE;
        PlayerEntity pilot = pilot(sw);
        for (Entity e : sw.getOtherEntities(this, new Box(from, reach).expand(HIT_RADIUS + 1.0), e -> canHurt(e, pilot))) {
            Box box = e.getBoundingBox().expand(HIT_RADIUS);
            double d;
            if (box.contains(from)) d = 0;
            else {
                Optional<Vec3d> at = box.raycast(from, reach);
                if (at.isEmpty()) continue;
                d = at.get().distanceTo(from);
            }
            if (d < targetDist) {
                targetDist = d;
                target = (LivingEntity) e;
            }
        }
        if (target != null && targetDist <= blockDist) {
            explode(sw, from.add(dir.multiply(Math.max(0, targetDist - 0.1))), target);
            return;
        }
        if (block.getType() != HitResult.Type.MISS) {
            explode(sw, block.getPos().subtract(dir.multiply(0.3)), null);
            return;
        }

        setPosition(to.subtract(0, getHeight() / 2, 0));
        face(v);
        flightEffects(sw);
    }

    /** This tick's steering, gliding and buffeting. Returns (and keeps) the new velocity. */
    private Vec3d fly() {
        Vec3d look = Vec3d.fromPolar(inPitch, inYaw);
        Vec3d right = new Vec3d(-look.z, 0, look.x);
        right = right.lengthSquared() < 1e-6 ? Vec3d.ZERO : right.normalize();

        Vec3d v = getVelocity().multiply(DRAG);
        if (inForward < 0) v = v.multiply(BRAKE).add(look.multiply(THRUST * 0.4));
        else v = v.add(look.multiply(inForward > 0 ? BOOST : THRUST));
        v = v.add(right.multiply(-STRAFE * inSideways)); // A is positive sideways (left)
        if (inUp) v = v.add(0, LIFT, 0);
        if (inDown) v = v.add(0, -SINK, 0);
        v = v.add(0, -GRAVITY, 0);
        // A small dragon is thrown about by the air: gusts, and a slow wobble on top
        v = v.add(random.nextGaussian() * TURBULENCE, random.nextGaussian() * TURBULENCE, random.nextGaussian() * TURBULENCE);
        v = v.add(right.multiply(MathHelper.sin(life * 0.31f) * 0.012)).add(0, MathHelper.sin(life * 0.23f + 1f) * 0.008, 0);
        if (v.length() > MAX_SPEED) v = v.normalize().multiply(MAX_SPEED);
        setVelocity(v);
        return v;
    }

    private void face(Vec3d v) {
        if (v.lengthSquared() < 1e-6) return;
        Vec3d d = v.normalize();
        setYaw((float) (MathHelper.atan2(-d.x, d.z) * MathHelper.DEGREES_PER_RADIAN));
        setPitch((float) (-Math.asin(MathHelper.clamp(d.y, -1, 1)) * MathHelper.DEGREES_PER_RADIAN));
    }

    // ---------------------------------------------------------------------
    // Going off
    // ---------------------------------------------------------------------

    /** The blast: {@code direct} (if it flew into something) takes the full hit, everything else around takes less. */
    private void explode(ServerWorld sw, Vec3d c, @Nullable LivingEntity direct) {
        if (exploded) return;
        exploded = true;
        PlayerEntity pilot = pilot(sw);
        Vec3d travel = getVelocity().lengthSquared() < 1e-6 ? Vec3d.ZERO : getVelocity().normalize();

        if (direct != null) {
            hurt(sw, direct, DIRECT_DAMAGE, pilot);
            fling(direct, travel.multiply(1, 0, 1).multiply(1.6).add(0, 0.6, 0));
        }
        for (LivingEntity e : sw.getEntitiesByClass(LivingEntity.class, new Box(c, c).expand(SPLASH_RADIUS + 1), e -> e != direct && canHurt(e, pilot))) {
            Vec3d at = e.getBoundingBox().getCenter();
            double d = at.distanceTo(c);
            if (d > SPLASH_RADIUS || !canSee(sw, c, e)) continue;
            float k = (float) (1.0 - d / SPLASH_RADIUS);
            hurt(sw, e, MathHelper.lerp(k, SPLASH_DAMAGE_EDGE, SPLASH_DAMAGE), pilot);
            Vec3d out = at.subtract(c).multiply(1, 0, 1);
            out = out.lengthSquared() < 1e-4 ? Vec3d.ZERO : out.normalize();
            fling(e, out.multiply(0.7 + 1.2 * k).add(0, 0.4 + 0.5 * k, 0));
        }

        // The big purple End blast itself is drawn on each client (DragonBlasts); this is just the punch
        sw.spawnParticles(ParticleTypes.FLASH, c.x, c.y, c.z, 2, 0.2, 0.2, 0.2, 0);
        sw.spawnParticles(ParticleTypes.EXPLOSION, c.x, c.y, c.z, 4, 1.0, 0.8, 1.0, 0);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 3.5f, 0.7f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_DRAGON_FIREBALL_EXPLODE, SoundCategory.PLAYERS, 2.5f, 0.8f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_END_PORTAL_SPAWN, SoundCategory.PLAYERS, 0.7f, 1.4f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 2.0f, 0.5f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_ENDER_DRAGON_HURT, SoundCategory.PLAYERS, 2.0f, 1.3f);
        Dragon.blast(sw, c, (float) SPLASH_RADIUS);
        ShakePayload.around(sw, c, 14, 0.9f, 18);

        if (pilotId != null) Dragon.end(pilot instanceof ServerPlayerEntity sp ? sp : null, pilotId, this);
        discard();
    }

    private void hurt(ServerWorld sw, LivingEntity e, float amount, @Nullable PlayerEntity pilot) {
        e.timeUntilRegen = 0;
        e.damage(sw.getDamageSources().explosion(this, pilot), amount);
    }

    private static void fling(LivingEntity e, Vec3d push) {
        double resist = e.getAttributeValue(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE);
        e.setVelocity(e.getVelocity().add(push.multiply(1.0 - MathHelper.clamp(resist, 0, 1))));
        e.velocityModified = true;
    }

    private boolean canHurt(Entity e, @Nullable PlayerEntity pilot) {
        if (!(e instanceof LivingEntity le) || !le.isAlive() || le.isSpectator() || e instanceof ArmorStandEntity) return false;
        if (pilotId != null) {
            if (e.getUuid().equals(pilotId) || e instanceof IllusionEntity) return false;
            if (e instanceof TameableEntity pet && pilotId.equals(pet.getOwnerUuid())) return false;
        }
        return pilot == null || !e.isTeammate(pilot);
    }

    private boolean canSee(ServerWorld sw, Vec3d from, Entity e) {
        Vec3d to = e.getBoundingBox().getCenter();
        return sw.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this)).getType() == HitResult.Type.MISS;
    }

    @Nullable
    private PlayerEntity pilot(ServerWorld sw) {
        return pilotId == null ? null : sw.getPlayerByUuid(pilotId);
    }

    private Vec3d center() {
        return getPos().add(0, getHeight() / 2, 0);
    }

    /** Its wings beating, and a growl now and then. */
    private void flightEffects(ServerWorld sw) {
        Vec3d c = center();
        if (life % 9 == 0) sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_ENDER_DRAGON_FLAP, SoundCategory.PLAYERS, 0.7f, 1.6f + random.nextFloat() * 0.2f);
        if (life == 2 || life % 37 == 0) sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.PLAYERS, 0.5f, 1.9f);
        if (life == LIFETIME - 30) sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_ENDER_DRAGON_AMBIENT, SoundCategory.PLAYERS, 0.8f, 1.8f);
    }

    // ---------------------------------------------------------------------
    // Client
    // ---------------------------------------------------------------------

    /** Synced positions land here; the next tick moves there, so rendering eases across the gap. */
    @Override
    public void updateTrackedPositionAndAngles(double x, double y, double z, float yaw, float pitch, int steps) {
        lerpTo = new Vec3d(x, y, z);
        lerpYaw = yaw;
        lerpPitch = pitch;
    }

    private void clientTick() {
        if (lerpTo != null) {
            setPosition(lerpTo);
            setYaw(lerpYaw);
            setPitch(lerpPitch);
            lerpTo = null;
        }
        if (age <= 1) java.util.Arrays.fill(yawHistory, getYaw()); // straight to begin with
        historyIndex = (historyIndex + 1) & 63;
        yawHistory[historyIndex] = getYaw();

        // A trail of purple dragon's breath and ender sparks streaming off behind it. Not for its own pilot: their
        // camera follows right behind along the same path, so the trail would fill their screen
        Vec3d moved = getPos().subtract(prevX, prevY, prevZ);
        if (moved.lengthSquared() < 1e-4 || PILOTED_HERE.test(this)) return;
        Vec3d back = center().subtract(moved.normalize().multiply(1.1));
        getWorld().addParticle(ParticleTypes.DRAGON_BREATH, back.x, back.y, back.z,
                random.nextGaussian() * 0.01, random.nextGaussian() * 0.01, random.nextGaussian() * 0.01);
        if (random.nextInt(2) == 0) {
            getWorld().addParticle(ParticleTypes.PORTAL, back.x, back.y, back.z, random.nextGaussian() * 0.2, random.nextGaussian() * 0.2, random.nextGaussian() * 0.2);
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
