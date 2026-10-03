package net.ragnar.ragnarsmagicmod.jaunting;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.UUID;

/**
 * The Tome of Jaunting's kunai. Flies fast and dead straight for a good while, then gravity and air bite hard and it
 * noses down. It sticks in blocks like an arrow, and in mobs and players too - riding along with them, poking out
 * of where it hit - and it never despawns: it stays until its owner jaunts to it or dispels it.
 */
public class JauntingKunaiEntity extends PersistentProjectileEntity {
    public static final float DAMAGE = 8f;
    public static final float SPEED = 3.4f;
    /** How long it flies straight (about 55 blocks) before it starts to curve down. */
    static final int STRAIGHT_TICKS = 18;
    /** Ticks a kunai waits for a host it can't find (logged off, unloaded elsewhere) before falling out. */
    private static final int HOST_PATIENCE = 60;
    private static final float RAD = MathHelper.RADIANS_PER_DEGREE;

    /** The network id of the entity it's stuck in, or -1. */
    private static final TrackedData<Integer> HOST = DataTracker.registerData(JauntingKunaiEntity.class, TrackedDataHandlerRegistry.INTEGER);
    /** Where on the host it's stuck, and which way it points, both turned with the host's body. */
    private static final TrackedData<Vector3f> HOST_OFFSET = DataTracker.registerData(JauntingKunaiEntity.class, TrackedDataHandlerRegistry.VECTOR3F);
    private static final TrackedData<Vector3f> HOST_DIR = DataTracker.registerData(JauntingKunaiEntity.class, TrackedDataHandlerRegistry.VECTOR3F);

    @Nullable private UUID ownerId;
    @Nullable private UUID hostId;
    private int hostLost;
    private int flightTicks;

    public JauntingKunaiEntity(EntityType<? extends JauntingKunaiEntity> type, World world) {
        super(type, world);
        pickupType = PickupPermission.DISALLOWED;
    }

    public JauntingKunaiEntity(World world, LivingEntity owner) {
        super(Jaunting.KUNAI, owner, world, new ItemStack(Jaunting.KUNAI_ITEM), null);
        ownerId = owner.getUuid();
        pickupType = PickupPermission.DISALLOWED;
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(HOST, -1);
        builder.add(HOST_OFFSET, new Vector3f());
        builder.add(HOST_DIR, new Vector3f(0, 0, 1));
    }

    @Override
    protected ItemStack getDefaultItemStack() {
        return new ItemStack(Jaunting.KUNAI_ITEM);
    }

    @Nullable
    public UUID getOwnerId() {
        return ownerId;
    }

    // ---------------------------------------------------------------------
    // Flight
    // ---------------------------------------------------------------------

    @Override
    protected double getGravity() {
        if (flightTicks < STRAIGHT_TICKS) return 0.0;
        return Math.min(0.008 + (flightTicks - STRAIGHT_TICKS) * 0.004, 0.1);
    }

    @Override
    protected float getDragInWater() {
        return 0.8f;
    }

    /** Never despawns, unlike an arrow lying in the ground. */
    @Override
    protected void age() {}

    @Override
    protected SoundEvent getHitSound() {
        return SoundEvents.ITEM_TRIDENT_HIT_GROUND;
    }

    public boolean isFlying() {
        return !inGround && !isInHost();
    }

    /** Which way the blade points. */
    public Vec3d facing() {
        float yaw = getYaw() * RAD, pitch = getPitch() * RAD;
        return new Vec3d(MathHelper.sin(yaw) * MathHelper.cos(pitch), MathHelper.sin(pitch), MathHelper.cos(yaw) * MathHelper.cos(pitch));
    }

    @Override
    public void tick() {
        if (isInHost()) {
            tickInHost();
            return;
        }
        super.tick();
        if (isRemoved()) return;
        if (!inGround) {
            flightTicks++;
            // Past the straight stretch the air slowly takes its speed while gravity grows, so it bends down more and more
            if (flightTicks > STRAIGHT_TICKS) {
                Vec3d v = getVelocity();
                setVelocity(v.x * 0.985, v.y, v.z * 0.985);
            }
        }
        if (getWorld().isClient) sparks();
        else keepMark();
    }

