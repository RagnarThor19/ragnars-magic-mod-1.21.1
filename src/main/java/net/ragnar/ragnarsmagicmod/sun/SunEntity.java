package net.ragnar.ragnarsmagicmod.sun;

import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.entity.IllusionEntity;
import net.ragnar.ragnarsmagicmod.network.ShakePayload;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A Tome of the Sun's sun: a solid ball of molten light (drawn by SunRenderer), through four stages.
 * <ol>
 *   <li><b>Igniting</b> ({@link #IGNITE_TICKS}): it kindles in front of the caster, drawing flame in from all round,
 *       swelling to {@link #IGNITED_RADIUS} and following their aim.</li>
 *   <li><b>Drifting</b>: let go, it drifts slowly onward the way they aimed, growing as it goes to {@link #END_RADIUS},
 *       burning everything it touches and setting alight what comes close.</li>
 *   <li><b>Collapsing</b> ({@link #COLLAPSE_TICKS}): full grown (or run into the land), it stops and caves in on itself.</li>
 *   <li><b>Nova</b> ({@link #NOVA_TICKS}): it bursts in a blast of flame, hurting and throwing everything within
 *       {@link #NOVA_RADIUS}.</li>
 * </ol>
 * The server only moves it and does the damage; every particle is made on each client from its tracked stage and
 * size, so it costs the network almost nothing. It never sets the world alight or changes a block.
 */
public class SunEntity extends Entity {
    public static final int IGNITE_TICKS = 30, COLLAPSE_TICKS = 10, NOVA_TICKS = 18;
    public static final float START_RADIUS = 0.25f, IGNITED_RADIUS = 1.6f, END_RADIUS = 5.0f;
    public static final double SPEED = 0.18, HOLD = 3.0;
    public static final float GROWTH = 0.02f;
    /** 30 damage a second to anything inside it: 15 every half second. */
    public static final float BURN_DAMAGE = 15f;
    public static final int BURN_INTERVAL = 10;
    /** How far past its surface the heat sets things alight. */
    public static final double HEAT_RANGE = 3;
    public static final double NOVA_RADIUS = 9;
    public static final float NOVA_DAMAGE = 22f, NOVA_EDGE = 6f;

    public static final int IGNITING = 0, DRIFTING = 1, COLLAPSING = 2, NOVA = 3;
    private static final TrackedData<Integer> STAGE = DataTracker.registerData(SunEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Float> RADIUS = DataTracker.registerData(SunEntity.class, TrackedDataHandlerRegistry.FLOAT);

    // Server
    @Nullable private UUID ownerId;
    private Vec3d forward = new Vec3d(0, 0, 1);
    private int stageAge;
    private float collapseFrom;
    private final Map<UUID, Integer> lastBurn = new HashMap<>();

    // Client
    public int clientStageAge;
    private int lastClientStage = -1;

    public SunEntity(EntityType<? extends SunEntity> type, World world) {
        super(type, world);
        noClip = true;
        setNoGravity(true);
    }

    public SunEntity(World world, PlayerEntity caster) {
        this(Sun.SUN, world);
        ownerId = caster.getUuid();
        forward = caster.getRotationVector().normalize();
        Vec3d at = caster.getEyePos().add(forward.multiply(HOLD + START_RADIUS));
        refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
        dataTracker.set(RADIUS, START_RADIUS);
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        builder.add(STAGE, IGNITING);
        builder.add(RADIUS, START_RADIUS);
    }

    public int stage() {
        return dataTracker.get(STAGE);
    }

    public float radius() {
        return dataTracker.get(RADIUS);
    }

    private void stage(int s) {
        dataTracker.set(STAGE, s);
        stageAge = 0;
    }

    @Override
    public boolean shouldRender(double distance) {
        return distance < 256 * 256;
    }

    @Override
    public Box getVisibilityBoundingBox() {
        return getBoundingBox().expand(radius() * 2.6 + 2 + (stage() == NOVA ? NOVA_RADIUS : 0));
    }

    @Override
    public void tick() {
        super.tick();
        if (getWorld().isClient) {
            if (stage() != lastClientStage) {
                lastClientStage = stage();
                clientStageAge = 0;
                if (stage() == NOVA) SunParticles.nova(this);
            } else {
                clientStageAge++;
            }
            SunParticles.tick(this);
            return;
        }
        ServerWorld world = (ServerWorld) getWorld();
        PlayerEntity owner = ownerId == null ? null : world.getPlayerByUuid(ownerId);
        stageAge++;
        switch (stage()) {
            case IGNITING -> {
                float t = MathHelper.clamp(stageAge / (float) IGNITE_TICKS, 0f, 1f);
                float r = MathHelper.lerp(1 - (1 - t) * (1 - t) * (1 - t), START_RADIUS, IGNITED_RADIUS);
                dataTracker.set(RADIUS, r);
                if (owner != null && owner.isAlive()) {
                    forward = owner.getRotationVector().normalize();
                    Vec3d at = owner.getEyePos().add(forward.multiply(HOLD + r));
                    setPosition(at.x, at.y, at.z);
                }
                if (stageAge % 6 == 0) sound(SoundEvents.BLOCK_FIRE_AMBIENT, 1.5f, 0.6f + t * 0.6f);
                if (stageAge >= IGNITE_TICKS) {
                    stage(DRIFTING);
                    sound(SoundEvents.ENTITY_BLAZE_SHOOT, 2.0f, 0.5f);
                    sound(SoundEvents.ITEM_FIRECHARGE_USE, 2.0f, 0.6f);
                    sound(SoundEvents.BLOCK_BEACON_POWER_SELECT, 1.5f, 0.5f);
                }
            }
            case DRIFTING -> {
                Vec3d at = getPos().add(forward.multiply(SPEED));
                setPosition(at.x, at.y, at.z);
                float r = radius() + GROWTH;
                dataTracker.set(RADIUS, r);
                if (stageAge % 20 == 0) sound(SoundEvents.BLOCK_FIRE_AMBIENT, 2.5f, 0.5f);
                if (stageAge % 30 == 15) sound(SoundEvents.ENTITY_BLAZE_BURN, 1.8f, 0.45f);
                if (r >= END_RADIUS || plowedIntoLand(world, r)) {
                    collapseFrom = r;
                    stage(COLLAPSING);
                    sound(SoundEvents.BLOCK_BEACON_DEACTIVATE, 2.5f, 0.5f);
                    sound(SoundEvents.BLOCK_RESPAWN_ANCHOR_DEPLETE.value(), 2.5f, 0.6f);
                }
            }
            case COLLAPSING -> {
                float t = MathHelper.clamp(stageAge / (float) COLLAPSE_TICKS, 0f, 1f);
                dataTracker.set(RADIUS, collapseFrom * (1 - 0.3f * t * t));
                if (stageAge >= COLLAPSE_TICKS) {
                    stage(NOVA);
                    nova(world, owner);
                }
            }
            case NOVA -> {
                if (stageAge >= NOVA_TICKS) discard();
                return;
            }
            default -> {}
        }
        burn(world, owner);
    }

    /** True once its middle is buried in the land: a hillside stops it. */
    private boolean plowedIntoLand(ServerWorld world, float r) {
        Vec3d c = getPos();
        double d = r * 0.5;
        Vec3d[] probes = {c, c.add(d, 0, 0), c.add(-d, 0, 0), c.add(0, d, 0), c.add(0, -d, 0), c.add(0, 0, d), c.add(0, 0, -d)};
        int solid = 0;
        for (Vec3d p : probes) {
            BlockPos bp = BlockPos.ofFloored(p);
            BlockState s = world.getBlockState(bp);
            if (!s.getCollisionShape(world, bp).isEmpty()) solid++;
        }
        return solid >= 3;
    }

    /** Burns everything inside it, and sets alight what's merely close. */
    private void burn(ServerWorld world, @Nullable PlayerEntity owner) {
        float r = radius();
        Box reach = getBoundingBox().expand(r + HEAT_RANGE + 1);
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, reach, e -> e.isAlive() && !e.isSpectator())) {
            if (friendly(e, owner)) continue;
            double d = e.getBoundingBox().getCenter().distanceTo(getPos()) - Math.max(e.getWidth(), e.getHeight()) * 0.5;
            if (d > r + HEAT_RANGE) continue;
            if (d > r) {
                if (e.getFireTicks() < 40) e.setOnFireFor(3);
                continue;
            }
            e.setOnFireFor(6);
            Integer last = lastBurn.get(e.getUuid());
            if (last != null && age - last < BURN_INTERVAL) continue;
            lastBurn.put(e.getUuid(), age);
            e.timeUntilRegen = 0;
            e.damage(source(world, owner), BURN_DAMAGE);
        }
    }

    /** The burst at the end: a blast of flame, hurting and throwing everything near. */
    private void nova(ServerWorld world, @Nullable PlayerEntity owner) {
        Vec3d c = getPos();
        sound(SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 4f, 0.6f);
        sound(SoundEvents.ITEM_FIRECHARGE_USE, 3f, 0.5f);
        sound(SoundEvents.ENTITY_BLAZE_DEATH, 2.5f, 0.5f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.PLAYERS, 3f, 1.4f);
        ShakePayload.around(world, c, 36, 1.2f, 16);
        // One flash for everyone, so even someone looking away sees the sky light up
        world.spawnParticles(ParticleTypes.FLASH, c.x, c.y, c.z, 2, 0.5, 0.5, 0.5, 0);
        Box box = getBoundingBox().expand(NOVA_RADIUS + 1);
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, box, e -> e.isAlive() && !e.isSpectator())) {
            if (friendly(e, owner)) continue;
            double d = e.getBoundingBox().getCenter().distanceTo(c) - Math.max(e.getWidth(), e.getHeight()) * 0.5;
            if (d > NOVA_RADIUS) continue;
            float f = (float) MathHelper.clamp(d / NOVA_RADIUS, 0, 1);
            e.timeUntilRegen = 0;
            e.damage(source(world, owner), MathHelper.lerp(f, NOVA_DAMAGE, NOVA_EDGE));
            e.setOnFireFor(6);
            Vec3d out = e.getPos().subtract(c);
            out = out.lengthSquared() < 1e-4 ? new Vec3d(0, 1, 0) : out.normalize();
            double push = 1.4 * (1 - f) + 0.3;
            e.setVelocity(e.getVelocity().add(out.x * push, 0.35 + 0.4 * (1 - f), out.z * push));
            e.velocityModified = true;
        }
    }

    private DamageSource source(ServerWorld world, @Nullable PlayerEntity owner) {
        return owner != null ? world.getDamageSources().indirectMagic(this, owner) : world.getDamageSources().magic();
    }

    private boolean friendly(LivingEntity e, @Nullable PlayerEntity owner) {
        if (ownerId != null && e.getUuid().equals(ownerId)) return true;
        if (e instanceof IllusionEntity) return true;
        if (e instanceof TameableEntity pet && ownerId != null && ownerId.equals(pet.getOwnerUuid())) return true;
        return owner != null && e.isTeammate(owner);
    }

    private void sound(net.minecraft.sound.SoundEvent sound, float volume, float pitch) {
        getWorld().playSound(null, getX(), getY(), getZ(), sound, SoundCategory.PLAYERS, volume, pitch);
    }

    /** For tests: point it a given way (as if aimed there). */
    public void aim(Vec3d dir) {
        forward = dir.normalize();
    }

    Random rand() {
        return random;
    }

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {}

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {}
}
