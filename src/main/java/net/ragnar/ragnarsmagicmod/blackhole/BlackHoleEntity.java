package net.ragnar.ragnarsmagicmod.blackhole;

import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The black hole. It lives in four stages:
 * <ol>
 *   <li><b>Forming</b> - for {@link #FORM_TICKS} (2.5 seconds) it hangs in front of the caster, following their aim.
 *       It opens as a pinpoint and swells into a black sphere; a disk of blocks spins up around it from the outside
 *       in, jets fire from its poles, and the ground around tears loose and falls in (all drawn on the client, see
 *       BlackHoleRenderer). Things nearby already start to slide toward it.</li>
 *   <li><b>Drifting</b> - for {@link #DRIFT_TICKS} it creeps forward the way the caster was looking, hovering over the
 *       ground and sliding along walls. Everything within {@link #PULL_RADIUS} is dragged in, lifted off its feet and
 *       swirled round it; anything in the inner ring takes {@link #INNER_DAMAGE} and anything caught in the core
 *       {@link #CORE_DAMAGE}, every half second. Dropped items, XP and projectiles are pulled in too.</li>
 *   <li><b>Collapsing</b> - for a second it stops and caves in on itself, the disk spiralling into it, pulling harder
 *       than ever.</li>
 *   <li><b>Burst</b> - it explodes: everything within {@link #BURST_RADIUS} takes up to {@link #BURST_DAMAGE} and is
 *       hurled away, and everything it had swallowed is flung back out. It lingers a moment so the client can draw the
 *       shockwave, then it's gone.</li>
 * </ol>
 * It never breaks blocks and never touches its caster, their pets or their teammates.
 */
public class BlackHoleEntity extends Entity {
    public static final int FORM_TICKS = 50;
    public static final int DRIFT_TICKS = 140;
    public static final int COLLAPSE_TICKS = 20;
    public static final int BURST_TICKS = 26;
    public static final double SPEED = 0.2;
    private static final double HOLD_DISTANCE = 7.0;

    public static final double PULL_RADIUS = 14.0;
    public static final double CORE_RADIUS = 2.4;
    public static final double INNER_RADIUS = 5.0;
    public static final float CORE_DAMAGE = 7f, INNER_DAMAGE = 3f;
    public static final int HURT_EVERY = 10;
    public static final float BURST_DAMAGE = 24f, BURST_DAMAGE_EDGE = 6f;
    public static final double BURST_RADIUS = 9.0;
    /** Drawn radius of the black sphere, in blocks. */
    public static final float HORIZON = 1.5f;

    public static final int FORMING = 0, DRIFTING = 1, COLLAPSING = 2, BURST = 3;
    private static final TrackedData<Integer> STAGE = DataTracker.registerData(BlackHoleEntity.class, TrackedDataHandlerRegistry.INTEGER);

    // Server
    @Nullable private UUID ownerId;
    private int stageAge;
    private int nextBeat;
    private Vec3d heading = Vec3d.ZERO;
    private final Map<UUID, Integer> nextHurt = new HashMap<>();

    // Client
    @Nullable private Vec3d lerpTo;
    private int seenStage = FORMING, stageSeenAt;
    /** Chunks of the ground falling in. */
    public final List<Debris> debris = new ArrayList<>();

    public BlackHoleEntity(EntityType<? extends BlackHoleEntity> type, World world) {
        super(type, world);
        noClip = false;
    }

    BlackHoleEntity(ServerWorld world, PlayerEntity owner) {
        this(BlackHoles.BLACK_HOLE, world);
        ownerId = owner.getUuid();
        setPosition(holdPoint(world, owner));
        resetPosition();
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
        return distance < 160 * 160;
    }

    @Override
    public Box getVisibilityBoundingBox() {
        // The disk, the jets and the debris reach far outside the little hitbox
        return getBoundingBox().expand(14);
    }

    @Override
    public boolean isCollidable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    public Vec3d center() {
        return getPos().add(0, getHeight() / 2, 0);
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
        switch (stage()) {
            case FORMING -> form(sw, owner);
            case DRIFTING -> drift(sw, owner);
            case COLLAPSING -> collapse(sw, owner);
            case BURST -> {
                if (stageAge >= BURST_TICKS) discard();
            }
            default -> {}
        }
    }

    /** Held in front of the caster, following their aim, swelling and spinning up, with a quickening heartbeat. */
    private void form(ServerWorld sw, @Nullable PlayerEntity owner) {
        if (owner == null || !owner.isAlive()) {
            fizzle(sw);
            return;
        }
        setPosition(holdPoint(sw, owner));
        float t = stageAge / (float) FORM_TICKS;
        pull(sw, owner, 0.1f + 0.7f * t * t, false);

        Vec3d c = center();
        if (stageAge >= nextBeat) {
            sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 2.0f, 0.5f + 0.5f * t);
            nextBeat = stageAge + Math.max(4, Math.round(14 - 10 * t));
        }
        if (stageAge % 10 == 3) {
            sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.PLAYERS, 1.2f, 0.5f + 0.6f * t);
        }
        if (stageAge == 18) sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_BEACON_POWER_SELECT, SoundCategory.PLAYERS, 1.8f, 0.5f);
        if (stageAge == FORM_TICKS - 14) {
            sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_CONDUIT_ACTIVATE, SoundCategory.PLAYERS, 2.0f, 0.5f);
            sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.PLAYERS, 0.7f, 0.5f);
        }
        if (stageAge % 4 == 0) ShakePayload.around(sw, c, 10, 0.04f + 0.3f * t * t, 6);
        if (stageAge >= FORM_TICKS) release(sw, owner);
    }

    /** Loosed: it lurches off the way the caster is looking, with a boom that rolls on and on. */
    private void release(ServerWorld sw, PlayerEntity owner) {
        Vec3d look = owner.getRotationVec(1f);
        heading = new Vec3d(look.x, MathHelper.clamp(look.y, -0.35, 0.35), look.z);
        heading = heading.lengthSquared() < 1e-4 ? Vec3d.fromPolar(0f, owner.getYaw()) : heading.normalize();
        setStage(DRIFTING);

        Vec3d c = center();
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.PLAYERS, 2.5f, 0.55f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 2.5f, 0.5f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 0.9f, 0.5f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_END_PORTAL_SPAWN, SoundCategory.PLAYERS, 0.8f, 1.4f);
        sw.spawnParticles(ParticleTypes.SONIC_BOOM, c.x, c.y, c.z, 1, 0, 0, 0, 0);
        sw.spawnParticles(ParticleTypes.FLASH, c.x, c.y, c.z, 1, 0, 0, 0, 0);
        sw.spawnParticles(ParticleTypes.REVERSE_PORTAL, c.x, c.y, c.z, 120, 0.3, 0.3, 0.3, 0.6);
        if (owner instanceof ServerPlayerEntity sp) shake(sp, 0.6f, 14);
        ShakePayload.around(sw, c, 12, 0.5f, 12);
    }

    /** Creeping forward, hovering, dragging in and crushing everything near, with a slow deep heartbeat. */
    private void drift(ServerWorld sw, @Nullable PlayerEntity owner) {
        float ramp = MathHelper.clamp(stageAge / 15f, 0f, 1f);
        Vec3d v = heading.multiply(SPEED * ramp * (2 - ramp));
        // Hovers a block or so up: never sinks into the floor, rides up over the ground when it's heading down
        if (!sw.isSpaceEmpty(this, getBoundingBox().offset(0, -2.0, 0))) v = new Vec3d(v.x, Math.max(v.y, 0.06), v.z);
        move(MovementType.SELF, v);

        pull(sw, owner, 1f, true);

        Vec3d c = center();
        if (stageAge % 20 == 0) sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 2.5f, 0.45f);
        if (stageAge % 30 == 15) sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_CONDUIT_AMBIENT_SHORT, SoundCategory.PLAYERS, 2.0f, 0.5f);
        if (stageAge % 40 == 25) sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_RESPAWN_ANCHOR_AMBIENT, SoundCategory.PLAYERS, 2.0f, 0.5f);
        if (stageAge % 5 == 0) ShakePayload.around(sw, c, 9, 0.16f, 7);
        if (stageAge % 3 == 0) {
            sw.spawnParticles(ParticleTypes.REVERSE_PORTAL, c.x, c.y, c.z, 3, 0.4, 0.4, 0.4, 0.15);
        }

        if (stageAge >= DRIFT_TICKS) {
            setStage(COLLAPSING);
            sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_BEACON_DEACTIVATE, SoundCategory.PLAYERS, 2.5f, 0.5f);
            sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_CONDUIT_DEACTIVATE, SoundCategory.PLAYERS, 2.5f, 0.5f);
            sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_PORTAL_TRIGGER, SoundCategory.PLAYERS, 1.2f, 1.8f);
        }
    }

    /** Stops dead and caves in, pulling harder and harder, with a rising whine. */
    private void collapse(ServerWorld sw, @Nullable PlayerEntity owner) {
        float t = stageAge / (float) COLLAPSE_TICKS;
        pull(sw, owner, 1.3f + 1.2f * t, true);
        Vec3d c = center();
        if (stageAge % 3 == 0) {
            sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.PLAYERS, 1.8f, 0.6f + 1.3f * t);
        }
        if (stageAge % 2 == 0) ShakePayload.around(sw, c, 10, 0.15f + 0.45f * t, 5);
        sw.spawnParticles(ParticleTypes.REVERSE_PORTAL, c.x, c.y, c.z, 6, 0.2, 0.2, 0.2, 0.05);
        if (stageAge >= COLLAPSE_TICKS) burst(sw, owner);
    }

    /** Bursts: a blinding flash and a shockwave that hurts and hurls everything near, spitting out all it swallowed. */
    private void burst(ServerWorld sw, @Nullable PlayerEntity owner) {
        Vec3d c = center();
        for (Entity e : sw.getOtherEntities(this, new Box(c, c).expand(BURST_RADIUS + 1), e -> affects(e, owner))) {
            Vec3d at = e.getBoundingBox().getCenter();
            double d = at.distanceTo(c);
            if (d > BURST_RADIUS || !canSee(sw, c, e)) continue;
            float k = (float) (1.0 - d / BURST_RADIUS);
            Vec3d out = at.subtract(c);
            out = out.lengthSquared() < 1e-4 ? Vec3d.fromPolar(0f, random.nextFloat() * 360f) : out.normalize();
            if (e instanceof LivingEntity le) hurt(sw, le, MathHelper.lerp(k, BURST_DAMAGE_EDGE, BURST_DAMAGE), owner);
            double give = resistance(e);
            Vec3d push = out.multiply((1.0 + 2.3 * k) * give).add(0, (0.45 + 0.6 * k) * give, 0);
            e.setVelocity(e.getVelocity().multiply(0.2).add(push));
            e.velocityModified = true;
            e.velocityDirty = true;
        }

        sw.spawnParticles(ParticleTypes.FLASH, c.x, c.y, c.z, 2, 0, 0, 0, 0);
        sw.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y, c.z, 1, 0, 0, 0, 0);
        sw.spawnParticles(ParticleTypes.SONIC_BOOM, c.x, c.y, c.z, 3, 0.6, 0.6, 0.6, 0);
        sw.spawnParticles(ParticleTypes.REVERSE_PORTAL, c.x, c.y, c.z, 300, 0.4, 0.4, 0.4, 1.2);
        sw.spawnParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, 90, 0.3, 0.3, 0.3, 0.55);
        sw.spawnParticles(ParticleTypes.DRAGON_BREATH, c.x, c.y, c.z, 80, 0.5, 0.5, 0.5, 0.25);
        for (BlockState s : new BlockState[]{Blocks.OBSIDIAN.getDefaultState(), Blocks.CRYING_OBSIDIAN.getDefaultState(),
                Blocks.AMETHYST_BLOCK.getDefaultState(), Blocks.BLACK_CONCRETE.getDefaultState()}) {
            sw.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, s), c.x, c.y, c.z, 50, 1.2, 1.2, 1.2, 0.4);
        }
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 4.0f, 0.5f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.PLAYERS, 3.0f, 0.8f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ITEM_TRIDENT_THUNDER.value(), SoundCategory.PLAYERS, 2.0f, 0.6f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 2.0f, 0.5f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_END_PORTAL_SPAWN, SoundCategory.PLAYERS, 1.2f, 0.7f);
        ShakePayload.around(sw, c, 18, 1.2f, 26);
        setStage(BURST);
    }

    /** Never got loosed (the caster died or left): it just winks out. */
    private void fizzle(ServerWorld sw) {
        Vec3d c = center();
        sw.spawnParticles(ParticleTypes.REVERSE_PORTAL, c.x, c.y, c.z, 40, 0.2, 0.2, 0.2, 0.3);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_BEACON_DEACTIVATE, SoundCategory.PLAYERS, 1.5f, 0.6f);
        discard();
    }

    // ---------------------------------------------------------------------
    // Gravity
    // ---------------------------------------------------------------------

    /**
     * Drags everything near toward the middle, harder the closer it is, swirled round the way the disk spins. Things
     * that get close are lifted off their feet, and anything that reaches the core is held there, tumbling, while it's
     * crushed ({@code crush}).
     */
    private void pull(ServerWorld sw, @Nullable PlayerEntity owner, float strength, boolean crush) {
        Vec3d c = center();
        for (Entity e : sw.getOtherEntities(this, new Box(c, c).expand(PULL_RADIUS), e -> affects(e, owner))) {
            Vec3d at = e.getBoundingBox().getCenter();
            Vec3d to = c.subtract(at);
            double d = to.length();
            if (d > PULL_RADIUS) continue;
            Vec3d dir = d > 1e-3 ? to.multiply(1 / d) : Vec3d.ZERO;
            Vec3d flat = new Vec3d(dir.x, 0, dir.z);
            Vec3d around = flat.lengthSquared() > 1e-4 ? new Vec3d(-flat.z, 0, flat.x).normalize() : Vec3d.ZERO;
            double k = 1.0 - d / PULL_RADIUS;
            double give = resistance(e) * (e instanceof PlayerEntity ? 0.8 : 1.0);
            Vec3d v = e.getVelocity();

            if (d < CORE_RADIUS) {
                // Caught: held in the middle, whirled round it
                double hold = d / CORE_RADIUS;
                v = v.multiply(0.6)
                        .add(dir.multiply((0.05 + 0.2 * hold) * Math.min(strength, 1.5)))
                        .add(around.multiply(0.14 * give))
                        .add(0, e.hasNoGravity() ? 0 : 0.07, 0);
                e.fallDistance = 0;
                if (e instanceof MobEntity mob) {
                    mob.setYaw(mob.getYaw() + 31f);
                    mob.setHeadYaw(mob.getYaw());
                    mob.setBodyYaw(mob.getYaw());
                }
            } else {
                double a = (0.03 + 0.13 * k * k) * strength * give;
                double swirl = 0.05 * k * strength * give;
                v = v.add(dir.multiply(a)).add(around.multiply(swirl));
                // Close enough and it's lifted off the ground, so it can't dig its heels in
                if (k > 0.35 && !e.hasNoGravity()) v = v.add(0, 0.055 * give * Math.min(strength, 1.5) * MathHelper.clamp((k - 0.35) / 0.3, 0, 1), 0);
                if (k > 0.45) e.fallDistance = 0;
            }
            double max = 1.3;
            if (v.lengthSquared() > max * max) v = v.normalize().multiply(max);
            e.setVelocity(v);
            e.velocityModified = true;
            e.velocityDirty = true;

            if (crush && e instanceof LivingEntity le && d < INNER_RADIUS && canHurt(le, owner)) {
                int due = nextHurt.getOrDefault(le.getUuid(), 0);
                if (age >= due) {
                    nextHurt.put(le.getUuid(), age + HURT_EVERY);
                    hurt(sw, le, d < CORE_RADIUS ? CORE_DAMAGE : INNER_DAMAGE, owner);
                    if (d < CORE_RADIUS) crushEffects(sw, le);
                }
            }
        }
    }

    /** How much of a pull or a push gets through: heavy things (iron golems, wardens) still move, just less. */
    private static double resistance(Entity e) {
        if (!(e instanceof LivingEntity le)) return 1.0;
        return 1.0 - 0.6 * MathHelper.clamp(le.getAttributeValue(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE), 0, 1);
    }

    /** Which things it pulls: creatures, dropped items, XP, flying projectiles, falling blocks and lit TNT. */
    private boolean affects(Entity e, @Nullable PlayerEntity owner) {
        if (e == this || e.isRemoved() || e.isSpectator()) return false;
        if (e instanceof LivingEntity le) return canHurt(le, owner);
        if (e instanceof ProjectileEntity p) {
            return (ownerId == null || p.getOwner() == null || !ownerId.equals(p.getOwner().getUuid())) && p.getVelocity().lengthSquared() > 0.01;
        }
        return e instanceof ItemEntity || e instanceof ExperienceOrbEntity || e instanceof FallingBlockEntity || e instanceof TntEntity;
    }

    private boolean canHurt(LivingEntity e, @Nullable PlayerEntity owner) {
        if (!e.isAlive() || e.isSpectator() || e instanceof ArmorStandEntity) return false;
        if (e instanceof PlayerEntity p && (p.isCreative() || p.isSpectator())) return false;
        if (ownerId != null) {
            if (e.getUuid().equals(ownerId) || e instanceof IllusionEntity) return false;
            if (e instanceof TameableEntity pet && ownerId.equals(pet.getOwnerUuid())) return false;
        }
        return owner == null || !e.isTeammate(owner);
    }

    private void hurt(ServerWorld sw, LivingEntity e, float amount, @Nullable PlayerEntity owner) {
        Vec3d v = e.getVelocity();
        DamageSource src = owner != null ? sw.getDamageSources().indirectMagic(this, owner) : sw.getDamageSources().magic();
        e.timeUntilRegen = 0;
        e.damage(src, amount);
        // No knockback from the hurt itself; gravity does all the moving
        e.setVelocity(v);
        e.velocityModified = true;
    }

    private void crushEffects(ServerWorld sw, LivingEntity e) {
        Vec3d at = e.getBoundingBox().getCenter();
        sw.spawnParticles(ParticleTypes.CRIT, at.x, at.y, at.z, 12, 0.3, 0.4, 0.3, 0.3);
        sw.spawnParticles(ParticleTypes.REVERSE_PORTAL, at.x, at.y, at.z, 10, 0.3, 0.3, 0.3, 0.1);
        sw.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_WARDEN_ATTACK_IMPACT, SoundCategory.PLAYERS, 1.0f, 0.5f);
        sw.playSound(null, at.x, at.y, at.z, SoundEvents.BLOCK_SCULK_BREAK, SoundCategory.PLAYERS, 1.0f, 0.5f);
    }

    private boolean canSee(ServerWorld sw, Vec3d from, Entity e) {
        Vec3d to = e.getBoundingBox().getCenter();
        return sw.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this)).getType() == HitResult.Type.MISS;
    }

    /**
     * Where it forms: {@link #HOLD_DISTANCE} ahead along the caster's aim, pulled in if that's inside a wall, and kept
     * a little up off the floor. Returns the (bottom-centre) position.
     */
    private Vec3d holdPoint(ServerWorld sw, PlayerEntity owner) {
        Vec3d eye = owner.getEyePos();
        Vec3d look = owner.getRotationVec(1f);
        Vec3d want = eye.add(look.multiply(HOLD_DISTANCE));
        BlockHitResult hit = sw.raycast(new RaycastContext(eye, want, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, owner));
        if (hit.getType() != HitResult.Type.MISS) want = hit.getPos().subtract(look.multiply(1.4));
        BlockHitResult floor = sw.raycast(new RaycastContext(want, want.subtract(0, 2.2, 0), RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, owner));
        if (floor.getType() != HitResult.Type.MISS) want = new Vec3d(want.x, floor.getPos().y + 2.2, want.z);
        return want.subtract(0, getHeight() / 2, 0);
    }

    private static void shake(ServerPlayerEntity player, float strength, int ticks) {
        if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player, ShakePayload.ID)) {
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, new ShakePayload(strength, ticks));
        }
    }

    // ---------------------------------------------------------------------
    // Client
    // ---------------------------------------------------------------------

    /** Ticks into the current stage (plus tickDelta), as seen on the client. */
    public float stageTime(float tickDelta) {
        return age - stageSeenAt + tickDelta;
    }

    /** 0..1 through the forming (1 once it's loosed). */
    public float formed(float tickDelta) {
        return stage() == FORMING ? MathHelper.clamp(stageTime(tickDelta) / FORM_TICKS, 0f, 1f) : 1f;
    }

    /**
     * A chunk of ground ripped loose and falling in. It remembers where in the world it tore loose, and spirals in from
     * there toward wherever the hole is now, stretching out thin as it gets close.
     */
    public static final class Debris {
        public final BlockState state;
        private final Vec3d from;
        private final float born, life;
        public final float size;
        public final float spinX, spinY, spinZ;
        private final float swirl;

        Debris(BlockState state, Vec3d from, float born, float life, float size, net.minecraft.util.math.random.Random r) {
            this.state = state;
            this.from = from;
            this.born = born;
            this.life = life;
            this.size = size;
            this.spinX = (r.nextFloat() - 0.5f) * 50f;
            this.spinY = (r.nextFloat() - 0.5f) * 50f;
            this.spinZ = (r.nextFloat() - 0.5f) * 50f;
            this.swirl = 2.2f + r.nextFloat() * 1.6f;
        }

        /** 0 when it tears loose, 1 when it's swallowed. */
        public float progress(float time) {
            return MathHelper.clamp((time - born) / life, 0f, 1f);
        }

        /** Where it is, relative to the hole's centre {@code c}. */
        public Vec3d offset(float time, Vec3d c) {
            float t = progress(time);
            Vec3d rel = from.subtract(c);
            // Slow to lift, then falling faster and faster, swinging round as it closes in
            float e = (float) Math.pow(t, 1.8);
            double turn = e * swirl;
            double r = 1.0 - e;
            double cos = Math.cos(turn), sin = Math.sin(turn);
            double x = (rel.x * cos - rel.z * sin) * r;
            double z = (rel.x * sin + rel.z * cos) * r;
            double y = rel.y * (1.0 - e) + Math.sin(t * Math.PI) * 1.2 * (1 - t);
            return new Vec3d(x, y, z);
        }

        public float spin(float time) {
            return time - born;
        }

        boolean swallowed(float time) {
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
        World w = getWorld();
        Vec3d c = center();
        float now = age;

        // Debris reaching the middle vanishes into it with a puff of grit
        debris.removeIf(d -> {
            if (!d.swallowed(now)) return false;
            if (random.nextInt(3) == 0) {
                w.addParticle(new BlockStateParticleEffect(ParticleTypes.BLOCK, d.state), c.x + random.nextGaussian() * 0.5,
                        c.y + random.nextGaussian() * 0.5, c.z + random.nextGaussian() * 0.5, 0, 0, 0);
            }
            return true;
        });

        switch (stage) {
            case FORMING -> {
                float f = formed(0f);
                tearGround(c, f < 0.3f ? (random.nextFloat() < 0.6f ? 1 : 0) : 1 + Math.round(f * 2), 2.5, 3.5 + 4 * f, now);
                inflow(c, 1 + Math.round(3 * f), 2.0 + 3.0 * f);
            }
            case DRIFTING -> {
                tearGround(c, 3, 2.5, 9.0, now);
                inflow(c, 4, 6.0);
                // Ripples of dust along the ground under it
                BlockPos ground = groundUnder(c.x, c.y, c.z, 6);
                if (ground != null && age % 2 == 0) {
                    BlockState g = w.getBlockState(ground);
                    if (g.getRenderType() == BlockRenderType.MODEL) {
                        for (int i = 0; i < 4; i++) {
                            double a = random.nextDouble() * Math.PI * 2, r = 1.0 + random.nextDouble() * 3.5;
                            double x = c.x + Math.cos(a) * r, z = c.z + Math.sin(a) * r;
                            w.addParticle(new BlockStateParticleEffect(ParticleTypes.BLOCK, g), x, ground.getY() + 1.05, z,
                                    (c.x - x) * 0.08, 0.25, (c.z - z) * 0.08);
                        }
                    }
                }
            }
            case COLLAPSING -> inflow(c, 8, 4.0);
            case BURST -> debris.clear();
            default -> {}
        }
    }

    /** Matter streaming in: portal sparks that converge on the middle. */
    private void inflow(Vec3d c, int count, double reach) {
        World w = getWorld();
        for (int i = 0; i < count; i++) {
            double a = random.nextDouble() * Math.PI * 2;
            double r = reach * (0.5 + random.nextDouble() * 0.5);
            double y = random.nextGaussian() * 0.6;
            // Portal particles start at (pos + velocity) and fall in to pos
            w.addParticle(ParticleTypes.PORTAL, c.x, c.y, c.z, Math.cos(a) * r, y, Math.sin(a) * r);
        }
    }

    /** {@code count} new chunks of the ground torn loose between {@code near} and {@code far} blocks out. */
    private void tearGround(Vec3d c, int count, double near, double far, float now) {
        World w = getWorld();
        for (int i = 0; i < count; i++) {
            double a = random.nextDouble() * Math.PI * 2;
            double r = near + random.nextDouble() * (far - near);
            double x = c.x + Math.cos(a) * r, z = c.z + Math.sin(a) * r;
            BlockPos ground = groundUnder(x, c.y + 1, z, 9);
            if (ground == null) continue;
            BlockState g = w.getBlockState(ground);
            if (!g.isFullCube(w, ground) || g.getRenderType() != BlockRenderType.MODEL) continue;
            Vec3d from = new Vec3d(x, ground.getY() + 1.0, z);
            // The ground cracks where it tears loose
            BlockStateParticleEffect crack = new BlockStateParticleEffect(ParticleTypes.BLOCK, g);
            for (int k = 0; k < 3; k++) {
                w.addParticle(crack, x + random.nextGaussian() * 0.25, from.y + 0.05, z + random.nextGaussian() * 0.25, 0, 0.2, 0);
            }
            float life = 16f + random.nextFloat() * 14f + (float) r * 1.2f;
            debris.add(new Debris(g, from, now, life, 0.22f + random.nextFloat() * 0.3f, random));
        }
    }

    /** The top solid block under (x, z), searching down from y; null if there's none within {@code depth}. */
    @Nullable
    private BlockPos groundUnder(double x, double y, double z, int depth) {
        World w = getWorld();
        BlockPos.Mutable p = new BlockPos.Mutable(MathHelper.floor(x), MathHelper.floor(y), MathHelper.floor(z));
        for (int i = 0; i < depth; i++, p.move(0, -1, 0)) {
            BlockState s = w.getBlockState(p);
            if (!s.getCollisionShape(w, p).isEmpty() && w.getBlockState(p.up()).getCollisionShape(w, p.up()).isEmpty()) {
                return p.toImmutable();
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // Never saved (see shouldSave)
    // ---------------------------------------------------------------------

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {}

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {}
}