    @Override
    protected boolean canHit(Entity entity) {
        return super.canHit(entity) && !entity.getUuid().equals(ownerId) && !(entity instanceof JauntingKunaiEntity);
    }

    // ---------------------------------------------------------------------
    // Hitting things
    // ---------------------------------------------------------------------

    @Override
    protected void onEntityHit(EntityHitResult hit) {
        if (!(getWorld() instanceof ServerWorld sw)) {
            setVelocity(Vec3d.ZERO); // the server says where it ends up
            return;
        }
        Entity target = hit.getEntity();
        Entity owner = getOwner();
        DamageSource source = getDamageSources().arrow(this, owner != null ? owner : this);
        boolean hurt = target.damage(source, DAMAGE);
        if (!hurt && (target.getType() == EntityType.ENDERMAN || target instanceof LivingEntity l && l.isBlocking())) {
            // Endermen dodge it and shields knock it away, same as an arrow
            setVelocity(getVelocity().multiply(-0.1));
            setYaw(getYaw() + 180f);
            prevYaw += 180f;
            return;
        }

        Vec3d at = hitPoint(target);
        sw.playSound(null, at.x, at.y, at.z, SoundEvents.ITEM_TRIDENT_HIT, SoundCategory.PLAYERS, 1.0f, 1.25f);
        sw.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.PLAYERS, 0.35f, 1.9f);
        sw.spawnParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, 18, 0.15, 0.15, 0.15, 0.5);
        if (owner instanceof PlayerEntity p && p != target && target instanceof PlayerEntity) {
            sw.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_ARROW_HIT_PLAYER, SoundCategory.PLAYERS, 0.45f, 0.9f);
        }

        if (target instanceof LivingEntity living && living.isAlive()) {
            stickInto(living, at);
        } else {
            // Killed it (or it wasn't alive): falls out where it hit
            setPosition(at);
            setVelocity(getVelocity().multiply(-0.04).add(0, -0.08, 0));
            flightTicks = Math.max(flightTicks, STRAIGHT_TICKS + 6);
        }
    }

    /** Where it actually went into {@code target}: on its hitbox along the flight line, a little way in. */
    private Vec3d hitPoint(Entity target) {
        Vec3d v = getVelocity();
        Vec3d dir = v.lengthSquared() < 1e-6 ? facing() : v.normalize();
        Vec3d start = getPos().subtract(dir.multiply(0.5));
        Box box = target.getBoundingBox();
        Vec3d on = box.raycast(start, start.add(v).add(dir.multiply(3))).orElseGet(box::getCenter);
        Vec3d in = on.add(dir.multiply(0.12));
        return new Vec3d(MathHelper.clamp(in.x, box.minX, box.maxX), MathHelper.clamp(in.y, box.minY, box.maxY),
                MathHelper.clamp(in.z, box.minZ, box.maxZ));
    }

    @Override
    protected void onBlockHit(BlockHitResult hit) {
        super.onBlockHit(hit);
        if (getWorld() instanceof ServerWorld sw) {
            Vec3d p = getPos();
            sw.playSound(null, p.x, p.y, p.z, SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.PLAYERS, 0.3f, 2.0f);
            sw.spawnParticles(ParticleTypes.ELECTRIC_SPARK, p.x, p.y, p.z, 14, 0.1, 0.1, 0.1, 0.45);
        }
    }

    // ---------------------------------------------------------------------
    // Stuck in a mob or player
    // ---------------------------------------------------------------------

    public boolean isInHost() {
        return getWorld().isClient ? dataTracker.get(HOST) >= 0 : hostId != null;
    }

    /** The entity it's stuck in, if that's loaded. */
    @Nullable
    public Entity host() {
        if (getWorld() instanceof ServerWorld sw) return hostId == null ? null : sw.getEntity(hostId);
        int id = dataTracker.get(HOST);
        return id < 0 ? null : getWorld().getEntityById(id);
    }

    private void stickInto(LivingEntity host, Vec3d at) {
        Vec3d v = getVelocity();
        Vec3d dir = v.lengthSquared() < 1e-6 ? facing() : v.normalize();
        float yaw = hostYaw(host, 1f) * RAD;
        dataTracker.set(HOST_OFFSET, at.subtract(host.getPos()).rotateY(yaw).toVector3f());
        dataTracker.set(HOST_DIR, dir.rotateY(yaw).toVector3f());
        dataTracker.set(HOST, host.getId());
        hostId = host.getUuid();
        hostLost = 0;
        inGround = false;
        setVelocity(Vec3d.ZERO);
        followHost(host);
    }

    private void tickInHost() {
        Entity host = host();
        if (getWorld().isClient) {
            if (host != null) followHost(host);
            sparks();
            return;
        }
        if (host == null) {
            if (++hostLost > HOST_PATIENCE) fallOut();
            else keepMark();
            return;
        }
        hostLost = 0;
        if (!host.isAlive()) {
            fallOut();
            return;
        }
        if (dataTracker.get(HOST) != host.getId()) dataTracker.set(HOST, host.getId());
        followHost(host);
        keepMark();
    }

    private void followHost(Entity host) {
        setPosition(stuckPos(host, 1f));
        setVelocity(Vec3d.ZERO);
        Vec3d d = stuckDir(host, 1f);
        float yaw = (float) (MathHelper.atan2(d.x, d.z) * MathHelper.DEGREES_PER_RADIAN);
        float pitch = (float) (MathHelper.atan2(d.y, d.horizontalLength()) * MathHelper.DEGREES_PER_RADIAN);
        setYaw(yaw);
        setPitch(pitch);
        prevYaw = yaw;
        prevPitch = pitch;
    }

    /** Lets go of its host and drops to the ground. */
    private void fallOut() {
        Vec3d dir = facing();
        hostId = null;
        hostLost = 0;
        dataTracker.set(HOST, -1);
        setVelocity(dir.multiply(0.05).add(0, -0.05, 0));
        flightTicks = Math.max(flightTicks, STRAIGHT_TICKS + 6);
    }

    /** Which way a host is facing, for turning the kunai with it. */
    public static float hostYaw(Entity host, float tickDelta) {
        if (host instanceof LivingEntity living) return MathHelper.lerpAngleDegrees(tickDelta, living.prevBodyYaw, living.bodyYaw);
        return host.getYaw(tickDelta);
    }

    /** Where it sits on its host this frame. */
    public Vec3d stuckPos(Entity host, float tickDelta) {
        Vector3f o = dataTracker.get(HOST_OFFSET);
        return host.getLerpedPos(tickDelta).add(new Vec3d(o).rotateY(-hostYaw(host, tickDelta) * RAD));
    }

    /** Which way it points out of its host this frame. */
    public Vec3d stuckDir(Entity host, float tickDelta) {
        return new Vec3d(dataTracker.get(HOST_DIR)).rotateY(-hostYaw(host, tickDelta) * RAD);
    }

    // ---------------------------------------------------------------------
    // Bookkeeping
    // ---------------------------------------------------------------------

    /** Keeps the owner's mark pointing here - or, if the owner has moved on to another kunai, goes away. */
    private void keepMark() {
        if (!(getWorld() instanceof ServerWorld sw)) return;
        if (ownerId == null || !JauntingMarks.isCurrent(sw.getServer(), ownerId, getUuid())) {
            discard();
            return;
        }
        JauntingMarks.track(sw.getServer(), ownerId, getUuid(), sw.getRegistryKey(), getPos());
    }

    @Override
    public void remove(RemovalReason reason) {
        // Lost for good (fell out of the world, /kill...): let the owner know, and free them to throw another
        if ((reason == RemovalReason.KILLED || reason == RemovalReason.DISCARDED) && ownerId != null
                && getWorld() instanceof ServerWorld sw && JauntingMarks.forget(sw.getServer(), ownerId, getUuid())) {
            PlayerEntity owner = sw.getServer().getPlayerManager().getPlayer(ownerId);
            if (owner != null) owner.sendMessage(Text.literal("Your kunai was lost.").formatted(Formatting.GRAY), true);
        }
        super.remove(reason);
    }

    /** Gone in a crackle: dispelled, or its owner just arrived. */
    public void fizzle(ServerWorld sw) {
        Vec3d p = getPos();
        sw.spawnParticles(ParticleTypes.ELECTRIC_SPARK, p.x, p.y, p.z, 16, 0.12, 0.12, 0.12, 0.35);
        sw.spawnParticles(ParticleTypes.SMOKE, p.x, p.y, p.z, 4, 0.05, 0.05, 0.05, 0.01);
        discard();
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        if (ownerId != null) nbt.putUuid("JauntOwner", ownerId);
        if (hostId != null) {
            nbt.putUuid("JauntHost", hostId);
            Vector3f o = dataTracker.get(HOST_OFFSET), d = dataTracker.get(HOST_DIR);
            nbt.putFloat("HostOX", o.x);
            nbt.putFloat("HostOY", o.y);
            nbt.putFloat("HostOZ", o.z);
            nbt.putFloat("HostDX", d.x);
            nbt.putFloat("HostDY", d.y);
            nbt.putFloat("HostDZ", d.z);
        }
        nbt.putInt("FlightTicks", flightTicks);
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        pickupType = PickupPermission.DISALLOWED;
        ownerId = nbt.containsUuid("JauntOwner") ? nbt.getUuid("JauntOwner") : null;
        hostId = nbt.containsUuid("JauntHost") ? nbt.getUuid("JauntHost") : null;
        if (hostId != null) {
            dataTracker.set(HOST_OFFSET, new Vector3f(nbt.getFloat("HostOX"), nbt.getFloat("HostOY"), nbt.getFloat("HostOZ")));
            dataTracker.set(HOST_DIR, new Vector3f(nbt.getFloat("HostDX"), nbt.getFloat("HostDY"), nbt.getFloat("HostDZ")));
        }
        flightTicks = nbt.getInt("FlightTicks");
    }

    // ---------------------------------------------------------------------
    // Client
    // ---------------------------------------------------------------------

    /** A crackling trail in flight; the odd spark off the handle once it's stuck. */
    private void sparks() {
        World w = getWorld();
        Random r = random;
        if (isFlying()) {
            if (age < 3) return; // still right in the thrower's face
            Vec3d v = getVelocity();
            for (int i = 0; i < 3; i++) {
                double t = r.nextDouble();
                w.addParticle(ParticleTypes.ELECTRIC_SPARK, getX() - v.x * t, getY() - v.y * t, getZ() - v.z * t,
                        r.nextGaussian() * 0.04, r.nextGaussian() * 0.04, r.nextGaussian() * 0.04);
            }
            if (r.nextInt(3) == 0) {
                w.addParticle(ParticleTypes.END_ROD, getX() - v.x * 0.5, getY() - v.y * 0.5, getZ() - v.z * 0.5,
                        r.nextGaussian() * 0.01, r.nextGaussian() * 0.01, r.nextGaussian() * 0.01);
            }
        } else if (r.nextInt(18) == 0) {
            Vec3d at = getPos().subtract(facing().multiply(0.3));
            for (int i = 0; i < 2 + r.nextInt(3); i++) {
                w.addParticle(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z,
                        r.nextGaussian() * 0.08, 0.04 + r.nextDouble() * 0.06, r.nextGaussian() * 0.08);
            }
        }
    }
}
