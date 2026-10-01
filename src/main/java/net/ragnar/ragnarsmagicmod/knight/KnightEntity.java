package net.ragnar.ragnarsmagicmod.knight;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.AnimationState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.Tameable;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.Angerable;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.entity.CloneEntity;
import net.ragnar.ragnarsmagicmod.network.ShakePayload;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The Tome of Knight's knight: a three-block-tall ancient warrior that climbs out of the ground, guards its caster
 * and cuts down whatever threatens them. It fights in a three-swing combo (overhead cleave, wide sweep, rising
 * uppercut) and charges shield-first at anything too far away. KnightSpell owns its lifetime; on the client the
 * swings are keyframe animations started by entity statuses.
 */
public class KnightEntity extends PathAwareEntity {
    private static final TrackedData<Optional<UUID>> OWNER =
            DataTracker.registerData(KnightEntity.class, TrackedDataHandlerRegistry.OPTIONAL_UUID);

    // Entity statuses that start the client-side animations (vanilla's own stop well below 100)
    static final byte STATUS_RISE = 100, STATUS_CLEAVE = 101, STATUS_SWEEP = 102, STATUS_UPPERCUT = 103,
            STATUS_DASH_WINDUP = 104, STATUS_DASH_STRIKE = 105, STATUS_DISMISS = 106;

    public static final float DAMAGE = 20f;
    static final int RISE_TICKS = 30;
    static final int DISMISS_TICKS = 30;
    private static final double REACH = 2.6;            // from the edge of its hitbox
    private static final int SWING_REST = 4;            // pause between swings
    private static final double DASH_MIN = 6.5, DASH_MAX = 20.0;
    private static final int DASH_WINDUP_TICKS = 12, DASH_MAX_TICKS = 16, DASH_RECOVER_TICKS = 14, DASH_COOLDOWN = 100;
    private static final double DASH_SPEED = 1.25;
    private static final double GUARD_RANGE = 14.0;     // auto-targets monsters this close to the caster
    private static final double LEASH = 26.0;           // drops a fight this far from the caster (unless ordered)
    static final double COMMAND_RANGE = 48.0;
    private static final double CATCH_UP = 28.0;
    private static final int CALM_TICKS = 100;

