package net.ragnar.ragnarsmagicmod.boulders;

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
import net.ragnar.ragnarsmagicmod.util.Deflectable;
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
import net.ragnar.ragnarsmagicmod.entity.IllusionEntity;
import net.ragnar.ragnarsmagicmod.network.ShakePayload;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The boulder. It lives in three stages:
 * <ol>
 *   <li><b>Forming</b> - for {@link #FORM_TICKS} it grows out of nothing, hoisted above and in front of the caster,
 *       following their aim.</li>
 *   <li><b>Flying</b> - hurled along their aim on a heavy arc. Anything in its way takes {@link #HIT_DAMAGE} and is
 *       flung aside as it ploughs straight through. Where it comes down it <i>slams</i>: a shockwave that hurts and
 *       hurls everything within {@link #SLAM_RADIUS}, hardest in the middle, and shakes the screens of everyone near.
 *       Flying into a wall slams it there and it breaks.</li>
 *   <li><b>Rolling</b> - after the slam it bounces and rolls on, climbing single steps, bowling over anything it rolls
 *       into for {@link #ROLL_DAMAGE}, until it slows to a stop, hits a wall or runs out of time, and breaks apart.</li>
 * </ol>
 * It never breaks blocks and never hurts its caster, their pets or their teammates. The server does all the physics;
 * the client eases between synced positions and turns the boulder to match how it moves (see {@link Tumble}).
 */
public class BoulderEntity extends Entity implements Deflectable {
    public static final int FORM_TICKS = 7;
    public static final double LAUNCH_SPEED = 1.3;
    /** How far off the crosshair can be and still be thrown at exactly. */
    private static final double AIM_RANGE = 40.0;
    private static final double GRAVITY = 0.05;
    private static final double ROLL_FRICTION = 0.94;

    public static final float HIT_DAMAGE = 12f;
    public static final double HIT_FLING = 2.4;
    public static final float SLAM_DAMAGE = 9f, SLAM_DAMAGE_EDGE = 3f;
    public static final double SLAM_RADIUS = 5.0;
    public static final float ROLL_DAMAGE = 7f;
    public static final float SHATTER_DAMAGE = 4f;
    public static final double SHATTER_RADIUS = 3.0;
    /** How soon the rolling boulder can bowl over the same thing again. */
    private static final int ROLL_AGAIN = 10;
    private static final int MAX_ROLL_TICKS = 60, MAX_AGE = 240;
    /** Drawn size, in blocks from the middle. */
    public static final float RADIUS = 1.0f;

    public static final int FORMING = 0, FLYING = 1, ROLLING = 2;
    private static final TrackedData<Integer> STAGE = DataTracker.registerData(BoulderEntity.class, TrackedDataHandlerRegistry.INTEGER);

    // Server
    @Nullable private UUID ownerId;
    private final Set<UUID> ploughed = new HashSet<>();
    private final Map<UUID, Integer> rolledOver = new HashMap<>();
    private int stageAge;

    // Client
    @Nullable private Vec3d lerpTo;
    public final Tumble tumble;

    public BoulderEntity(EntityType<? extends BoulderEntity> type, World world) {
        super(type, world);
        tumble = new Tumble(world.random.nextLong());
    }

    BoulderEntity(ServerWorld world, PlayerEntity owner) {
        this(Boulders.BOULDER, world);
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
        builder.add(STAGE, FORMING);
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
        return stage() == ROLLING ? 1.0f : 0f; // rolls up single steps
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
            shatter(sw, owner);
            return;
        }
        switch (stage()) {
            case FORMING -> form(sw, owner);
            case FLYING, ROLLING -> physics(sw, owner);
            default -> {}
        }
    }

    /** Hoisted overhead, following the caster's aim, until it's fully formed and goes. */
    private void form(ServerWorld sw, @Nullable PlayerEntity owner) {
        if (owner == null || !owner.isAlive()) {
            shatter(sw, null);
            return;
        }
        setPosition(holdPoint(sw, owner));
        Vec3d c = center();
        if (stageAge % 2 == 1) {
            sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_STONE_PLACE, SoundCategory.PLAYERS, 1.0f, 0.5f + stageAge * 0.05f);
        }
        if (stageAge >= FORM_TICKS) launch(sw, owner);
    }

    private void launch(ServerWorld sw, PlayerEntity owner) {
        // Hoisted into a wall or ceiling: it can't get out, so it comes down right there
        if (!sw.isSpaceEmpty(this, getBoundingBox().contract(0.05))) {
            setStage(FLYING);
            slam(sw, owner, 1f);
            shatter(sw, owner);
            return;
        }
        setVelocity(aim(sw, owner));
        velocityDirty = true;
        setStage(FLYING);
        Vec3d c = center();
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_IRON_GOLEM_ATTACK, SoundCategory.PLAYERS, 1.5f, 0.55f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 1.2f, 0.5f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_WITCH_THROW, SoundCategory.PLAYERS, 1.5f, 0.4f);
        if (owner instanceof ServerPlayerEntity sp) shake(sp, 0.25f, 6);
    }

    private void physics(ServerWorld sw, @Nullable PlayerEntity owner) {
        boolean rolling = stage() == ROLLING;
        Vec3d v = getVelocity();
        if (rolling && isOnGround()) v = new Vec3d(v.x * ROLL_FRICTION, v.y, v.z * ROLL_FRICTION);
        else v = v.multiply(0.99);
        if (isTouchingWater()) v = v.multiply(0.85);
        v = v.add(0, -GRAVITY, 0);

        Vec3d before = getPos();
        Box boxBefore = getBoundingBox();
        setVelocity(v);
        move(MovementType.SELF, v); // zeroes whichever parts of the velocity ran into something
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
                shatter(sw, owner);
                return;
            }
            if (landed) {
                slam(sw, owner, (float) MathHelper.clamp(0.55 + fall, 0.6, 1.0));
                setStage(ROLLING);
                // One big hop forwards out of the crater, then it rolls
                setVelocity(v.x * 0.75, fall > 0.4 ? Math.min(fall * 0.35, 0.45) : 0.0, v.z * 0.75);
                velocityDirty = true;
            }
            return;
        }

        if (landed && fall > 0.55) {
            thud(sw, owner);
            setVelocity(getVelocity().x, Math.min(fall * 0.3, 0.3), getVelocity().z);
        }
        if (hitWall && across > 0.2) {
            shatter(sw, owner);
            return;
        }
        if ((isOnGround() && getVelocity().horizontalLength() < 0.06) || stageAge > MAX_ROLL_TICKS) {
            shatter(sw, owner);
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
                hurt(sw, e, HIT_DAMAGE, owner);
                fling(e, dir.multiply(HIT_FLING).add(0, 0.65, 0));
                setVelocity(getVelocity().multiply(0.85)); // something that size slows it, a little
                impactEffects(sw, e, 1f);
                if (owner instanceof ServerPlayerEntity sp) shake(sp, 0.35f, 8);
            } else {
                if (speed < 0.12 || age - rolledOver.getOrDefault(e.getUuid(), -100) < ROLL_AGAIN) continue;
                rolledOver.put(e.getUuid(), age);
                float k = (float) MathHelper.clamp(speed / 0.6, 0.5, 1.0);
                hurt(sw, e, ROLL_DAMAGE * k, owner);
                fling(e, dir.multiply(1.2 + 1.4 * speed).add(0, 0.5, 0));
                impactEffects(sw, e, 0.7f);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Big moments
    // ---------------------------------------------------------------------

    /** Comes down: a shockwave through the ground that hurts and hurls everything near, hardest in the middle. */
    private void slam(ServerWorld sw, @Nullable PlayerEntity owner, float strength) {
        Vec3d c = getPos();
        double radius = SLAM_RADIUS * (0.7 + 0.3 * strength);
        Vec3d from = c.add(0, 0.6, 0);
        for (LivingEntity e : sw.getEntitiesByClass(LivingEntity.class, new Box(c, c).expand(radius + 1), e -> canHurt(e, owner))) {
            Vec3d at = e.getPos();
            double d = Math.sqrt(MathHelper.square(at.x - c.x) + MathHelper.square(at.z - c.z)) + Math.abs(at.y - c.y) * 0.5;
            if (d > radius || !canSee(sw, from, e)) continue;
            float k = (float) (1.0 - d / radius);
            hurt(sw, e, MathHelper.lerp(k, SLAM_DAMAGE_EDGE, SLAM_DAMAGE) * strength, owner);
            Vec3d out = at.subtract(c).multiply(1, 0, 1);
            out = out.lengthSquared() < 1e-4 ? Vec3d.ZERO : out.normalize();
            fling(e, out.multiply((0.8 + 1.7 * k) * strength).add(0, (0.45 + 0.55 * k) * strength, 0));
        }

        BlockState ground = groundBelow(sw);
        BlockStateParticleEffect dust = new BlockStateParticleEffect(ParticleTypes.BLOCK, ground);
        // A ring of dust racing out across the ground, and earth thrown up all round
        for (int i = 0; i < 32; i++) {
            double a = i * Math.PI * 2 / 32;
            double cos = Math.cos(a), sin = Math.sin(a);
            sw.spawnParticles(ParticleTypes.POOF, c.x + cos * 1.2, c.y + 0.2, c.z + sin * 1.2, 0, cos, 0.02, sin, 0.55 * strength);
            sw.spawnParticles(new BlockStateParticleEffect(ParticleTypes.DUST_PILLAR, ground), c.x + cos * radius * 0.6, c.y + 0.1, c.z + sin * radius * 0.6, 1, 0.3, 0, 0.3, 0);
        }
        sw.spawnParticles(dust, c.x, c.y + 0.3, c.z, 140, radius * 0.4, 0.3, radius * 0.4, 0.3);
        sw.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.COBBLESTONE.getDefaultState()), c.x, c.y + 0.8, c.z, 40, 0.8, 0.5, 0.8, 0.25);
        sw.spawnParticles(ParticleTypes.EXPLOSION, c.x, c.y + 0.5, c.z, 3, 1.2, 0.3, 1.2, 0);
        sw.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, c.x, c.y + 0.3, c.z, 10, 1.5, 0.2, 1.5, 0.02);

        float vol = 1.2f + strength;
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ITEM_MACE_SMASH_GROUND_HEAVY, SoundCategory.PLAYERS, vol, 0.7f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, vol * 0.7f, 0.55f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, vol * 0.5f, 0.4f);
        sw.playSound(null, c.x, c.y, c.z, ground.getSoundGroup().getBreakSound(), SoundCategory.PLAYERS, vol, 0.5f);
        ShakePayload.around(sw, c, 10, 0.8f * strength, 16);
    }

    /** A smaller landing while it's rolling, coming down off a drop. */
    private void thud(ServerWorld sw, @Nullable PlayerEntity owner) {
        Vec3d c = getPos();
        for (LivingEntity e : sw.getEntitiesByClass(LivingEntity.class, getBoundingBox().expand(1.5, 0.5, 1.5), e -> canHurt(e, owner))) {
            Vec3d out = e.getPos().subtract(c).multiply(1, 0, 1);
            out = out.lengthSquared() < 1e-4 ? Vec3d.ZERO : out.normalize();
            hurt(sw, e, ROLL_DAMAGE * 0.6f, owner);
            fling(e, out.multiply(1.0).add(0, 0.45, 0));
        }
        sw.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, groundBelow(sw)), c.x, c.y + 0.2, c.z, 50, 1.0, 0.2, 1.0, 0.2);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ITEM_MACE_SMASH_GROUND, SoundCategory.PLAYERS, 1.5f, 0.6f);
        ShakePayload.around(sw, c, 6, 0.35f, 8);
    }

    /** Breaks apart into rubble, knocking back whatever's right beside it. */
    private void shatter(ServerWorld sw, @Nullable PlayerEntity owner) {
        Vec3d c = center();
        if (stage() != FORMING) {
            for (LivingEntity e : sw.getEntitiesByClass(LivingEntity.class, new Box(c, c).expand(SHATTER_RADIUS + 1), e -> canHurt(e, owner))) {
                Vec3d at = e.getBoundingBox().getCenter();
                double d = at.distanceTo(c);
                if (d > SHATTER_RADIUS) continue;
                float k = (float) (1.0 - d / SHATTER_RADIUS);
                hurt(sw, e, MathHelper.lerp(k, 1f, SHATTER_DAMAGE), owner);
                Vec3d out = at.subtract(c).multiply(1, 0, 1);
                out = out.lengthSquared() < 1e-4 ? Vec3d.ZERO : out.normalize();
                fling(e, out.multiply(0.5 + 0.7 * k).add(0, 0.3, 0));
            }
        }
        for (BlockState s : new BlockState[]{Blocks.COBBLESTONE.getDefaultState(), Blocks.STONE.getDefaultState(),
                Blocks.ANDESITE.getDefaultState(), Blocks.MOSSY_COBBLESTONE.getDefaultState()}) {
            sw.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, s), c.x, c.y, c.z, 45, 0.6, 0.6, 0.6, 0.25);
        }
        sw.spawnParticles(ParticleTypes.POOF, c.x, c.y, c.z, 25, 0.7, 0.6, 0.7, 0.06);
        sw.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, c.x, c.y - 0.4, c.z, 5, 0.8, 0.2, 0.8, 0.01);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_STONE_BREAK, SoundCategory.PLAYERS, 2.0f, 0.5f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_DEEPSLATE_BREAK, SoundCategory.PLAYERS, 1.6f, 0.6f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_DECORATED_POT_SHATTER, SoundCategory.PLAYERS, 1.0f, 0.4f);
        ShakePayload.around(sw, c, 5, 0.25f, 6);
        discard();
    }

    // ---------------------------------------------------------------------
    // Hurting things
    // ---------------------------------------------------------------------

    private void hurt(ServerWorld sw, LivingEntity e, float amount, @Nullable PlayerEntity owner) {
        // A ploughing hit, the slam and the rubble can land within a few ticks; none should bounce off hurt immunity
        e.timeUntilRegen = 0;
        e.damage(sw.getDamageSources().thrown(this, owner), amount); // "was pummeled by"
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
        sw.spawnParticles(ParticleTypes.CRIT, c.x, c.y, c.z, (int) (25 * k), 0.3, 0.4, 0.3, 0.5);
        sw.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.COBBLESTONE.getDefaultState()), c.x, c.y, c.z, (int) (20 * k), 0.3, 0.4, 0.3, 0.2);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK, SoundCategory.PLAYERS, 1.5f * k, 0.6f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_IRON_GOLEM_DAMAGE, SoundCategory.PLAYERS, 1.2f * k, 0.5f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_STONE_BREAK, SoundCategory.PLAYERS, 1.2f * k, 0.6f);
    }

    /** Grit kicked up where it touches the ground, a grinding rumble, and a tremble underfoot. */
    private void rollEffects(ServerWorld sw, double speed) {
        Vec3d c = getPos();
        sw.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, groundBelow(sw)), c.x, c.y + 0.1, c.z, 4, 0.5, 0.05, 0.5, 0.12);
        if (age % 3 == 0) {
            float vol = (float) MathHelper.clamp(speed * 2.5, 0.4, 1.5);
            sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_STONE_STEP, SoundCategory.PLAYERS, vol, 0.5f);
            sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_GRAVEL_STEP, SoundCategory.PLAYERS, vol, 0.5f);
        }
        if (age % 4 == 0) ShakePayload.around(sw, c, 5, (float) MathHelper.clamp(speed * 0.35, 0.05, 0.2), 5);
    }

    private BlockState groundBelow(ServerWorld sw) {
        BlockPos below = BlockPos.ofFloored(getX(), getY() - 0.2, getZ());
        BlockState s = sw.getBlockState(below);
        return s.isAir() ? Blocks.STONE.getDefaultState() : s;
    }

    public Vec3d center() {
        return getPos().add(0, getHeight() / 2, 0);
    }

    /**
     * Where it's held while forming: hoisted overhead a little in front of the caster, whichever way they're looking,
     * so it's never in the way of their aim. Pulled in if that's inside a wall. Returns the boulder's (bottom-centre)
     * position.
     */
    private Vec3d holdPoint(ServerWorld sw, PlayerEntity owner) {
        Vec3d eye = owner.getEyePos();
        Vec3d flat = Vec3d.fromPolar(0f, owner.getYaw());
        Vec3d want = eye.add(flat.multiply(1.8)).add(0, 1.5, 0);
        BlockHitResult hit = sw.raycast(new RaycastContext(eye, want, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, owner));
        if (hit.getType() != HitResult.Type.MISS) {
            Vec3d back = want.subtract(eye).normalize();
            want = hit.getPos().subtract(back.multiply(1.0));
        }
        return want.subtract(0, getHeight() / 2, 0);
    }

    /**
     * The throw: from where it's held, at whatever the caster's crosshair is on (within {@link #AIM_RANGE}), lofted just
     * enough that it comes down there. Nothing under the crosshair: straight along their aim.
     */
    private Vec3d aim(ServerWorld sw, PlayerEntity owner) {
        Vec3d eye = owner.getEyePos();
        Vec3d look = owner.getRotationVec(1f);
        BlockHitResult hit = sw.raycast(new RaycastContext(eye, eye.add(look.multiply(AIM_RANGE)),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, owner));
        if (hit.getType() == HitResult.Type.MISS) return look.multiply(LAUNCH_SPEED).add(0, 0.1, 0);
        // Aim the bottom of the boulder at the spot, so it lands on it rather than overshooting by its own size
        Vec3d to = hit.getPos().subtract(getPos());
        double ticks = Math.max(to.length() / LAUNCH_SPEED, 1.0);
        Vec3d v = to.multiply(1.0 / ticks);
        return v.add(0, GRAVITY * ticks * 0.5, 0); // make up for the drop on the way
    }

    private static void shake(ServerPlayerEntity player, float strength, int ticks) {
        if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player, ShakePayload.ID)) {
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, new ShakePayload(strength, ticks));
        }
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
        Vec3d moved = getPos().subtract(prevX, prevY, prevZ);
        Vec3d c = center();
        switch (stage()) {
            case FORMING -> {
                tumble.spin(0.12f, 0.3f, 1f, 0.2f);
                // Grit and pebbles drawn in from above and around as it forms (never from below, in the caster's face)
                for (int i = 0; i < 4; i++) {
                    Vec3d off = new Vec3d(random.nextGaussian(), Math.abs(random.nextGaussian()) + 0.3, random.nextGaussian()).normalize().multiply(1.8);
                    getWorld().addParticle(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.STONE.getDefaultState()),
                            c.x + off.x, c.y + off.y, c.z + off.z, -off.x * 0.2, -off.y * 0.2, -off.z * 0.2);
                }
            }
            case FLYING -> {
                tumble.roll(moved, RADIUS, 0.45f, 0.6f);
                for (int i = 0; i < 3; i++) {
                    getWorld().addParticle(new BlockStateParticleEffect(ParticleTypes.FALLING_DUST, Blocks.STONE.getDefaultState()),
                            c.x + random.nextGaussian() * 0.5, c.y + random.nextGaussian() * 0.5, c.z + random.nextGaussian() * 0.5, 0, 0, 0);
                }
                if (random.nextInt(3) == 0) {
                    getWorld().addParticle(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.COBBLESTONE.getDefaultState()),
                            c.x, c.y, c.z, -moved.x * 0.2, 0.1, -moved.z * 0.2);
                }
            }
            case ROLLING -> tumble.roll(moved, RADIUS, 1f, 0.6f);
            default -> {}
        }
    }

    /** How big to draw it part way through this tick: it swells into being while forming, overshooting a touch. */
    public float formScale(float tickDelta) {
        if (stage() != FORMING) return 1f;
        float t = MathHelper.clamp((age + tickDelta) / FORM_TICKS, 0f, 1f);
        float back = 1.6f;
        float u = t - 1f;
        return MathHelper.clamp(0.15f + 0.85f * (1f + (back + 1f) * u * u * u + back * u * u), 0.15f, 1.15f);
    }

    // ---------------------------------------------------------------------
    // Never saved (see shouldSave)
    // ---------------------------------------------------------------------

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {}

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {}
}
