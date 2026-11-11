package net.ragnar.ragnarsmagicmod.basalt;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
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
import net.ragnar.ragnarsmagicmod.boulders.Tumble;
import net.ragnar.ragnarsmagicmod.entity.IllusionEntity;
import net.ragnar.ragnarsmagicmod.network.ShakePayload;
import net.ragnar.ragnarsmagicmod.util.Deflectable;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The basalt boulder: a huge glowing volcanic rock. It lives in three stages, like the Tome of Boulders' boulder:
 * <ol>
 *   <li><b>Gathering</b> - for {@link #GATHER_TICKS} (a second) it forms over the caster's head, following their aim.
 *       Chunks of the ground tear loose all around and spiral up into a white-hot core, which grows, piece by piece,
 *       into the rock (the shards are drawn on the client, see {@link Shard}).</li>
 *   <li><b>Flying</b> - hurled where the caster looks, trailing smoke, flame and dripping lava. Anything in its way takes
 *       {@link #HIT_DAMAGE}, is set alight and is flung aside as it ploughs on. Where it comes down it slams: a burning
 *       shockwave through everything within {@link #SLAM_RADIUS}. Flying into a wall slams it there and it bursts.</li>
 *   <li><b>Rolling</b> - it bounces and rolls on, scorching and bowling over whatever it rolls into, cooling as it goes,
 *       until it stops, hits a wall or runs out of time, and bursts apart in a spray of lava.</li>
 * </ol>
 * It never breaks or burns blocks and never hurts its caster, their pets or their teammates.
 */
public class BasaltEntity extends Entity implements Deflectable {
    public static final int GATHER_TICKS = 20;
    public static final double LAUNCH_SPEED = 1.35;
    private static final double AIM_RANGE = 45.0;
    private static final double GRAVITY = 0.055;
    private static final double ROLL_FRICTION = 0.95;

    public static final float HIT_DAMAGE = 35f;
    public static final double HIT_FLING = 3.0;
    public static final float SLAM_DAMAGE = 16f, SLAM_DAMAGE_EDGE = 5f;
    public static final double SLAM_RADIUS = 7.0;
    public static final float ROLL_DAMAGE = 12f;
    public static final float BURST_DAMAGE = 8f;
    public static final double BURST_RADIUS = 4.5;
    /** Seconds alight after a direct hit; the slam, rolling and burst burn for less. */
    private static final float BURN = 6f;
    private static final int ROLL_AGAIN = 10;
    private static final int MAX_ROLL_TICKS = 80, MAX_AGE = 300;
    /** Drawn size, in blocks from the middle. */
    public static final float RADIUS = 2.25f;

    public static final int GATHERING = 0, FLYING = 1, ROLLING = 2;
    private static final TrackedData<Integer> STAGE = DataTracker.registerData(BasaltEntity.class, TrackedDataHandlerRegistry.INTEGER);

    private static final BlockState[] VOLCANIC = {Blocks.BASALT.getDefaultState(), Blocks.BLACKSTONE.getDefaultState(),
            Blocks.MAGMA_BLOCK.getDefaultState(), Blocks.SMOOTH_BASALT.getDefaultState()};

    // Server
    @Nullable private UUID ownerId;
    private final Set<UUID> ploughed = new HashSet<>();
    private final Map<UUID, Integer> rolledOver = new HashMap<>();
    private int stageAge;

    // Client
    @Nullable private Vec3d lerpTo;
    public final Tumble tumble;
    /** The bits of ground flying in while it gathers. */
    public final List<Shard> shards = new ArrayList<>();
    private int seenStage = GATHERING, stageSeenAt;

    public BasaltEntity(EntityType<? extends BasaltEntity> type, World world) {
        super(type, world);
        tumble = new Tumble(world.random.nextLong());
    }

    BasaltEntity(ServerWorld world, PlayerEntity owner) {
        this(Basalt.BASALT_BOULDER, world);
        ownerId = owner.getUuid();
        setPosition(holdPoint(world, owner));
        resetPosition();
    }

    // A Tome of Deflection pane hurls it back while it's in the air, as the deflector's
    @Override
    public Vec3d deflectVelocity() {
        return stage() == FLYING && !isRemoved() ? getVelocity() : Vec3d.ZERO;
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
        ploughed.clear();
        stageAge = 0;
        setVelocity(dir.multiply(Math.max(speed, LAUNCH_SPEED)));
        velocityDirty = true;
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        builder.add(STAGE, GATHERING);
    }

    public int stage() {
        return dataTracker.get(STAGE);
    }

    private void setStage(int stage) {
        dataTracker.set(STAGE, stage);
        stageAge = 0;
    }

    @Override
    public boolean shouldSave() {
        return false;
    }

    @Override
    public boolean shouldRender(double distance) {
        return distance < 128 * 128;
    }

    @Override
    public float getStepHeight() {
        return stage() == ROLLING ? 1.0f : 0f;
    }

    @Override
    public Box getVisibilityBoundingBox() {
        // While gathering, the shards fly in from well outside the rock
        return stage() == GATHERING ? getBoundingBox().expand(10, 7, 10) : getBoundingBox().expand(1);
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
        PlayerEntity owner = ownerId != null ? sw.getPlayerByUuid(ownerId) : null;
        stageAge++;
        if (age > MAX_AGE) {
            burst(sw, owner);
            return;
        }
        switch (stage()) {
            case GATHERING -> gather(sw, owner);
            case FLYING, ROLLING -> physics(sw, owner);
            default -> {}
        }
    }

    /** Held overhead, following the caster's aim, drawing in heat and stone until it's whole and goes. */
    private void gather(ServerWorld sw, @Nullable PlayerEntity owner) {
        if (owner == null || !owner.isAlive()) {
            burst(sw, null);
            return;
        }
        setPosition(holdPoint(sw, owner));
        Vec3d c = center();
        float t = stageAge / (float) GATHER_TICKS;
        // Stone knocking into place, quicker and higher as it builds, each chunk landing with a wet molten squelch
        if (stageAge % 3 == 1) {
            sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_BASALT_PLACE, SoundCategory.PLAYERS, 1.3f, 0.5f + t * 0.5f);
            sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_MAGMA_CUBE_SQUISH, SoundCategory.PLAYERS, 0.9f, 0.5f + t * 0.4f);
        }
        if (stageAge % 5 == 2) sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_LAVA_POP, SoundCategory.PLAYERS, 1.2f, 0.6f + t * 0.5f);
        if (stageAge % 4 == 3) sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_POINTED_DRIPSTONE_DRIP_LAVA, SoundCategory.PLAYERS, 1.5f, 0.7f + t * 0.4f);
        // The magma inside swelling up: a deep gurgle, then the roar of a furnace as it's about to go
        if (stageAge == 6) sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_LAVA_AMBIENT, SoundCategory.PLAYERS, 2.0f, 0.6f);
        if (stageAge == GATHER_TICKS - 7) {
            sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_FURNACE_FIRE_CRACKLE, SoundCategory.PLAYERS, 2.0f, 0.5f);
            sw.playSound(null, c.x, c.y, c.z, SoundEvents.ITEM_BUCKET_EMPTY_LAVA, SoundCategory.PLAYERS, 1.4f, 0.5f);
        }
        if (stageAge % 4 == 0) ShakePayload.around(sw, owner.getPos(), 10, 0.08f + 0.3f * t, 5);
        if (stageAge >= GATHER_TICKS) launch(sw, owner);
    }

    private void launch(ServerWorld sw, PlayerEntity owner) {
        if (!sw.isSpaceEmpty(this, getBoundingBox().contract(0.05))) {
            // Gathered into a wall or ceiling: it can't get out, so it comes down right there
            setStage(FLYING);
            slam(sw, owner, 1f);
            burst(sw, owner);
            return;
        }
        setVelocity(aim(sw, owner));
        velocityDirty = true;
        setStage(FLYING);
        Vec3d c = center();
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_IRON_GOLEM_ATTACK, SoundCategory.PLAYERS, 1.6f, 0.45f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_BLAZE_SHOOT, SoundCategory.PLAYERS, 1.6f, 0.5f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 0.7f, 1.4f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 1.2f, 0.4f);
        Vec3d v = getVelocity().normalize();
        sw.spawnParticles(ParticleTypes.FLAME, c.x, c.y, c.z, 40, 0.8, 0.8, 0.8, 0.12);
        sw.spawnParticles(ParticleTypes.LARGE_SMOKE, c.x - v.x, c.y - v.y, c.z - v.z, 8, 0.6, 0.6, 0.6, 0.04);
        sw.spawnParticles(ParticleTypes.LAVA, c.x, c.y, c.z, 10, 0.6, 0.6, 0.6, 0);
        if (owner instanceof ServerPlayerEntity sp) shake(sp, 0.45f, 8);
    }

    private void physics(ServerWorld sw, @Nullable PlayerEntity owner) {
        boolean rolling = stage() == ROLLING;
        Vec3d v = getVelocity();
        if (rolling && isOnGround()) v = new Vec3d(v.x * ROLL_FRICTION, v.y, v.z * ROLL_FRICTION);
        else v = v.multiply(0.99);
        if (isTouchingWater()) {
            v = v.multiply(0.85);
            if (age % 3 == 0) {
                Vec3d c = center();
                sw.spawnParticles(ParticleTypes.CLOUD, c.x, c.y, c.z, 6, 1.0, 0.6, 1.0, 0.05);
                sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_LAVA_EXTINGUISH, SoundCategory.PLAYERS, 0.8f, 0.7f);
            }
        }
        v = v.add(0, -GRAVITY, 0);

        Vec3d before = getPos();
        Box boxBefore = getBoundingBox();
        setVelocity(v);
        move(MovementType.SELF, v);
        Vec3d moved = getPos().subtract(before);
        boolean landed = verticalCollision && v.y < 0;
        boolean hitWall = horizontalCollision;

        crush(sw, owner, boxBefore.union(getBoundingBox()).expand(0.15), moved);
        if (isRemoved()) return;

        double fall = -v.y;
        double across = v.horizontalLength();
        if (!rolling) {
            if (hitWall) {
                slam(sw, owner, 1f);
                burst(sw, owner);
                return;
            }
            if (landed) {
                slam(sw, owner, (float) MathHelper.clamp(0.6 + fall, 0.7, 1.0));
                setStage(ROLLING);
                setVelocity(v.x * 0.8, fall > 0.4 ? Math.min(fall * 0.35, 0.45) : 0.0, v.z * 0.8);
                velocityDirty = true;
            }
            return;
        }

        if (landed && fall > 0.55) {
            thud(sw, owner);
            setVelocity(getVelocity().x, Math.min(fall * 0.3, 0.3), getVelocity().z);
        }
        if (hitWall && across > 0.2) {
            burst(sw, owner);
            return;
        }
        if ((isOnGround() && getVelocity().horizontalLength() < 0.05) || stageAge > MAX_ROLL_TICKS) {
            burst(sw, owner);
            return;
        }
        if (isOnGround() && across > 0.08) rollEffects(sw, across);
        velocityDirty = true;
    }

    /** Everything it ran over this tick: ploughed through in flight, bowled over while rolling. */
    private void crush(ServerWorld sw, @Nullable PlayerEntity owner, Box swept, Vec3d moved) {
        Vec3d dir = moved.multiply(1, 0, 1);
        if (dir.lengthSquared() < 1e-6) return;
        dir = dir.normalize();
        boolean flying = stage() == FLYING;
        double speed = getVelocity().horizontalLength();
        for (LivingEntity e : sw.getEntitiesByClass(LivingEntity.class, swept, e -> canHurt(e, owner))) {
            if (flying) {
                if (!ploughed.add(e.getUuid())) continue;
                hurt(sw, e, HIT_DAMAGE, owner, BURN);
                fling(e, dir.multiply(HIT_FLING).add(0, 0.8, 0));
                setVelocity(getVelocity().multiply(0.9)); // barely slowed
                impactEffects(sw, e, 1f);
                if (owner instanceof ServerPlayerEntity sp) shake(sp, 0.45f, 10);
            } else {
                if (speed < 0.12 || age - rolledOver.getOrDefault(e.getUuid(), -100) < ROLL_AGAIN) continue;
                rolledOver.put(e.getUuid(), age);
                float k = (float) MathHelper.clamp(speed / 0.6, 0.5, 1.0);
                hurt(sw, e, ROLL_DAMAGE * k, owner, 4f * heat());
                fling(e, dir.multiply(1.4 + 1.6 * speed).add(0, 0.6, 0));
                impactEffects(sw, e, 0.75f);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Big moments
    // ---------------------------------------------------------------------

    /** Comes down: a burning shockwave that hurts, scorches and hurls everything near, hardest in the middle. */
    private void slam(ServerWorld sw, @Nullable PlayerEntity owner, float strength) {
        Vec3d c = getPos();
        double radius = SLAM_RADIUS * (0.7 + 0.3 * strength);
        Vec3d from = c.add(0, 0.8, 0);
        for (LivingEntity e : sw.getEntitiesByClass(LivingEntity.class, new Box(c, c).expand(radius + 1), e -> canHurt(e, owner))) {
            Vec3d at = e.getPos();
            double d = Math.sqrt(MathHelper.square(at.x - c.x) + MathHelper.square(at.z - c.z)) + Math.abs(at.y - c.y) * 0.5;
            if (d > radius || !canSee(sw, from, e)) continue;
            float k = (float) (1.0 - d / radius);
            hurt(sw, e, MathHelper.lerp(k, SLAM_DAMAGE_EDGE, SLAM_DAMAGE) * strength, owner, 2f + 3f * k);
            Vec3d out = at.subtract(c).multiply(1, 0, 1);
            out = out.lengthSquared() < 1e-4 ? Vec3d.ZERO : out.normalize();
            fling(e, out.multiply((1.0 + 1.9 * k) * strength).add(0, (0.5 + 0.6 * k) * strength, 0));
        }

        BlockState ground = groundBelow(sw);
        // A ring of fire and dust racing out across the ground, earth and molten rock thrown up all round
        for (int i = 0; i < 40; i++) {
            double a = i * Math.PI * 2 / 40;
            double cos = Math.cos(a), sin = Math.sin(a);
            sw.spawnParticles(ParticleTypes.POOF, c.x + cos * 1.6, c.y + 0.2, c.z + sin * 1.6, 0, cos, 0.02, sin, 0.6 * strength);
            sw.spawnParticles(ParticleTypes.FLAME, c.x + cos * 1.4, c.y + 0.15, c.z + sin * 1.4, 0, cos, 0.01, sin, 0.45 * strength);
            if (i % 2 == 0) {
                sw.spawnParticles(new BlockStateParticleEffect(ParticleTypes.DUST_PILLAR, ground),
                        c.x + cos * radius * 0.6, c.y + 0.1, c.z + sin * radius * 0.6, 1, 0.3, 0, 0.3, 0);
            }
        }
        sw.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, ground), c.x, c.y + 0.3, c.z, 160, radius * 0.4, 0.3, radius * 0.4, 0.3);
        for (BlockState s : VOLCANIC) {
            sw.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, s), c.x, c.y + 1.0, c.z, 25, 1.0, 0.6, 1.0, 0.3);
        }
        sw.spawnParticles(ParticleTypes.LAVA, c.x, c.y + 0.8, c.z, 45, 1.6, 0.4, 1.6, 0);
        sw.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y + 0.8, c.z, 1, 0, 0, 0, 0);
        sw.spawnParticles(ParticleTypes.EXPLOSION, c.x, c.y + 0.6, c.z, 5, 1.8, 0.4, 1.8, 0);
        sw.spawnParticles(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, c.x, c.y + 0.5, c.z, 14, 1.8, 0.3, 1.8, 0.02);
        sw.spawnParticles(ParticleTypes.LARGE_SMOKE, c.x, c.y + 0.6, c.z, 16, 2.2, 0.6, 2.2, 0.04);

        float vol = 1.6f + strength;
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ITEM_MACE_SMASH_GROUND_HEAVY, SoundCategory.PLAYERS, vol, 0.55f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, vol, 0.5f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_DRAGON_FIREBALL_EXPLODE, SoundCategory.PLAYERS, vol * 0.6f, 0.6f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_BASALT_BREAK, SoundCategory.PLAYERS, vol, 0.45f);
        sw.playSound(null, c.x, c.y, c.z, ground.getSoundGroup().getBreakSound(), SoundCategory.PLAYERS, vol, 0.5f);
        ShakePayload.around(sw, c, 14, 1.0f * strength, 20);
    }

    /** A smaller landing while it's rolling, coming down off a drop. */
    private void thud(ServerWorld sw, @Nullable PlayerEntity owner) {
        Vec3d c = getPos();
        for (LivingEntity e : sw.getEntitiesByClass(LivingEntity.class, getBoundingBox().expand(1.8, 0.5, 1.8), e -> canHurt(e, owner))) {
            Vec3d out = e.getPos().subtract(c).multiply(1, 0, 1);
            out = out.lengthSquared() < 1e-4 ? Vec3d.ZERO : out.normalize();
            hurt(sw, e, ROLL_DAMAGE * 0.6f, owner, 2f);
            fling(e, out.multiply(1.1).add(0, 0.5, 0));
        }
        sw.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, groundBelow(sw)), c.x, c.y + 0.2, c.z, 60, 1.2, 0.2, 1.2, 0.2);
        sw.spawnParticles(ParticleTypes.LAVA, c.x, c.y + 0.4, c.z, (int) (10 * heat()), 1.0, 0.2, 1.0, 0);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ITEM_MACE_SMASH_GROUND, SoundCategory.PLAYERS, 1.6f, 0.55f);
        ShakePayload.around(sw, c, 8, 0.4f, 8);
    }

    /** Bursts apart in a spray of lava and rock, scorching and knocking back whatever's right beside it. */
    private void burst(ServerWorld sw, @Nullable PlayerEntity owner) {
        Vec3d c = center();
        if (stage() != GATHERING) {
            for (LivingEntity e : sw.getEntitiesByClass(LivingEntity.class, new Box(c, c).expand(BURST_RADIUS + 1), e -> canHurt(e, owner))) {
                Vec3d at = e.getBoundingBox().getCenter();
                double d = at.distanceTo(c);
                if (d > BURST_RADIUS) continue;
                float k = (float) (1.0 - d / BURST_RADIUS);
                hurt(sw, e, MathHelper.lerp(k, 2f, BURST_DAMAGE), owner, 3f * k);
                Vec3d out = at.subtract(c).multiply(1, 0, 1);
                out = out.lengthSquared() < 1e-4 ? Vec3d.ZERO : out.normalize();
                fling(e, out.multiply(0.6 + 0.9 * k).add(0, 0.35, 0));
            }
        }
        for (BlockState s : new BlockState[]{Blocks.BASALT.getDefaultState(), Blocks.BLACKSTONE.getDefaultState(),
                Blocks.MAGMA_BLOCK.getDefaultState(), Blocks.SMOOTH_BASALT.getDefaultState(), Blocks.OBSIDIAN.getDefaultState()}) {
            sw.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, s), c.x, c.y, c.z, 45, 0.9, 0.9, 0.9, 0.3);
        }
        sw.spawnParticles(ParticleTypes.LAVA, c.x, c.y, c.z, 30, 0.8, 0.6, 0.8, 0);
        sw.spawnParticles(ParticleTypes.FLAME, c.x, c.y, c.z, 70, 0.9, 0.8, 0.9, 0.15);
        sw.spawnParticles(ParticleTypes.LARGE_SMOKE, c.x, c.y, c.z, 12, 1.0, 0.8, 1.0, 0.05);
        sw.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, c.x, c.y - 0.6, c.z, 8, 1.0, 0.3, 1.0, 0.01);
        sw.spawnParticles(ParticleTypes.EXPLOSION, c.x, c.y, c.z, 2, 0.5, 0.5, 0.5, 0);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_BASALT_BREAK, SoundCategory.PLAYERS, 2.2f, 0.5f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_DEEPSLATE_BREAK, SoundCategory.PLAYERS, 1.8f, 0.55f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_LAVA_EXTINGUISH, SoundCategory.PLAYERS, 1.2f, 0.6f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 1.0f, 0.8f);
        ShakePayload.around(sw, c, 7, 0.35f, 8);
        discard();
    }

    // ---------------------------------------------------------------------
    // Hurting things
    // ---------------------------------------------------------------------

    private void hurt(ServerWorld sw, LivingEntity e, float amount, @Nullable PlayerEntity owner, float burnSeconds) {
        // A ploughing hit, the slam and the burst can land within a few ticks; none should bounce off hurt immunity
        e.timeUntilRegen = 0;
        e.damage(sw.getDamageSources().thrown(this, owner), amount);
        if (burnSeconds > 0.5f && !e.isFireImmune()) e.setOnFireFor(burnSeconds);
    }

    /** Throws {@code e} by {@code push}, most of the way through even heavy knockback resistance. */
    private static void fling(LivingEntity e, Vec3d push) {
        double resist = e.getAttributeValue(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE);
        push = push.multiply(1.0 - 0.6 * MathHelper.clamp(resist, 0, 1));
        e.setVelocity(e.getVelocity().multiply(0.3).add(push));
        e.velocityModified = true;
        e.velocityDirty = true;
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

    // ---------------------------------------------------------------------
    // Effects and helpers
    // ---------------------------------------------------------------------

    private void impactEffects(ServerWorld sw, LivingEntity e, float k) {
        Vec3d c = e.getBoundingBox().getCenter();
        sw.spawnParticles(ParticleTypes.CRIT, c.x, c.y, c.z, (int) (30 * k), 0.3, 0.4, 0.3, 0.6);
        sw.spawnParticles(ParticleTypes.FLAME, c.x, c.y, c.z, (int) (25 * k), 0.3, 0.4, 0.3, 0.12);
        sw.spawnParticles(ParticleTypes.LAVA, c.x, c.y, c.z, (int) (6 * k), 0.3, 0.3, 0.3, 0);
        sw.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.BLACKSTONE.getDefaultState()), c.x, c.y, c.z, (int) (25 * k), 0.3, 0.4, 0.3, 0.2);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK, SoundCategory.PLAYERS, 1.6f * k, 0.55f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_IRON_GOLEM_DAMAGE, SoundCategory.PLAYERS, 1.3f * k, 0.45f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_BASALT_BREAK, SoundCategory.PLAYERS, 1.4f * k, 0.6f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_BLAZE_HURT, SoundCategory.PLAYERS, 0.6f * k, 0.5f);
    }

    /** Grit and embers kicked up where it touches the ground, a grinding rumble, and a tremble underfoot. */
    private void rollEffects(ServerWorld sw, double speed) {
        Vec3d c = getPos();
        sw.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, groundBelow(sw)), c.x, c.y + 0.1, c.z, 5, 0.7, 0.05, 0.7, 0.12);
        if (age % 3 == 0) {
            float vol = (float) MathHelper.clamp(speed * 2.5, 0.5, 1.6);
            sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_BASALT_STEP, SoundCategory.PLAYERS, vol, 0.45f);
            sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_GRAVEL_STEP, SoundCategory.PLAYERS, vol, 0.45f);
        }
        if (age % 8 == 0) sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_FIRE_AMBIENT, SoundCategory.PLAYERS, 1.2f, 0.7f);
        if (age % 4 == 0) ShakePayload.around(sw, c, 6, (float) MathHelper.clamp(speed * 0.4, 0.05, 0.25), 5);
    }

    private BlockState groundBelow(ServerWorld sw) {
        BlockPos below = BlockPos.ofFloored(getX(), getY() - 0.2, getZ());
        BlockState s = sw.getBlockState(below);
        return s.isAir() ? Blocks.BASALT.getDefaultState() : s;
    }

    public Vec3d center() {
        return getPos().add(0, getHeight() / 2, 0);
    }

    /**
     * How hot it is, 1 = glowing, down to 0.35 as it cools while rolling. Works on both sides (the client counts the
     * time since it saw the stage change).
     */
    public float heat() {
        if (stage() != ROLLING) return 1f;
        int rolled = getWorld().isClient ? age - stageSeenAt : stageAge;
        return 1f - 0.65f * MathHelper.clamp(rolled / (float) MAX_ROLL_TICKS, 0f, 1f);
    }

    /**
     * Where it's held while gathering: high over the caster's head and a little in front, whichever way they're looking,
     * so it's never in the way of their aim. Pulled in if that's inside a wall. Returns the (bottom-centre) position.
     */
    private Vec3d holdPoint(ServerWorld sw, PlayerEntity owner) {
        Vec3d eye = owner.getEyePos();
        Vec3d flat = Vec3d.fromPolar(0f, owner.getYaw());
        Vec3d want = eye.add(flat.multiply(3.0)).add(0, 3.3, 0);
        BlockHitResult hit = sw.raycast(new RaycastContext(eye, want, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, owner));
        if (hit.getType() != HitResult.Type.MISS) {
            Vec3d back = want.subtract(eye).normalize();
            want = hit.getPos().subtract(back.multiply(2.2));
        }
        return want.subtract(0, getHeight() / 2, 0);
    }

    /**
     * The throw: at whatever the caster's crosshair is on (within {@link #AIM_RANGE}), lofted just enough that it comes
     * down there. Nothing under the crosshair: straight along their aim.
     */
    private Vec3d aim(ServerWorld sw, PlayerEntity owner) {
        Vec3d eye = owner.getEyePos();
        Vec3d look = owner.getRotationVec(1f);
        BlockHitResult hit = sw.raycast(new RaycastContext(eye, eye.add(look.multiply(AIM_RANGE)),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, owner));
        if (hit.getType() == HitResult.Type.MISS) return look.multiply(LAUNCH_SPEED).add(0, 0.1, 0);
        Vec3d to = hit.getPos().subtract(getPos());
        double ticks = Math.max(to.length() / LAUNCH_SPEED, 1.0);
        Vec3d v = to.multiply(1.0 / ticks);
        return v.add(0, GRAVITY * ticks * 0.5, 0);
    }

    private static void shake(ServerPlayerEntity player, float strength, int ticks) {
        if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player, ShakePayload.ID)) {
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, new ShakePayload(strength, ticks));
        }
    }

    // ---------------------------------------------------------------------
    // Client
    // ---------------------------------------------------------------------

    /**
     * A chunk of ground torn loose while the rock gathers, spiralling up into it. Kept relative to the rock's centre,
     * so it follows as the caster turns.
     */
    public static final class Shard {
        public final BlockState state;
        public final boolean hot;
        private final Vec3d from;
        private final float born, life;
        public final float size;
        public final float spinX, spinY, spinZ;

        Shard(BlockState state, Vec3d from, float born, float life, float size, net.minecraft.util.math.random.Random r) {
            this.state = state;
            this.hot = state.isOf(Blocks.MAGMA_BLOCK);
            this.from = from;
            this.born = born;
            this.life = life;
            this.size = size;
            this.spinX = (r.nextFloat() - 0.5f) * 40f;
            this.spinY = (r.nextFloat() - 0.5f) * 40f;
            this.spinZ = (r.nextFloat() - 0.5f) * 40f;
        }

        /** 0 when it tears loose, 1 when it reaches the core. */
        public float progress(float time) {
            return MathHelper.clamp((time - born) / life, 0f, 1f);
        }

        /** Where it is relative to the rock's centre at {@code time} (age + tickDelta). */
        public Vec3d offset(float time) {
            float t = progress(time);
            // Slow to lift off the ground, then faster and faster, swirling as it closes in
            float e = (float) Math.pow(t, 1.6);
            double swirl = e * 2.6;
            double r = 1.0 - e;
            double cos = Math.cos(swirl), sin = Math.sin(swirl);
            double x = (from.x * cos - from.z * sin) * r;
            double z = (from.x * sin + from.z * cos) * r;
            float rise = t * t * (3f - 2f * t);
            double y = from.y * (1.0 - rise) + Math.sin(t * Math.PI) * 1.4;
            return new Vec3d(x, y, z);
        }

        public float angle(float time) {
            return (time - born);
        }

        boolean arrived(float time) {
            return time - born >= life;
        }
    }

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
        int stage = stage();
        if (stage != seenStage) {
            seenStage = stage;
            stageSeenAt = age;
        }
        Vec3d moved = getPos().subtract(prevX, prevY, prevZ);
        Vec3d c = center();
        World w = getWorld();
        switch (stage) {
            case GATHERING -> gatherClient(c);
            case FLYING -> {
                shards.clear();
                tumble.roll(moved, RADIUS, 0.4f, 0.5f);
                // A smoking, burning trail and drips of lava
                for (int i = 0; i < 3; i++) {
                    w.addParticle(ParticleTypes.FLAME, c.x + random.nextGaussian() * 1.0, c.y + random.nextGaussian() * 1.0,
                            c.z + random.nextGaussian() * 1.0, -moved.x * 0.1, 0.02, -moved.z * 0.1);
                }
                if (random.nextInt(2) == 0) {
                    w.addParticle(ParticleTypes.LARGE_SMOKE, c.x + random.nextGaussian() * 1.1, c.y + random.nextGaussian() * 1.1,
                            c.z + random.nextGaussian() * 1.1, 0, 0.03, 0);
                }
                if (age % 2 == 0) w.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, true, c.x, c.y, c.z, 0, 0.02, 0);
                if (random.nextInt(2) == 0) {
                    w.addParticle(ParticleTypes.FALLING_LAVA, c.x + random.nextGaussian() * 0.6, c.y - 0.8, c.z + random.nextGaussian() * 0.6, 0, 0, 0);
                }
                if (random.nextInt(3) == 0) w.addParticle(ParticleTypes.LAVA, c.x, c.y, c.z, 0, 0, 0);
            }
            case ROLLING -> {
                shards.clear();
                tumble.roll(moved, RADIUS, 1f, 0.5f);
                float heat = heat();
                if (random.nextFloat() < 0.8f) {
                    w.addParticle(ParticleTypes.SMOKE, c.x + random.nextGaussian() * 0.7, c.y + 0.9 + random.nextGaussian() * 0.5,
                            c.z + random.nextGaussian() * 0.7, 0, 0.04, 0);
                }
                if (random.nextFloat() < heat * 0.7f) {
                    // Embers left burning in its track
                    w.addParticle(ParticleTypes.SMALL_FLAME, getX() + random.nextGaussian() * 0.5, getY() + 0.1,
                            getZ() + random.nextGaussian() * 0.5, 0, 0.01, 0);
                }
                if (random.nextFloat() < heat * 0.25f) w.addParticle(ParticleTypes.LAVA, c.x, c.y, c.z, 0, 0, 0);
                if (age % 5 == 0 && heat > 0.5f) w.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, true, c.x, c.y + 0.6, c.z, 0, 0.015, 0);
            }
            default -> {}
        }
    }

    /** The gathering, as seen: ground tearing loose, shards and flames spiralling in, the core spinning ever faster. */
    private void gatherClient(Vec3d c) {
        World w = getWorld();
        float g = gather(0f);
        tumble.spin(0.05f + 0.3f * g, 0.3f, 1f, 0.2f);
        float now = age;

        // Shards arriving sink into the rock with a puff of grit
        shards.removeIf(s -> {
            if (!s.arrived(now)) return false;
            w.addParticle(new BlockStateParticleEffect(ParticleTypes.BLOCK, s.state), c.x + random.nextGaussian() * 0.6,
                    c.y + random.nextGaussian() * 0.6, c.z + random.nextGaussian() * 0.6, 0, 0, 0);
            if (random.nextInt(3) == 0) w.addParticle(ParticleTypes.LAVA, c.x, c.y, c.z, 0, 0, 0);
            return true;
        });

        // New shards tear loose from the ground around, all through the first part of the gathering
        int left = GATHER_TICKS - age;
        if (left > 4) {
            for (int i = 0; i < 3; i++) {
                double a = random.nextDouble() * Math.PI * 2;
                double r = 3.5 + random.nextDouble() * 4.5;
                double x = c.x + Math.cos(a) * r, z = c.z + Math.sin(a) * r;
                BlockPos ground = groundUnder(x, c.y, z);
                Vec3d from;
                BlockState state;
                if (ground != null) {
                    from = new Vec3d(x, ground.getY() + 1.0, z);
                    BlockState g0 = w.getBlockState(ground);
                    boolean usable = g0.isFullCube(w, ground) && g0.getRenderType() == net.minecraft.block.BlockRenderType.MODEL;
                    state = usable && random.nextFloat() < 0.5f ? g0 : VOLCANIC[random.nextInt(VOLCANIC.length)];
                    // The ground cracks where it tears loose
                    BlockStateParticleEffect crack = new BlockStateParticleEffect(ParticleTypes.BLOCK, usable ? g0 : state);
                    for (int k = 0; k < 4; k++) {
                        w.addParticle(crack, x + random.nextGaussian() * 0.25, from.y + 0.05, z + random.nextGaussian() * 0.25, 0, 0.15, 0);
                    }
                    if (random.nextInt(4) == 0) w.addParticle(ParticleTypes.LAVA, x, from.y + 0.1, z, 0, 0, 0);
                } else {
                    // Nothing under it (it's over a drop): it draws in from the air around
                    from = c.add(Math.cos(a) * r, -1.5 - random.nextDouble() * 2.0, Math.sin(a) * r);
                    state = VOLCANIC[random.nextInt(VOLCANIC.length)];
                }
                float life = Math.min(9f + random.nextFloat() * 5f, left - 1f);
                shards.add(new Shard(state, from.subtract(c), now, life, 0.22f + random.nextFloat() * 0.25f, random));
            }
        }

        // Flames spiralling in, smoke boiling off, and a few drips of lava from the forming rock
        for (int i = 0; i < 4; i++) {
            double a = random.nextDouble() * Math.PI * 2;
            double r = 2.8 + random.nextDouble() * 1.6;
            double ox = Math.cos(a) * r, oz = Math.sin(a) * r, oy = random.nextGaussian() * 0.8;
            // Inward and round
            double vx = -ox * 0.12 - oz * 0.1, vz = -oz * 0.12 + ox * 0.1;
            w.addParticle(i % 2 == 0 ? ParticleTypes.FLAME : ParticleTypes.SMALL_FLAME, c.x + ox, c.y + oy, c.z + oz, vx, -oy * 0.1, vz);
        }
        if (random.nextFloat() < 0.2f + g * 0.3f) {
            w.addParticle(ParticleTypes.LARGE_SMOKE, c.x + random.nextGaussian() * 0.6, c.y + 0.8, c.z + random.nextGaussian() * 0.6, 0, 0.06, 0);
        }
        if (g > 0.4f && random.nextFloat() < g * 0.5f) {
            w.addParticle(ParticleTypes.FALLING_LAVA, c.x + random.nextGaussian() * 0.5, c.y - RADIUS * 0.7, c.z + random.nextGaussian() * 0.5, 0, 0, 0);
        }
        if (left <= 3) {
            for (int i = 0; i < 3; i++) w.addParticle(ParticleTypes.LAVA, c.x, c.y, c.z, 0, 0, 0);
        }
    }

    /** The top solid block under (x, z), searching down from a little below y; null if there's none near. */
    @Nullable
    private BlockPos groundUnder(double x, double y, double z) {
        BlockPos.Mutable p = new BlockPos.Mutable(MathHelper.floor(x), MathHelper.floor(y) - 1, MathHelper.floor(z));
        for (int i = 0; i < 9; i++, p.move(0, -1, 0)) {
            BlockState s = getWorld().getBlockState(p);
            if (!s.getCollisionShape(getWorld(), p).isEmpty() && getWorld().getBlockState(p.up()).getCollisionShape(getWorld(), p.up()).isEmpty()) {
                return p.toImmutable();
            }
        }
        return null;
    }

    /** How far through the gathering it is, 0..1 (1 once it's thrown). */
    public float gather(float tickDelta) {
        if (stage() != GATHERING) return 1f;
        return MathHelper.clamp((age + tickDelta) / GATHER_TICKS, 0f, 1f);
    }

    // ---------------------------------------------------------------------
    // Never saved (see shouldSave)
    // ---------------------------------------------------------------------

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {}

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {}
}