    private static final DustParticleEffect MARK = new DustParticleEffect(new Vector3f(1.0f, 0.2f, 0.15f), 1.4f);
    private static final BlockStateParticleEffect MOSS_DUST = new BlockStateParticleEffect(ParticleTypes.FALLING_DUST, Blocks.MOSS_BLOCK.getDefaultState());
    private static final BlockStateParticleEffect MOSS_CHUNKS = new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.MOSS_BLOCK.getDefaultState());

    private enum Phase { RISING, READY, SWING, DASH_WINDUP, DASHING, RECOVER, DISMISSING }

    private enum Swing {
        CLEAVE(STATUS_CLEAVE, 18, 8, 55),
        SWEEP(STATUS_SWEEP, 17, 7, 105),
        UPPERCUT(STATUS_UPPERCUT, 16, 6, 55);

        final byte status;
        final int length, hitAt;
        final double halfArcCos;

        Swing(byte status, int length, int hitAt, double halfArcDegrees) {
            this.status = status;
            this.length = length;
            this.hitAt = hitAt;
            this.halfArcCos = Math.cos(Math.toRadians(halfArcDegrees));
        }
    }

    // Client: one state per animation, see KnightModel
    public final AnimationState riseAnim = new AnimationState();
    public final AnimationState cleaveAnim = new AnimationState();
    public final AnimationState sweepAnim = new AnimationState();
    public final AnimationState uppercutAnim = new AnimationState();
    public final AnimationState dashWindupAnim = new AnimationState();
    public final AnimationState dashStrikeAnim = new AnimationState();
    public final AnimationState dismissAnim = new AnimationState();

    // Server
    private Phase phase = Phase.RISING;
    private int phaseTicks;
    private Swing swing = Swing.CLEAVE;
    private int combo;
    private LivingEntity swingTarget;
    private int attackCooldown;
    private int dashCooldown = 40;
    private Vec3d dashDir = Vec3d.ZERO;
    private LivingEntity dashTarget;
    private final Set<Integer> dashHits = new HashSet<>();
    private LivingEntity commandTarget;
    private int calmUntil;
    private LivingEntity guardTarget;
    private int chaseTicks;
    private LivingEntity givenUpOn;
    private int givenUpUntil;
    private int repath;
    private float followAngle = 140f;

    public KnightEntity(EntityType<? extends PathAwareEntity> type, World world) {
        super(type, world);
        this.experiencePoints = 0;
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return MobEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 250.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.3)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, DAMAGE)
                .add(EntityAttributes.GENERIC_ARMOR, 10.0)
                .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 1.0)
                .add(EntityAttributes.GENERIC_STEP_HEIGHT, 1.1)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 48.0);
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(OWNER, Optional.empty());
    }

    @Override
    protected void initGoals() {
        // Everything else is the state machine in mobTick
        this.goalSelector.add(0, new SwimGoal(this));
    }

    void setOwner(PlayerEntity owner) {
        this.dataTracker.set(OWNER, Optional.of(owner.getUuid()));
    }

    public Optional<UUID> getOwnerUuid() {
        return this.dataTracker.get(OWNER);
    }

    public boolean isOwnedBy(Entity entity) {
        return entity != null && getOwnerUuid().map(entity.getUuid()::equals).orElse(false);
    }

    public PlayerEntity getOwner() {
        return getOwnerUuid().map(id -> getWorld().getPlayerByUuid(id)).orElse(null);
    }

    boolean isDismissing() {
        return phase == Phase.DISMISSING;
    }

    /** Called right after spawning: it climbs out of the ground. */
    void rise() {
        setPhase(Phase.RISING);
        getWorld().sendEntityStatus(this, STATUS_RISE);
    }

    // ---------------------------------------------------------------------
    // Orders from the caster
    // ---------------------------------------------------------------------

    void command(LivingEntity target) {
        boolean fresh = target != commandTarget;
        commandTarget = target;
        calmUntil = 0;
        if (fresh) dashCooldown = 0; // a new order: charge straight in if it's far
        target.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 30, 0, false, false, false));

        ServerWorld world = (ServerWorld) getWorld();
        Vec3d t = target.getPos();
        double r = target.getWidth() * 0.5 + 0.8;
        for (int i = 0; i < 28; i++) {
            double a = i * Math.PI * 2 / 28;
            world.spawnParticles(MARK, t.x + Math.cos(a) * r, t.y + 0.1, t.z + Math.sin(a) * r, 1, 0, 0, 0, 0);
        }
        world.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, getX(), getEyeY(), getZ(), 6, 0.3, 0.2, 0.3, 0.02);
        world.playSound(null, getX(), getY(), getZ(), SoundEvents.ITEM_ARMOR_EQUIP_NETHERITE.value(), SoundCategory.PLAYERS, 1.2f, 0.6f);
        world.playSound(null, t.x, t.y, t.z, SoundEvents.ENTITY_EVOKER_PREPARE_ATTACK, SoundCategory.PLAYERS, 0.8f, 0.7f);
    }

    void recall() {
        commandTarget = null;
        guardTarget = null;
        setTarget(null);
        calmUntil = this.age + CALM_TICKS;
        ServerWorld world = (ServerWorld) getWorld();
        world.spawnParticles(ParticleTypes.SOUL, getX(), getY() + 2.0, getZ(), 6, 0.4, 0.5, 0.4, 0.02);
        world.playSound(null, getX(), getY(), getZ(), SoundEvents.ITEM_ARMOR_EQUIP_IRON.value(), SoundCategory.PLAYERS, 1.2f, 0.6f);
    }

    /** Kneels, plants its sword and sinks back into the earth. */
    void dismiss() {
        if (phase == Phase.DISMISSING) return;
        setPhase(Phase.DISMISSING);
        getNavigation().stop();
        setTarget(null);
        setVelocity(Vec3d.ZERO);
        getWorld().sendEntityStatus(this, STATUS_DISMISS);
        getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.ITEM_ARMOR_EQUIP_NETHERITE.value(), SoundCategory.PLAYERS, 1.2f, 0.5f);
        getWorld().playSound(null, getX(), getY(), getZ(), SoundEvents.BLOCK_BELL_RESONATE, SoundCategory.PLAYERS, 0.6f, 0.6f);
    }

    // ---------------------------------------------------------------------
    // Ticking
    // ---------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (getWorld().isClient) {
            // Moss shaking loose as it moves
            if (this.age % 5 == 0 && !this.dismissAnim.isRunning() && random.nextInt(3) == 0) {
                getWorld().addParticle(MOSS_DUST, getX() + (random.nextDouble() - 0.5) * 1.6, getY() + 2.0 + random.nextDouble() * 0.6,
                        getZ() + (random.nextDouble() - 0.5) * 1.6, 0, 0, 0);
            }
            return;
        }
        // Left over from a reload, /summon, or a lost caster: a knight can't outlive its spell
        if (!KnightSpell.isLive(this)) discard();
    }

    @Override
    protected void mobTick() {
        super.mobTick();
        if (attackCooldown > 0) attackCooldown--;
        if (dashCooldown > 0) dashCooldown--;
        phaseTicks++;
        ServerWorld world = (ServerWorld) getWorld();

        if (commandTarget != null && this.age % 10 == 0 && commandTarget.isAlive()) {
            commandTarget.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 30, 0, false, false, false));
        }

        switch (phase) {
            case RISING -> {
                risingEffects(world);
                if (phaseTicks >= RISE_TICKS) setPhase(Phase.READY);
            }
            case DISMISSING -> {
                dismissEffects(world);
                if (phaseTicks >= DISMISS_TICKS) vanish(world);
            }
            case SWING -> swingTick(world);
            case DASH_WINDUP -> dashWindupTick(world);
            case DASHING -> dashTick(world);
            case RECOVER -> {
                if (phaseTicks >= DASH_RECOVER_TICKS) setPhase(Phase.READY);
            }
            case READY -> readyTick(world);
        }
    }

    private void setPhase(Phase p) {
        phase = p;
        phaseTicks = 0;
    }

    private void readyTick(ServerWorld world) {
        PlayerEntity owner = getOwner();
        if (owner == null) {
            getNavigation().stop();
            return;
        }
        LivingEntity target = pickTarget(world, owner);
        if (target != getTarget()) chaseTicks = 0;
        setTarget(target);
        if (target == null) {
            follow(owner);
            return;
        }

        getLookControl().lookAt(target, 30f, 30f);
        double dist = flatDistance(target);
        double dy = target.getY() - getY();
        boolean inReach = dist <= reachTo(target) && dy > -1.5 && dy < 3.2;

        if (inReach) {
            getNavigation().stop();
            faceTowards(target, 40f);
            if (attackCooldown <= 0) startSwing(world, target);
            return;
        }
        if (dashCooldown <= 0 && dist >= DASH_MIN && dist <= DASH_MAX && Math.abs(dy) < 2.5 && isOnGround() && canSee(target)) {
            startDashWindup(world, target);
            return;
        }

        // Chase; give up on something it can't get to, unless it was ordered
        if (++chaseTicks > 160 && target != commandTarget) {
            givenUpOn = target;
            givenUpUntil = this.age + 200;
            guardTarget = null;
            setTarget(null);
            return;
        }
        if (--repath <= 0) {
            repath = 6;
            getNavigation().startMovingTo(target, dist > 8 ? 1.3 : 1.15);
        }
    }

    /** Follows a step behind its caster, catching up in a burst of soul fire if left far behind. */
    private void follow(PlayerEntity owner) {
        double d2 = squaredDistanceTo(owner);
        if (d2 > CATCH_UP * CATCH_UP) {
            teleportNear(owner);
            return;
        }
        if (this.age % 80 == 0) followAngle = 140f + (random.nextFloat() - 0.5f) * 40f;
        double a = Math.toRadians(owner.getYaw() + followAngle);
        Vec3d spot = owner.getPos().add(-Math.sin(a) * 3.2, 0, Math.cos(a) * 3.2);

        if (squaredDistanceTo(spot) > 2.2 * 2.2) {
            if (--repath <= 0) {
                repath = 8;
                getNavigation().startMovingTo(spot.x, spot.y, spot.z, d2 > 12 * 12 ? 1.4 : 1.05);
            }
        } else {
            getNavigation().stop();
            // Settled in: keep watch the way the caster looks
            Vec3d focus = owner.getEyePos().add(owner.getRotationVector().multiply(16.0));
            getLookControl().lookAt(focus.x, focus.y, focus.z, 10f, 20f);
        }
    }

    private void teleportNear(PlayerEntity owner) {
        ServerWorld world = (ServerWorld) getWorld();
        for (float off : new float[]{180f, 135f, -135f, 90f, -90f, 0f}) {
            double a = Math.toRadians(owner.getYaw() + off);
            for (double r : new double[]{2.5, 3.5}) {
                Vec3d to = owner.getPos().add(-Math.sin(a) * r, 0, Math.cos(a) * r);
                BlockPos below = BlockPos.ofFloored(to).down();
                if (!world.getBlockState(below).isSideSolidFullSquare(world, below, Direction.UP)) continue;
                if (!world.isSpaceEmpty(this, getDimensions(getPose()).getBoxAt(to))) continue;
                soulBurst(world, getPos());
                getNavigation().stop();
                refreshPositionAndAngles(to.x, to.y, to.z, owner.getYaw(), 0f);
                setVelocity(Vec3d.ZERO);
                soulBurst(world, to);
                world.playSound(null, to.x, to.y, to.z, SoundEvents.PARTICLE_SOUL_ESCAPE.value(), SoundCategory.PLAYERS, 2.0f, 0.8f);
                world.playSound(null, to.x, to.y, to.z, SoundEvents.ENTITY_IRON_GOLEM_STEP, SoundCategory.PLAYERS, 1.0f, 0.6f);
                return;
            }
        }
    }

    // ---------------------------------------------------------------------
    // Targeting
    // ---------------------------------------------------------------------

    private LivingEntity pickTarget(ServerWorld world, PlayerEntity owner) {
        if (commandTarget != null && !(isFoe(commandTarget, owner) && commandTarget.squaredDistanceTo(owner) < COMMAND_RANGE * COMMAND_RANGE)) {
            commandTarget = null;
        }
        if (commandTarget != null) return commandTarget;
        if (this.age < calmUntil) return null;

        // Whoever is fighting it or its caster, then whatever the caster is hitting
        if (worthFighting(getAttacker(), owner)) return getAttacker();
        if (worthFighting(owner.getAttacker(), owner)) return owner.getAttacker();
        LivingEntity hit = owner.getAttacking();
        if (owner.age - owner.getLastAttackTime() < 100 && worthFighting(hit, owner)) return hit;
        if (worthFighting(getTarget(), owner)) return getTarget();

        // Otherwise: monsters closing in on the caster (looked for twice a second)
        if (this.age % 10 == 0 || !worthFighting(guardTarget, owner)) {
            guardTarget = null;
            double best = Double.MAX_VALUE;
            List<MobEntity> near = world.getEntitiesByClass(MobEntity.class, owner.getBoundingBox().expand(GUARD_RANGE, 6.0, GUARD_RANGE),
                    m -> m instanceof Monster && isThreat(m, owner) && worthFighting(m, owner));
            for (MobEntity m : near) {
                double d = m.squaredDistanceTo(this);
                if (d < best) {
                    best = d;
                    guardTarget = m;
                }
            }
        }
        return guardTarget;
    }

    /** Monsters that would actually start a fight; neutral ones (endermen, piglins...) only once they're angry at us. */
    private boolean isThreat(MobEntity m, PlayerEntity owner) {
        if (!(m instanceof Angerable)) return true;
        LivingEntity t = m.getTarget();
        return t == owner || t == this;
    }

    private boolean worthFighting(LivingEntity e, PlayerEntity owner) {
        if (!isFoe(e, owner)) return false;
        if (e == givenUpOn && this.age < givenUpUntil) return false;
        return e.squaredDistanceTo(owner) < LEASH * LEASH;
    }

    /** Never the caster, their pets, their clones, another knight, or players who can't be hurt. */
    boolean isFoe(LivingEntity e, PlayerEntity owner) {
        if (e == null || e == this || !e.isAlive() || e.isRemoved() || e.getWorld() != getWorld()) return false;
        if (e instanceof ArmorStandEntity || e instanceof KnightEntity) return false;
        if (owner != null) {
            if (e == owner || e == owner.getVehicle()) return false;
            if (e instanceof Tameable pet && owner.getUuid().equals(pet.getOwnerUuid())) return false;
            if (e instanceof CloneEntity clone && clone.isOwnedBy(owner)) return false;
        }
        return !(e instanceof PlayerEntity p && (p.isCreative() || p.isSpectator()));
    }

    /** Who a blow may land on: its mark, or any monster caught in the swing - never bystanders. */
    private boolean canHit(LivingEntity e, LivingEntity mark, PlayerEntity owner) {
        return isFoe(e, owner) && (e == mark || e == commandTarget || e instanceof Monster);
    }

    private double flatDistance(Entity e) {
        double dx = e.getX() - getX(), dz = e.getZ() - getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private double reachTo(LivingEntity target) {
        return getWidth() * 0.5 + target.getWidth() * 0.5 + REACH;
    }

    private void faceTowards(Entity target, float maxTurn) {
        double dx = target.getX() - getX(), dz = target.getZ() - getZ();
        float want = (float) (MathHelper.atan2(dz, dx) * MathHelper.DEGREES_PER_RADIAN) - 90f;
        float yaw = MathHelper.stepUnwrappedAngleTowards(getYaw(), want, maxTurn);
        setYaw(yaw);
        setBodyYaw(yaw);
        setHeadYaw(yaw);
    }

    private Vec3d facing() {
        return Vec3d.fromPolar(0f, getYaw());
    }

    // ---------------------------------------------------------------------
    // Sword swings: cleave -> sweep -> uppercut
    // ---------------------------------------------------------------------

    private void startSwing(ServerWorld world, LivingEntity target) {
        swing = Swing.values()[combo++ % Swing.values().length];
        swingTarget = target;
        setPhase(Phase.SWING);
        world.sendEntityStatus(this, swing.status);
        world.playSound(null, getX(), getY(), getZ(), SoundEvents.ITEM_ARMOR_EQUIP_IRON.value(), SoundCategory.HOSTILE, 0.9f, 0.6f);
    }

    private void swingTick(ServerWorld world) {
        if (phaseTicks < swing.hitAt && swingTarget != null && swingTarget.isAlive()) faceTowards(swingTarget, 30f);
        if (phaseTicks == swing.hitAt) strike(world);
        if (phaseTicks >= swing.length) {
            setPhase(Phase.READY);
            attackCooldown = SWING_REST;
        }
    }

    private void strike(ServerWorld world) {
        PlayerEntity owner = getOwner();
        Vec3d look = facing();
        double reach = getWidth() * 0.5 + REACH + 0.6;
        List<LivingEntity> victims = world.getEntitiesByClass(LivingEntity.class, getBoundingBox().expand(reach + 1.0, 1.0, reach + 1.0),
                e -> canHit(e, swingTarget, owner) && inArc(e, look, reach));
        // The mark itself gets a little leeway, so a swing that looked like it connected did
        if (swingTarget != null && !victims.contains(swingTarget) && canHit(swingTarget, swingTarget, owner)
                && flatDistance(swingTarget) <= reachTo(swingTarget) + 1.0) {
            victims.add(swingTarget);
        }

        Vec3d front = getPos().add(look.multiply(2.6));
        world.playSound(null, getX(), getY(), getZ(), SoundEvents.ITEM_MACE_SMASH_AIR, SoundCategory.HOSTILE, 1.0f, 0.7f);
        world.playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.HOSTILE, 1.0f, 0.55f);

        boolean landed = false;
        for (LivingEntity v : victims) {
            double kb = swing == Swing.SWEEP ? 1.3 : 0.7;
            double lift = swing == Swing.UPPERCUT ? 0.75 : 0.0;
            landed |= hurt(v, kb, lift);
        }

        switch (swing) {
            case CLEAVE -> {
                // The blade bites the ground
                BlockState ground = groundAt(world, front);
                if (ground != null) {
                    world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, ground), front.x, front.y + 0.1, front.z, 24, 0.5, 0.05, 0.5, 0.2);
                }
                world.spawnParticles(ParticleTypes.CRIT, front.x, front.y + 0.3, front.z, 12, 0.4, 0.1, 0.4, 0.4);
                world.playSound(null, front.x, front.y, front.z, SoundEvents.ITEM_MACE_SMASH_GROUND, SoundCategory.HOSTILE, 1.0f, 0.8f);
                ShakePayload.around(world, front, 5.0, 0.25f, 6);
            }
            case SWEEP -> {
                for (int i = -1; i <= 1; i++) {
                    Vec3d p = getPos().add(Vec3d.fromPolar(0f, getYaw() + i * 45f).multiply(2.4));
                    world.spawnParticles(ParticleTypes.SWEEP_ATTACK, p.x, getY() + 1.6, p.z, 1, 0, 0, 0, 0);
                }
            }
            case UPPERCUT -> world.spawnParticles(ParticleTypes.CLOUD, front.x, front.y + 0.2, front.z, 8, 0.4, 0.1, 0.4, 0.08);
        }

        if (landed) {
            world.playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.HOSTILE, 1.2f, 0.7f);
            world.playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_IRON_GOLEM_ATTACK, SoundCategory.HOSTILE, 1.0f, 0.75f);
        }
    }

    private boolean inArc(LivingEntity e, Vec3d look, double reach) {
        Vec3d to = new Vec3d(e.getX() - getX(), 0, e.getZ() - getZ());
        double d = to.length();
        if (d > reach + e.getWidth() * 0.5) return false;
        double dy = e.getY() - getY();
        if (dy < -1.5 || dy > 3.5) return false;
        return d < 0.8 || to.multiply(1.0 / d).dotProduct(look) >= swing.halfArcCos;
    }

    /** One blow: {@link #DAMAGE}, a shove away from the knight, and credit to the caster for the kill. */
    private boolean hurt(LivingEntity v, double knockback, double lift) {
        PlayerEntity owner = getOwner();
        if (owner != null && !(v instanceof PlayerEntity)) v.setAttacking(owner); // loot and XP as if the caster killed it
        if (!v.damage(getDamageSources().mobAttack(this), DAMAGE)) return false;
        v.takeKnockback(knockback, getX() - v.getX(), getZ() - v.getZ());
        if (lift > 0) v.addVelocity(0, lift, 0);
        v.velocityModified = true;
        ((ServerWorld) getWorld()).spawnParticles(ParticleTypes.CRIT, v.getX(), v.getBodyY(0.6), v.getZ(), 10, 0.3, 0.3, 0.3, 0.3);
        return true;
    }

    // ---------------------------------------------------------------------
    // Dash: lowers its shield and charges
    // ---------------------------------------------------------------------

    private void startDashWindup(ServerWorld world, LivingEntity target) {
        dashTarget = target;
        setPhase(Phase.DASH_WINDUP);
        getNavigation().stop();
        world.sendEntityStatus(this, STATUS_DASH_WINDUP);
        world.playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 0.9f, 1.3f);
        world.playSound(null, getX(), getY(), getZ(), SoundEvents.ITEM_ARMOR_EQUIP_NETHERITE.value(), SoundCategory.HOSTILE, 1.0f, 0.6f);
    }

    private void dashWindupTick(ServerWorld world) {
        if (dashTarget != null && dashTarget.isAlive()) faceTowards(dashTarget, 25f);
        if (phaseTicks % 2 == 0) groundBurst(world, getPos(), 4, 0.5);
        if (phaseTicks < DASH_WINDUP_TICKS) return;

        if (dashTarget == null || !dashTarget.isAlive()) {
            endDash(world, false);
            return;
        }
        Vec3d to = new Vec3d(dashTarget.getX() - getX(), 0, dashTarget.getZ() - getZ());
        dashDir = to.lengthSquared() > 1.0e-4 ? to.normalize() : facing();
        dashHits.clear();
        setPhase(Phase.DASHING);
        world.playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_BREEZE_WIND_BURST.value(), SoundCategory.HOSTILE, 1.2f, 0.6f);
        world.playSound(null, getX(), getY(), getZ(), SoundEvents.ITEM_TRIDENT_RIPTIDE_2.value(), SoundCategory.HOSTILE, 1.0f, 0.7f);
        world.spawnParticles(ParticleTypes.EXPLOSION, getX(), getY() + 0.5, getZ(), 1, 0, 0, 0, 0);
    }

    private void dashTick(ServerWorld world) {
        PlayerEntity owner = getOwner();
        // Homes in a little, so a sidestep doesn't make it look silly
        if (dashTarget != null && dashTarget.isAlive()) {
            Vec3d to = new Vec3d(dashTarget.getX() - getX(), 0, dashTarget.getZ() - getZ());
            if (to.lengthSquared() > 1.0e-4) dashDir = dashDir.lerp(to.normalize(), 0.25).normalize();
        }
        setVelocity(dashDir.x * DASH_SPEED, getVelocity().y, dashDir.z * DASH_SPEED);
        float yaw = (float) (MathHelper.atan2(dashDir.z, dashDir.x) * MathHelper.DEGREES_PER_RADIAN) - 90f;
        setYaw(yaw);
        setBodyYaw(yaw);
        setHeadYaw(yaw);

        // Trail
        Vec3d back = getPos().subtract(dashDir.multiply(0.8));
        world.spawnParticles(ParticleTypes.CLOUD, back.x, getY() + 0.3, back.z, 2, 0.3, 0.2, 0.3, 0.01);
        groundBurst(world, getPos(), 3, 0.4);
        if (phaseTicks % 3 == 0) world.playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_IRON_GOLEM_STEP, SoundCategory.HOSTILE, 1.2f, 0.7f);

        // Runs over anything in the way
        for (LivingEntity v : world.getEntitiesByClass(LivingEntity.class, getBoundingBox().expand(0.6),
                e -> canHit(e, dashTarget, owner) && !dashHits.contains(e.getId()))) {
            dashHits.add(v.getId());
            hurt(v, 1.6, 0.45);
        }

        boolean reached = dashTarget != null && dashTarget.isAlive()
                && flatDistance(dashTarget) <= reachTo(dashTarget) - 0.4;
        boolean blocked = horizontalCollision && phaseTicks > 2;
        if (reached || blocked || phaseTicks >= DASH_MAX_TICKS) endDash(world, reached);
    }

    /** The charge ends in a heavy slash (into the target if it got there). */
    private void endDash(ServerWorld world, boolean reached) {
        PlayerEntity owner = getOwner();
        setVelocity(dashDir.multiply(0.15).add(0, getVelocity().y, 0));
        world.sendEntityStatus(this, STATUS_DASH_STRIKE);
        if (reached && !dashHits.contains(dashTarget.getId()) && canHit(dashTarget, dashTarget, owner)) {
            hurt(dashTarget, 1.4, 0.3);
        }
        Vec3d front = getPos().add(facing().multiply(2.0));
        groundBurst(world, front, 30, 1.0);
        world.spawnParticles(ParticleTypes.SWEEP_ATTACK, front.x, getY() + 1.6, front.z, 1, 0, 0, 0, 0);
        world.spawnParticles(ParticleTypes.EXPLOSION, front.x, getY() + 0.6, front.z, 1, 0, 0, 0, 0);
        world.playSound(null, front.x, front.y, front.z, SoundEvents.ITEM_MACE_SMASH_GROUND_HEAVY, SoundCategory.HOSTILE, 1.0f, 0.8f);
        world.playSound(null, front.x, front.y, front.z, SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK, SoundCategory.HOSTILE, 1.2f, 0.6f);
        ShakePayload.around(world, front, 6.0, 0.4f, 10);
        setPhase(Phase.RECOVER);
        dashCooldown = DASH_COOLDOWN;
        attackCooldown = DASH_RECOVER_TICKS;
        dashTarget = null;
    }

    // ---------------------------------------------------------------------
    // Arriving and leaving
    // ---------------------------------------------------------------------

    private void risingEffects(ServerWorld world) {
        if (phaseTicks % 2 == 0) groundBurst(world, getPos(), 8, 0.8);
        if (phaseTicks % 3 == 0) {
            world.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, getX(), getY() + 0.2, getZ(), 4, 0.6, 0.1, 0.6, 0.04);
            world.spawnParticles(MOSS_CHUNKS, getX(), getY() + 0.3, getZ(), 6, 0.7, 0.2, 0.7, 0.1);
        }
        if (phaseTicks == 22) {
            world.playSound(null, getX(), getY(), getZ(), SoundEvents.ITEM_ARMOR_EQUIP_NETHERITE.value(), SoundCategory.PLAYERS, 1.5f, 0.5f);
            world.playSound(null, getX(), getY(), getZ(), SoundEvents.BLOCK_BELL_RESONATE, SoundCategory.PLAYERS, 0.8f, 0.7f);
        }
    }

    private void dismissEffects(ServerWorld world) {
        if (phaseTicks > 12 && phaseTicks % 2 == 0) groundBurst(world, getPos(), 6, 0.8);
        if (phaseTicks % 4 == 0) {
            world.spawnParticles(ParticleTypes.SOUL, getX(), getY() + 1.8, getZ(), 2, 0.5, 0.6, 0.5, 0.02);
            world.spawnParticles(MOSS_DUST, getX(), getY() + 2.2, getZ(), 4, 0.6, 0.4, 0.6, 0);
        }
        if (phaseTicks == 12) world.playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_WARDEN_DIG, SoundCategory.PLAYERS, 1.0f, 0.9f);
    }

    private void vanish(ServerWorld world) {
        world.spawnParticles(ParticleTypes.SOUL, getX(), getY() + 0.5, getZ(), 14, 0.6, 0.4, 0.6, 0.05);
        world.spawnParticles(MOSS_CHUNKS, getX(), getY() + 0.3, getZ(), 30, 0.8, 0.2, 0.8, 0.15);
        world.playSound(null, getX(), getY(), getZ(), SoundEvents.PARTICLE_SOUL_ESCAPE.value(), SoundCategory.PLAYERS, 2.0f, 0.7f);
        world.playSound(null, getX(), getY(), getZ(), SoundEvents.BLOCK_MOSS_BREAK, SoundCategory.PLAYERS, 1.5f, 0.6f);
        discard();
    }

    private void soulBurst(ServerWorld world, Vec3d at) {
        world.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, at.x, at.y + 1.5, at.z, 20, 0.5, 0.9, 0.5, 0.02);
        world.spawnParticles(ParticleTypes.SOUL, at.x, at.y + 1.5, at.z, 6, 0.4, 0.8, 0.4, 0.03);
        world.spawnParticles(MOSS_CHUNKS, at.x, at.y + 0.2, at.z, 12, 0.6, 0.1, 0.6, 0.1);
    }

    /** Dirt (or whatever it stands on) kicked up around {@code at}. */
    private void groundBurst(ServerWorld world, Vec3d at, int count, double spread) {
        BlockState ground = groundAt(world, at);
        if (ground == null) return;
        world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, ground), at.x, at.y + 0.1, at.z, count, spread, 0.05, spread, 0.15);
    }

    private static BlockState groundAt(ServerWorld world, Vec3d at) {
        BlockPos pos = BlockPos.ofFloored(at.x, at.y - 0.2, at.z);
        BlockState state = world.getBlockState(pos);
        return state.isAir() ? null : state;
    }

    // ---------------------------------------------------------------------
    // Client animations
    // ---------------------------------------------------------------------

    @Override
    public void handleStatus(byte status) {
        AnimationState anim = switch (status) {
            case STATUS_RISE -> riseAnim;
            case STATUS_CLEAVE -> cleaveAnim;
            case STATUS_SWEEP -> sweepAnim;
            case STATUS_UPPERCUT -> uppercutAnim;
            case STATUS_DASH_WINDUP -> dashWindupAnim;
            case STATUS_DASH_STRIKE -> dashStrikeAnim;
            case STATUS_DISMISS -> dismissAnim;
            default -> null;
        };
        if (anim == null) {
            super.handleStatus(status);
            return;
        }
        // One at a time: each starts from the resting pose (the dash strike picks up the charge pose itself)
        for (AnimationState a : new AnimationState[]{riseAnim, cleaveAnim, sweepAnim, uppercutAnim, dashWindupAnim, dashStrikeAnim, dismissAnim}) {
            a.stop();
        }
        anim.start(this.age);
    }

    // ---------------------------------------------------------------------
    // Vanilla behaviour
    // ---------------------------------------------------------------------

    @Override
    public boolean damage(DamageSource source, float amount) {
        if (phase == Phase.RISING || phase == Phase.DISMISSING) return false;
        if (source.isOf(DamageTypes.IN_WALL) || source.isOf(DamageTypes.DROWN) || source.isOf(DamageTypes.FALL)) return false;
        Entity attacker = source.getAttacker();
        if (isOwnedBy(attacker) || attacker instanceof KnightEntity) return false;
        if (attacker instanceof Tameable pet && getOwnerUuid().map(id -> id.equals(pet.getOwnerUuid())).orElse(false)) return false;
        return super.damage(source, amount);
    }

    @Override
    public void onDeath(DamageSource source) {
        super.onDeath(source);
        if (getWorld() instanceof ServerWorld world) {
            soulBurst(world, getPos());
            world.playSound(null, getX(), getY(), getZ(), SoundEvents.PARTICLE_SOUL_ESCAPE.value(), SoundCategory.HOSTILE, 2.0f, 0.6f);
        }
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        playSound(SoundEvents.ENTITY_IRON_GOLEM_STEP, 0.9f, 0.75f);
        playSound(SoundEvents.BLOCK_CHAIN_STEP, 0.5f, 0.7f);
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.ENTITY_IRON_GOLEM_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.ENTITY_IRON_GOLEM_DEATH;
    }

    @Override
    public float getSoundPitch() {
        return 0.7f + random.nextFloat() * 0.1f;
    }

    @Override
    public int getMaxLookYawChange() {
        return 20;
    }

    // The caster walks right through it
    @Override
    public void pushAwayFrom(Entity entity) {
        if (!isOwnedBy(entity)) super.pushAwayFrom(entity);
    }

    @Override
    protected void pushAway(Entity entity) {
        if (!isOwnedBy(entity)) super.pushAway(entity);
    }

    @Override
    public boolean canUsePortals(boolean allowVehicles) {
        return false;
    }

    @Override
    public boolean cannotDespawn() {
        return true;
    }

    @Override
    public boolean canPickUpLoot() {
        return false;
    }

    @Override
    protected boolean shouldDropLoot() {
        return false;
    }
}
