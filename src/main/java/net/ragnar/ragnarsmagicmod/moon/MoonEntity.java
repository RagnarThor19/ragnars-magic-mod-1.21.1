package net.ragnar.ragnarsmagicmod.moon;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LightBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.BlockStateParticleEffect;
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
import net.ragnar.ragnarsmagicmod.entity.IllusionEntity;
import net.ragnar.ragnarsmagicmod.network.ShakePayload;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * The moon. It lives in three stages:
 * <ol>
 *   <li><b>Forming</b> ({@link #FORM_TICKS}) - it gathers out of the night high over the landing spot.</li>
 *   <li><b>Falling</b> ({@link #FALL_TICKS}) - it sinks onto the landing spot, slowly and then faster. Its pull lifts
 *       every creature within {@link #PULL_RADIUS} of the spot up off the ground and sweeps them round in a ring under
 *       it.</li>
 *   <li><b>Afterglow</b> ({@link #AFTERGLOW_TICKS}) - it's landed: everything it lifted was slammed into the ground
 *       for {@link #SLAM_DAMAGE} more, a shockwave hit everything within {@link #IMPACT_RADIUS} (hardest in the
 *       middle) and the crater glows with moonlight for a while.</li>
 * </ol>
 * The server moves it and does all the hurting; the client draws the moon, its shadow and its light (MoonRenderer).
 * It never breaks blocks, and never hurts its caster, their pets or their teammates. It lights its surroundings as it
 * falls, with a light block it moves along with it (only ever into air, and always taken away again).
 */
public class MoonEntity extends Entity {
    public static final int FORM_TICKS = 24;
    public static final int FALL_TICKS = 60;
    public static final int AFTERGLOW_TICKS = 90;
    /** The moon's size, in blocks from its middle. */
    public static final float RADIUS = 3.5f;
    /** How high over the landing spot it forms, when there's room. */
    public static final double START_HEIGHT = 22;

    public static final double PULL_RADIUS = 10;
    public static final double IMPACT_RADIUS = 11;
    public static final float IMPACT_DAMAGE = 26f, IMPACT_DAMAGE_EDGE = 8f;
    /** Extra for being lifted up and slammed back down. */
    public static final float SLAM_DAMAGE = 10f;
    /** Where the lifted ring of creatures circles: how far out from the middle, and how high. */
    private static final double ORBIT_RADIUS = 5.5, ORBIT_HEIGHT = 4.5;

    public static final int FORMING = 0, FALLING = 1, AFTERGLOW = 2;
    private static final TrackedData<Integer> STAGE = DataTracker.registerData(MoonEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Vector3f> GROUND = DataTracker.registerData(MoonEntity.class, TrackedDataHandlerRegistry.VECTOR3F);
    private static final TrackedData<Float> START_Y = DataTracker.registerData(MoonEntity.class, TrackedDataHandlerRegistry.FLOAT);

    private static final DustParticleEffect SILVER = new DustParticleEffect(new Vector3f(0.85f, 0.9f, 1.0f), 1.6f);
    private static final DustParticleEffect PALE_BLUE = new DustParticleEffect(new Vector3f(0.55f, 0.7f, 1.0f), 1.2f);

    /** Something the moon has lifted: which way round the ring it is, and for how long it's been up. */
    private static final class Lifted {
        double angle;
        int ticks;

        Lifted(double angle) {
            this.angle = angle;
        }
    }

    // Server
    @Nullable private UUID ownerId;
    private int stageAge;
    private final Map<UUID, Lifted> lifted = new HashMap<>();
    @Nullable private BlockPos light;

    // Client
    @Nullable private Vec3d lerpTo;
    /** Ticks since the stage last changed, as this client saw it. */
    public int clientStageAge;

    public MoonEntity(EntityType<? extends MoonEntity> type, World world) {
        super(type, world);
        noClip = true;
        setNoGravity(true);
    }

    MoonEntity(ServerWorld world, PlayerEntity owner, Vec3d ground) {
        this(Moon.MOON, world);
        ownerId = owner.getUuid();
        dataTracker.set(GROUND, ground.toVector3f());
        // As high as it'll fit, up to START_HEIGHT
        Vec3d top = ground.add(0, START_HEIGHT + RADIUS, 0);
        BlockHitResult roof = world.raycast(new RaycastContext(ground.add(0, 0.5, 0), top, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this));
        double y = roof.getType() == HitResult.Type.MISS ? ground.y + START_HEIGHT : roof.getPos().y - RADIUS - 0.5;
        y = Math.max(y, ground.y + RADIUS + 1.0);
        dataTracker.set(START_Y, (float) y);
        setPosition(ground.x, y, ground.z);
        resetPosition();
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        builder.add(STAGE, FORMING);
        builder.add(GROUND, new Vector3f());
        builder.add(START_Y, 0f);
    }

    public int stage() {
        return dataTracker.get(STAGE);
    }

    public Vec3d ground() {
        return new Vec3d(dataTracker.get(GROUND));
    }

    public float startY() {
        return dataTracker.get(START_Y);
    }

    /** Where the moon's middle comes to rest: sunk a little into the ground. */
    public double endY() {
        return ground().y + RADIUS * 0.45;
    }

    private void setStage(int stage) {
        dataTracker.set(STAGE, stage);
        stageAge = 0;
    }

    @Override
    public void onTrackedDataSet(TrackedData<?> data) {
        super.onTrackedDataSet(data);
        if (STAGE.equals(data)) clientStageAge = 0;
    }

    @Override
    public boolean shouldSave() {
        return false;
    }

    @Override
    public boolean shouldRender(double distance) {
        return distance < 200 * 200;
    }

    /** Everything it draws: the moon, its shadow and the shockwave. */
    @Override
    public Box getVisibilityBoundingBox() {
        Vec3d g = ground();
        return new Box(getPos(), g).expand(IMPACT_RADIUS + 6);
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
        PlayerEntity owner = ownerId == null ? null : sw.getPlayerByUuid(ownerId);
        stageAge++;
        switch (stage()) {
            case FORMING -> {
                if (stageAge == 1) formSounds(sw);
                moveLight(sw);
                if (stageAge >= FORM_TICKS) {
                    setStage(FALLING);
                    sw.playSound(null, getX(), getY(), getZ(), SoundEvents.BLOCK_CONDUIT_ACTIVATE, SoundCategory.PLAYERS, 4f, 0.5f);
                }
            }
            case FALLING -> fall(sw, owner);
            case AFTERGLOW -> afterglow(sw);
            default -> {}
        }
    }

    /** Down it comes, gathering speed, pulling everything near the landing spot up into a ring. */
    private void fall(ServerWorld sw, @Nullable PlayerEntity owner) {
        float t = MathHelper.clamp(stageAge / (float) FALL_TICKS, 0f, 1f);
        double y = MathHelper.lerp(Math.pow(t, 2.4), startY(), endY());
        Vec3d g = ground();
        setPosition(g.x, y, g.z);
        moveLight(sw);
        pull(sw, owner);

        // The ground trembles harder the closer it gets
        if (stageAge % 4 == 0) ShakePayload.around(sw, g, 16, 0.08f + 0.35f * t * t, 6);
        if (stageAge % 12 == 0) sw.playSound(null, getX(), getY(), getZ(), SoundEvents.BLOCK_BEACON_AMBIENT, SoundCategory.PLAYERS, 4f, 0.5f + 0.2f * t);
        if (stageAge == FALL_TICKS - 22) sw.playSound(null, g.x, g.y, g.z, SoundEvents.ENTITY_WARDEN_SONIC_CHARGE, SoundCategory.PLAYERS, 3f, 0.6f);

        if (stageAge >= FALL_TICKS) impact(sw, owner);
    }

    /** Lifts everything near the landing spot and sweeps it round in a ring under the moon. */
    private void pull(ServerWorld sw, @Nullable PlayerEntity owner) {
        Vec3d g = ground();
        Box area = new Box(g, g).expand(PULL_RADIUS, 0, PULL_RADIUS).stretch(0, getY() - g.y, 0).expand(0, 2, 0);
        for (LivingEntity e : sw.getEntitiesByClass(LivingEntity.class, area, e -> canHurt(e, owner))) {
            if (lifted.containsKey(e.getUuid())) continue;
            if (horizontal(e.getPos(), g) > PULL_RADIUS) continue;
            lifted.put(e.getUuid(), new Lifted(Math.atan2(e.getZ() - g.z, e.getX() - g.x)));
            sw.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.ENTITY_ILLUSIONER_MIRROR_MOVE, SoundCategory.PLAYERS, 1.2f, 0.5f);
            sw.spawnParticles(ParticleTypes.END_ROD, e.getX(), e.getY() + 0.1, e.getZ(), 12, 0.3, 0.05, 0.3, 0.08);
        }

        // The moon's bottom; the ring has to stay below it as it comes down
        double ceiling = getY() - RADIUS - 1.0 - g.y;
        Iterator<Map.Entry<UUID, Lifted>> it = lifted.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Lifted> entry = it.next();
            Entity found = sw.getEntity(entry.getKey());
            if (!(found instanceof LivingEntity e) || !e.isAlive() || horizontal(e.getPos(), g) > PULL_RADIUS + 6) {
                it.remove();
                continue;
            }
            Lifted l = entry.getValue();
            l.ticks++;
            l.angle += 0.06 + 0.04 * Math.min(1, l.ticks / 30.0); // the ring picks up speed
            double rise = Math.min(1.0, l.ticks / 25.0);
            double height = Math.max(0.5, Math.min(ORBIT_HEIGHT * rise, ceiling));
            double radius = MathHelper.lerp(rise, horizontal(e.getPos(), g), ORBIT_RADIUS);
            Vec3d want = g.add(Math.cos(l.angle) * radius, height + Math.sin(l.ticks * 0.2 + l.angle) * 0.25, Math.sin(l.angle) * radius);
            Vec3d v = want.subtract(e.getPos()).multiply(0.3);
            if (v.length() > 1.2) v = v.normalize().multiply(1.2);
            e.setVelocity(v);
            e.velocityModified = true;
            e.fallDistance = 0;
            if (l.ticks % 2 == 0) sw.spawnParticles(PALE_BLUE, e.getX(), e.getY() - 0.1, e.getZ(), 1, 0.15, 0.05, 0.15, 0);
        }
    }

    /** It lands: everything it lifted is slammed into the ground, and a shockwave tears outward. */
    private void impact(ServerWorld sw, @Nullable PlayerEntity owner) {
        Vec3d g = ground();
        Vec3d from = g.add(0, 1.0, 0);
        for (LivingEntity e : sw.getEntitiesByClass(LivingEntity.class, new Box(g, g).expand(IMPACT_RADIUS + 1, IMPACT_RADIUS, IMPACT_RADIUS + 1), e -> canHurt(e, owner))) {
            boolean wasLifted = lifted.containsKey(e.getUuid());
            Vec3d at = e.getPos();
            double d = horizontal(at, g) + Math.abs(at.y - g.y) * 0.3;
            if (d > IMPACT_RADIUS || (!wasLifted && !canSee(sw, from, e))) continue;
            float k = (float) (1.0 - d / IMPACT_RADIUS);
            float damage = MathHelper.lerp(k, IMPACT_DAMAGE_EDGE, IMPACT_DAMAGE) + (wasLifted ? SLAM_DAMAGE : 0f);
            e.timeUntilRegen = 0;
            e.damage(sw.getDamageSources().indirectMagic(this, owner), damage);
            Vec3d out = new Vec3d(at.x - g.x, 0, at.z - g.z);
            out = out.lengthSquared() < 1e-4 ? Vec3d.ZERO : out.normalize();
            if (wasLifted) {
                e.setVelocity(out.multiply(0.4).add(0, -2.5, 0)); // straight back down into the ground
            } else {
                e.setVelocity(e.getVelocity().add(out.multiply(1.0 + 1.6 * k)).add(0, 0.5 + 0.5 * k, 0));
            }
            e.velocityModified = true;
        }
        lifted.clear();

        // Silver light and earth thrown out in a ring
        BlockState floor = sw.getBlockState(BlockPos.ofFloored(g.x, g.y - 0.5, g.z));
        if (floor.isAir()) floor = Blocks.STONE.getDefaultState();
        for (int i = 0; i < 40; i++) {
            double a = i * Math.PI * 2 / 40, cos = Math.cos(a), sin = Math.sin(a);
            sw.spawnParticles(new BlockStateParticleEffect(ParticleTypes.DUST_PILLAR, floor), g.x + cos * 4.5, g.y + 0.1, g.z + sin * 4.5, 2, 0.4, 0, 0.4, 0);
            sw.spawnParticles(ParticleTypes.POOF, g.x + cos * 2, g.y + 0.3, g.z + sin * 2, 0, cos, 0.03, sin, 0.8);
            sw.spawnParticles(ParticleTypes.END_ROD, g.x + cos * 1.5, g.y + 0.5, g.z + sin * 1.5, 0, cos, 0.15, sin, 0.6);
        }
        sw.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, floor), g.x, g.y + 0.5, g.z, 200, 3.5, 0.5, 3.5, 0.4);
        sw.spawnParticles(SILVER, g.x, g.y + 1.5, g.z, 150, 3.0, 1.5, 3.0, 0);
        sw.spawnParticles(ParticleTypes.FLASH, g.x, g.y + 1.0, g.z, 3, 1.0, 0.5, 1.0, 0);
        sw.spawnParticles(ParticleTypes.SNOWFLAKE, g.x, g.y + 1.0, g.z, 120, 2.0, 1.0, 2.0, 0.35);
        sw.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, g.x, g.y + 0.5, g.z, 25, 3.0, 0.3, 3.0, 0.02);

        sw.playSound(null, g.x, g.y, g.z, SoundEvents.ITEM_MACE_SMASH_GROUND_HEAVY, SoundCategory.PLAYERS, 4f, 0.5f);
        sw.playSound(null, g.x, g.y, g.z, SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 4f, 0.55f);
        sw.playSound(null, g.x, g.y, g.z, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.PLAYERS, 3f, 0.7f);
        sw.playSound(null, g.x, g.y, g.z, SoundEvents.ITEM_TRIDENT_THUNDER, SoundCategory.PLAYERS, 3f, 0.8f);
        sw.playSound(null, g.x, g.y, g.z, SoundEvents.BLOCK_BELL_RESONATE, SoundCategory.PLAYERS, 3f, 0.6f);
        ShakePayload.around(sw, g, 24, 1.3f, 28);

        setPosition(g.x, g.y, g.z);
        setStage(AFTERGLOW);
        moveLight(sw);
    }

    /** Moonlight lingering in the crater, motes of it drifting up, until it fades out. */
    private void afterglow(ServerWorld sw) {
        Vec3d g = ground();
        float left = 1f - stageAge / (float) AFTERGLOW_TICKS;
        if (sw.random.nextFloat() < left) {
            sw.spawnParticles(ParticleTypes.END_ROD, g.x, g.y + 0.3, g.z, 2, 2.5, 0.1, 2.5, 0.03);
        }
        if (stageAge % 3 == 0 && left > 0.2f) sw.spawnParticles(SILVER, g.x, g.y + 0.4, g.z, 3, 3.0, 0.2, 3.0, 0);
        if (stageAge >= AFTERGLOW_TICKS) discard();
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private void formSounds(ServerWorld sw) {
        double x = getX(), y = getY(), z = getZ();
        sw.playSound(null, x, y, z, SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 4f, 0.5f);
        sw.playSound(null, x, y, z, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 4f, 0.5f);
        sw.playSound(null, x, y, z, SoundEvents.ENTITY_ILLUSIONER_CAST_SPELL, SoundCategory.PLAYERS, 3f, 0.5f);
    }

    private static double horizontal(Vec3d a, Vec3d b) {
        return Math.sqrt(MathHelper.square(a.x - b.x) + MathHelper.square(a.z - b.z));
    }

    private boolean canHurt(Entity e, @Nullable PlayerEntity owner) {
        if (!(e instanceof LivingEntity le) || !le.isAlive() || le.isSpectator() || e instanceof ArmorStandEntity) return false;
        if (e instanceof PlayerEntity p && p.isCreative()) return false;
        if (ownerId != null) {
            if (e.getUuid().equals(ownerId) || e instanceof IllusionEntity) return false;
            if (e instanceof TameableEntity pet && ownerId.equals(pet.getOwnerUuid())) return false;
        }
        return owner == null || !e.isTeammate(owner);
    }

    private boolean canSee(ServerWorld sw, Vec3d from, Entity e) {
        Vec3d to = e.getBoundingBox().getCenter();
        return sw.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this)).getType() == HitResult.Type.MISS;
    }

    /** Keeps a light block at the moon (or in the crater once it's landed), only ever in air. */
    private void moveLight(ServerWorld sw) {
        BlockPos want = stage() == AFTERGLOW ? BlockPos.ofFloored(ground().add(0, 1.0, 0)) : getBlockPos();
        if (want.equals(light)) return;
        clearLight(sw);
        if (sw.getBlockState(want).isAir()) {
            sw.setBlockState(want, Blocks.LIGHT.getDefaultState().with(LightBlock.LEVEL_15, 15), 3);
            light = want;
        }
    }

    private void clearLight(ServerWorld sw) {
        if (light != null && sw.getBlockState(light).isOf(Blocks.LIGHT)) sw.setBlockState(light, Blocks.AIR.getDefaultState(), 3);
        light = null;
    }

    @Override
    public void remove(RemovalReason reason) {
        if (getWorld() instanceof ServerWorld sw) clearLight(sw);
        super.remove(reason);
    }

    // ---------------------------------------------------------------------
    // Client
    // ---------------------------------------------------------------------

    /** Synced positions land here; the next tick moves there, so rendering eases across the gap. */
    @Override
    public void updateTrackedPositionAndAngles(double x, double y, double z, float yaw, float pitch, int steps) {
        lerpTo = new Vec3d(x, y, z);
    }

    private void clientTick() {
        if (lerpTo != null) {
            setPosition(lerpTo);
            lerpTo = null;
        }
        clientStageAge++;
        World w = getWorld();
        Vec3d c = getPos();
        switch (stage()) {
            case FORMING -> {
                // Specks of light drawn in from the dark to make it
                for (int i = 0; i < 6; i++) {
                    Vec3d d = randomDir().multiply(RADIUS * 2.2);
                    w.addParticle(ParticleTypes.END_ROD, c.x + d.x, c.y + d.y, c.z + d.z, -d.x * 0.06, -d.y * 0.06, -d.z * 0.06);
                }
            }
            case FALLING -> {
                // Moondust shed from its surface, and grit lifting off the ground as it gets close
                for (int i = 0; i < 3; i++) {
                    Vec3d d = randomDir().multiply(RADIUS * 1.02);
                    w.addParticle(ParticleTypes.WHITE_ASH, c.x + d.x, c.y + d.y, c.z + d.z, d.x * 0.02, d.y * 0.02, d.z * 0.02);
                }
                if (random.nextInt(3) == 0) {
                    Vec3d d = randomDir().multiply(RADIUS * 1.05);
                    w.addParticle(ParticleTypes.END_ROD, c.x + d.x, c.y + d.y, c.z + d.z, d.x * 0.01, d.y * 0.01, d.z * 0.01);
                }
                Vec3d g = ground();
                double close = 1.0 - MathHelper.clamp((c.y - g.y) / (startY() - g.y), 0, 1);
                if (close > 0.5) {
                    for (int i = 0; i < 4; i++) {
                        double a = random.nextDouble() * Math.PI * 2, r = RADIUS * (1.2 + random.nextDouble() * 1.5);
                        w.addParticle(ParticleTypes.POOF, g.x + Math.cos(a) * r, g.y + 0.1, g.z + Math.sin(a) * r,
                                Math.cos(a) * 0.08, 0.02, Math.sin(a) * 0.08);
                    }
                }
            }
            default -> {}
        }
    }

    private Vec3d randomDir() {
        Vec3d d = new Vec3d(random.nextGaussian(), random.nextGaussian(), random.nextGaussian());
        return d.lengthSquared() < 1e-6 ? new Vec3d(0, 1, 0) : d.normalize();
    }

    // ---------------------------------------------------------------------
    // Never saved (see shouldSave)
    // ---------------------------------------------------------------------

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {}

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {}
}
