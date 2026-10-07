package net.ragnar.ragnarsmagicmod.bubbles;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CampfireBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.ai.brain.Brain;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.WardenEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.FluidTags;
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
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The bubble. It drifts at a crawl roughly along the way it was blown, its heading wandering a little more every
 * tick and its path swaying from side to side, slowly sinking, and bouncing softly off whatever it runs into.
 * <p>
 * The first living thing it touches gets sealed inside: the bubble swells to fit and glides over it, then carries it
 * off, rising to hover a few blocks up (lower and slower the heavier its passenger). It pops when its
 * {@link #LIFE} runs out or anything sharp gets at it - see {@link #tick} - and whatever was inside drops.
 * <p>
 * Its position is the bottom middle of its box, like any entity; {@link #center} is the middle of the sphere.
 */
public class BubbleEntity extends Entity {
    /** The size it's blown at, and the most it can stretch to fit something. Anything bigger just pops it. */
    public static final float RADIUS = 1.6f, MAX_RADIUS = 2.4f;
    public static final int LIFE = 15 * 20;
    /** How fast it leaves the staff, and the crawl it settles into. */
    public static final double LAUNCH = 0.17, CRUISE = 0.045;
    /** How much its heading wanders per tick, and how strongly it sways. */
    private static final double WANDER = 0.035, SWAY = 0.03;
    private static final double SINK = 0.008;
    /** How readily it takes up a new velocity: low, so it feels light and lazy. */
    private static final double RESPONSE = 0.06;

    private static final TrackedData<Float> SIZE = DataTracker.registerData(BubbleEntity.class, TrackedDataHandlerRegistry.FLOAT);
    /** Network id of what's inside, or -1. */
    private static final TrackedData<Integer> HELD_ID = DataTracker.registerData(BubbleEntity.class, TrackedDataHandlerRegistry.INTEGER);

    // Server
    @Nullable private UUID ownerId;
    private Vec3d dir = new Vec3d(0, 0, 1);
    private double phaseA, phaseB;
    @Nullable private UUID heldId;
    /** Where what's inside sits relative to the middle; shrinks to nothing as the bubble closes over it. */
    private Vec3d holdOffset = Vec3d.ZERO;
    private int lastBounce = -100, sealedAt;
    /** Each warden nearby: whether its sonic boom was on cooldown last tick. */
    private final Map<Integer, Boolean> wardens = new HashMap<>();

    // Client
    @Nullable private Vec3d lerpTo;
    /** When it last swallowed something or bounced, for the renderer's jiggle. */
    public int kickAt = -100;
    /** The size it's drawn at, easing toward its real size so it swells smoothly. */
    public float shownRadius = RADIUS * 0.35f, prevShownRadius = RADIUS * 0.35f;
    private boolean wasHolding;
    private Vec3d lastMove = Vec3d.ZERO;
    /** Little bubbles shed from its film, drifting off behind it. */
    public final List<Droplet> droplets = new ArrayList<>();

    public static final class Droplet {
        public Vec3d pos, prev, vel;
        public final float size;
        public int age;
        public final int life;

        Droplet(Vec3d pos, Vec3d vel, float size, int life) {
            this.pos = this.prev = pos;
            this.vel = vel;
            this.size = size;
            this.life = life;
        }
    }

    public BubbleEntity(EntityType<? extends BubbleEntity> type, World world) {
        super(type, world);
        setNoGravity(true);
    }

    BubbleEntity(ServerWorld world, PlayerEntity owner, Vec3d look) {
        this(Bubbles.BUBBLE, world);
        ownerId = owner.getUuid();
        dir = look.normalize();
        phaseA = random.nextDouble() * Math.PI * 2;
        phaseB = random.nextDouble() * Math.PI * 2;
        Vec3d eye = owner.getEyePos();
        // Blown out in front of the staff, but not through a wall
        double out = RADIUS + 0.7;
        BlockHitResult wall = world.raycast(new RaycastContext(eye, eye.add(dir.multiply(out + RADIUS)),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, owner));
        if (wall.getType() != HitResult.Type.MISS) out = Math.max(0.6, wall.getPos().distanceTo(eye) - RADIUS);
        Vec3d c = eye.add(dir.multiply(out)).add(0, -0.3, 0);
        setPosition(c.x, c.y - RADIUS, c.z);
        // It's taller than you: lift it clear of the ground it would otherwise start buried in
        for (int i = 0; i < 24 && !world.isSpaceEmpty(this); i++) setPosition(getX(), getY() + 0.1, getZ());
        resetPosition();
        setVelocity(dir.multiply(LAUNCH));
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        builder.add(SIZE, RADIUS);
        builder.add(HELD_ID, -1);
    }

    public float radius() {
        return dataTracker.get(SIZE);
    }

    /** The middle of the sphere. */
    public Vec3d center() {
        return getPos().add(0, radius(), 0);
    }

    public Vec3d lerpedCenter(float tickDelta) {
        return getLerpedPos(tickDelta).add(0, radius(), 0);
    }

    public boolean isHolding() {
        return dataTracker.get(HELD_ID) >= 0;
    }

    @Nullable
    public Entity held() {
        int id = dataTracker.get(HELD_ID);
        return id >= 0 ? getWorld().getEntityById(id) : null;
    }

    @Override
    public EntityDimensions getDimensions(EntityPose pose) {
        float r = dataTracker != null ? radius() : RADIUS;
        return EntityDimensions.fixed(r * 2, r * 2);
    }

    @Override
    public void onTrackedDataSet(TrackedData<?> data) {
        super.onTrackedDataSet(data);
        if (SIZE.equals(data)) calculateDimensions();
    }

    @Override
    public boolean shouldSave() {
        return false;
    }

    @Override
    public boolean shouldRender(double distance) {
        return distance < 96 * 96;
    }

    // Arrows and the like fly on through (popping it, see popOnSharpThings) rather than stopping dead in the film
    @Override
    public boolean canHit() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean isCollidable() {
        return false;
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        // Only explosions and the like get here (nothing can hit it directly)
        if (!getWorld().isClient && !isRemoved()) burst();
        return false;
    }

    @Override
    public void onStruckByLightning(ServerWorld world, LightningEntity lightning) {
        burst();
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
            // Jiggles when it swallows something, or bounces (its motion turns right around)
            Vec3d moving = getPos().subtract(prevX, prevY, prevZ);
            if (isHolding() != wasHolding || moving.dotProduct(lastMove) < -1e-4) kickAt = age;
            wasHolding = isHolding();
            lastMove = moving;
            prevShownRadius = shownRadius;
            shownRadius += (radius() - shownRadius) * 0.35f;
            shedDroplets();
            return;
        }
        ServerWorld sw = (ServerWorld) getWorld();
        LivingEntity held = heldEntity(sw);
        if (heldId != null && held == null) { // died, or left the world some other way
            burst();
            return;
        }
        if (age >= LIFE) {
            burst();
            return;
        }

        drift(sw, held);
        if (popOnSharpThings(sw, held)) return;
        if (held == null) held = trySeal(sw);
        if (isRemoved()) return;
        if (held != null) hold(held);
        ambience(sw, held);
    }

    /** Synced positions land here; the next tick moves there, so rendering eases across the gap. */
    @Override
    public void updateTrackedPositionAndAngles(double x, double y, double z, float yaw, float pitch, int steps) {
        lerpTo = new Vec3d(x, y, z);
    }

    @Nullable
    private LivingEntity heldEntity(ServerWorld sw) {
        if (heldId == null) return null;
        return sw.getEntity(heldId) instanceof LivingEntity le && le.isAlive() && !le.isRemoved() ? le : null;
    }

    // ---------------------------------------------------------------------
    // Drifting
    // ---------------------------------------------------------------------

    private void drift(ServerWorld sw, @Nullable LivingEntity held) {
        // Its heading wanders off course, a little more all the time, and won't stay pointed at the sky or floor
        dir = dir.add(random.nextGaussian() * WANDER, random.nextGaussian() * WANDER * 0.6, random.nextGaussian() * WANDER);
        dir = new Vec3d(dir.x, dir.y * 0.99, dir.z).normalize();

        double weight = held != null ? lightness(held) : 1.0;
        double speed = (CRUISE + (LAUNCH - CRUISE) * Math.exp(-age / 22.0)) * weight;
        Vec3d want = dir.multiply(speed);

        // Swaying from side to side and bobbing up and down, like it's caught in a breeze
        Vec3d side = dir.crossProduct(new Vec3d(0, 1, 0));
        side = side.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : side.normalize();
        want = want.add(side.multiply(Math.sin(age * 0.08 + phaseA) * SWAY))
                .add(0, Math.sin(age * 0.11 + phaseB) * SWAY * 0.6, 0);

        if (held != null) {
            // Lifts its passenger to hover a few blocks up: lower the heavier it is
            double hover = 1.0 + 2.5 * weight;
            want = want.add(0, groundBelow(sw) < hover ? 0.03 + 0.04 * weight : -0.004, 0);
        } else {
            want = want.add(0, -SINK, 0);
        }
        if (getWorld().getFluidState(BlockPos.ofFloored(center())).isIn(FluidTags.WATER)) want = want.add(0, 0.09, 0);

        // Nudged away by its caster, so it never swallows them
        PlayerEntity owner = ownerId != null ? sw.getPlayerByUuid(ownerId) : null;
        if (owner != null) {
            Vec3d away = center().subtract(owner.getBoundingBox().getCenter());
            double d = away.length();
            if (d < radius() + 0.6 && d > 1e-4) {
                want = want.add(away.multiply(0.05 / d));
                dir = dir.add(away.multiply(0.15 / d)).normalize();
            }
        }

        Vec3d vel = getVelocity();
        vel = vel.add(want.subtract(vel).multiply(RESPONSE));
        // Somehow wedged in blocks (a door shut on it, sand fell in): squeeze up and out
        if (!sw.isSpaceEmpty(this)) {
            setPosition(getX(), getY() + 0.05, getZ());
            vel = new Vec3d(vel.x, Math.max(vel.y, 0), vel.z);
        }
        Vec3d before = getPos();
        move(MovementType.SELF, vel);
        Vec3d moved = getPos().subtract(before);

        // Bounces softly off whatever it bumps
        boolean bounced = false;
        if (horizontalCollision) {
            if (Math.abs(moved.x - vel.x) > 1e-4) {
                vel = new Vec3d(-vel.x * 0.6, vel.y, vel.z);
                dir = new Vec3d(-dir.x, dir.y, dir.z);
                bounced = true;
            }
            if (Math.abs(moved.z - vel.z) > 1e-4) {
                vel = new Vec3d(vel.x, vel.y, -vel.z * 0.6);
                dir = new Vec3d(dir.x, dir.y, -dir.z);
                bounced = true;
            }
        }
        if (verticalCollision) {
            vel = new Vec3d(vel.x, -vel.y * 0.55 + (vel.y < 0 ? 0.02 : 0), vel.z);
            dir = new Vec3d(dir.x, -dir.y * 0.5, dir.z).normalize();
            bounced = true;
        }
        setVelocity(vel);
        if (bounced && age - lastBounce > 8 && vel.lengthSquared() > 0.0004) {
            lastBounce = age;
            Vec3d c = center();
            sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_SLIME_JUMP_SMALL, SoundCategory.PLAYERS, 0.35f, 1.6f + random.nextFloat() * 0.3f);
        }
    }

    /** How far down the ground is from the bottom of the bubble (8 if further). */
    private double groundBelow(ServerWorld sw) {
        Vec3d bottom = getPos();
        BlockHitResult hit = sw.raycast(new RaycastContext(bottom, bottom.add(0, -8, 0),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.ANY, this));
        return hit.getType() == HitResult.Type.MISS ? 8 : bottom.y - hit.getPos().y;
    }

    /** 1 for nothing at all, down to about a third for a ravager: how much it slows down carrying {@code e}. */
    private static double lightness(LivingEntity e) {
        double mass = e.getWidth() * e.getWidth() * e.getHeight();
        return 1.0 / (1.0 + mass * 0.25);
    }

    // ---------------------------------------------------------------------
    // Popping
    // ---------------------------------------------------------------------

    /** Arrows and every other shot, a warden's sonic boom, cactus, thorns and fire: all pop it. True if it popped. */
    private boolean popOnSharpThings(ServerWorld sw, @Nullable LivingEntity held) {
        Vec3d c = center();
        float r = radius();
        Box box = getBoundingBox();

        if (!sw.getEntitiesByClass(ProjectileEntity.class, box.expand(0.5), p -> touches(c, r, p.getBoundingBox())).isEmpty()) {
            burst();
            return true;
        }

        for (WardenEntity w : sw.getEntitiesByClass(WardenEntity.class, box.expand(24), w -> true)) {
            Brain<WardenEntity> brain = w.getBrain();
            boolean booming = brain.hasMemoryModule(MemoryModuleType.SONIC_BOOM_SOUND_COOLDOWN);
            boolean was = wardens.getOrDefault(w.getId(), true); // one already mid-boom when it showed up doesn't count
            wardens.put(w.getId(), booming);
            if (!booming || was) continue;
            // It just went off: from the warden to whatever it's after
            LivingEntity target = brain.getOptionalRegisteredMemory(MemoryModuleType.ATTACK_TARGET).orElse(null);
            boolean inside = w == held;
            boolean through = target != null && segmentHits(w.getPos().add(0, 1.6, 0), target.getBoundingBox().getCenter(), c, r);
            if (inside || through) {
                burst();
                return true;
            }
        }

        // Sharp or hot blocks it brushes against
        Box skin = box.expand(0.08);
        for (BlockPos pos : BlockPos.iterate(BlockPos.ofFloored(skin.minX, skin.minY, skin.minZ), BlockPos.ofFloored(skin.maxX, skin.maxY, skin.maxZ))) {
            BlockState s = sw.getBlockState(pos);
            if (!isSharp(s)) continue;
            if (touches(c, r + 0.08f, new Box(pos))) {
                burst();
                return true;
            }
        }

        // A player sealed inside can poke their way out
        if (held instanceof ServerPlayerEntity sp && sp.handSwinging && age - sealedAt > 10) {
            burst();
            return true;
        }
        return false;
    }

    private static boolean isSharp(BlockState s) {
        return s.isOf(Blocks.CACTUS) || s.isOf(Blocks.SWEET_BERRY_BUSH) || s.isOf(Blocks.POINTED_DRIPSTONE)
                || s.isOf(Blocks.FIRE) || s.isOf(Blocks.SOUL_FIRE) || s.isOf(Blocks.LAVA) || s.isOf(Blocks.MAGMA_BLOCK)
                || ((s.isOf(Blocks.CAMPFIRE) || s.isOf(Blocks.SOUL_CAMPFIRE)) && s.get(CampfireBlock.LIT));
    }

    /** True if the sphere at {@code c} of radius {@code r} touches {@code box}. */
    private static boolean touches(Vec3d c, double r, Box box) {
        double x = MathHelper.clamp(c.x, box.minX, box.maxX);
        double y = MathHelper.clamp(c.y, box.minY, box.maxY);
        double z = MathHelper.clamp(c.z, box.minZ, box.maxZ);
        return c.squaredDistanceTo(x, y, z) <= r * r;
    }

    /** True if the line from {@code a} to {@code b} passes within {@code r} of {@code c}. */
    static boolean segmentHits(Vec3d a, Vec3d b, Vec3d c, double r) {
        Vec3d ab = b.subtract(a);
        double len2 = ab.lengthSquared();
        double t = len2 < 1e-8 ? 0 : MathHelper.clamp(c.subtract(a).dotProduct(ab) / len2, 0, 1);
        return a.add(ab.multiply(t)).squaredDistanceTo(c) <= r * r;
    }

    /** Pops: drops whatever was inside where it is, with a splash. */
    public void burst() {
        if (isRemoved() || !(getWorld() instanceof ServerWorld sw)) return;
        LivingEntity held = heldEntity(sw);
        if (held != null) {
            held.setVelocity(held.getVelocity().multiply(0.2, 0, 0.2));
            held.velocityModified = true;
            held.fallDistance = 0;
        }
        release();

        Vec3d c = center();
        float r = radius();
        for (int i = 0; i < 48; i++) {
            Vec3d d = new Vec3d(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()).normalize();
            Vec3d p = c.add(d.multiply(r));
            sw.spawnParticles(ParticleTypes.SPLASH, p.x, p.y, p.z, 2, 0.05, 0.05, 0.05, 0.1);
            if (i % 2 == 0) sw.spawnParticles(ParticleTypes.BUBBLE_POP, p.x, p.y, p.z, 1, 0, 0, 0, 0.02);
            if (i % 4 == 0) sw.spawnParticles(ParticleTypes.FALLING_WATER, p.x, p.y, p.z, 1, 0.1, 0.1, 0.1, 0);
        }
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_BUBBLE_COLUMN_BUBBLE_POP, SoundCategory.PLAYERS, 3.0f, 0.55f + random.nextFloat() * 0.1f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_BUBBLE_COLUMN_BUBBLE_POP, SoundCategory.PLAYERS, 2.0f, 1.2f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_PLAYER_SPLASH, SoundCategory.PLAYERS, 0.5f, 1.7f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ITEM_BUCKET_EMPTY, SoundCategory.PLAYERS, 0.5f, 1.8f);
        Bubbles.pop(sw, c, r);
        discard();
    }

    private void release() {
        if (heldId != null && Bubbles.HELD.get(heldId) == this) Bubbles.HELD.remove(heldId);
        heldId = null;
        dataTracker.set(HELD_ID, -1);
    }

    @Override
    public void remove(RemovalReason reason) {
        if (!getWorld().isClient) release();
        super.remove(reason);
    }

    // ---------------------------------------------------------------------
    // Sealing things in
    // ---------------------------------------------------------------------

    /** The first thing it touches gets sealed in, or pops it if it's too big to fit. */
    @Nullable
    private LivingEntity trySeal(ServerWorld sw) {
        Vec3d c = center();
        float r = radius();
        LivingEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (LivingEntity e : sw.getEntitiesByClass(LivingEntity.class, getBoundingBox().expand(0.5), this::canSeal)) {
            if (!touches(c, r, e.getBoundingBox())) continue;
            double d = e.getBoundingBox().getCenter().squaredDistanceTo(c);
            if (d < best) {
                best = d;
                nearest = e;
            }
        }
        if (nearest == null) return null;

        float fit = fitRadius(nearest);
        if (fit > MAX_RADIUS) {
            burst();
            return null;
        }
        nearest.stopRiding();
        nearest.removeAllPassengers();
        heldId = nearest.getUuid();
        sealedAt = age;
        Bubbles.HELD.put(heldId, this);
        dataTracker.set(HELD_ID, nearest.getId());
        holdOffset = nearest.getBoundingBox().getCenter().subtract(c);
        if (fit > r) {
            dataTracker.set(SIZE, fit);
            setPosition(c.x, c.y - fit, c.z); // swell about its middle
            // ...but not down into the floor
            for (int i = 0; i < 12 && !sw.isSpaceEmpty(this); i++) setPosition(getX(), getY() + 0.1, getZ());
        }
        // The heading turns a little toward where its passenger was, as if knocked by it
        dir = dir.add(holdOffset.normalize().multiply(0.3)).normalize();
        setVelocity(getVelocity().multiply(0.5));

        Vec3d at = nearest.getBoundingBox().getCenter();
        sw.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_SLIME_SQUISH_SMALL, SoundCategory.PLAYERS, 1.2f, 0.6f);
        sw.playSound(null, at.x, at.y, at.z, SoundEvents.BLOCK_BUBBLE_COLUMN_UPWARDS_INSIDE, SoundCategory.PLAYERS, 1.5f, 1.3f);
        sw.playSound(null, at.x, at.y, at.z, SoundEvents.ITEM_BUCKET_FILL, SoundCategory.PLAYERS, 0.6f, 1.6f);
        sw.spawnParticles(ParticleTypes.SPLASH, at.x, at.y, at.z, 30, nearest.getWidth() * 0.6, nearest.getHeight() * 0.4, nearest.getWidth() * 0.6, 0.1);
        return nearest;
    }

    private boolean canSeal(LivingEntity e) {
        if (!e.isAlive() || e.isSpectator() || e instanceof ArmorStandEntity || e instanceof EnderDragonEntity) return false;
        if (e instanceof IllusionEntity || Bubbles.bubbleOf(e) != null) return false;
        if (e instanceof PlayerEntity p && p.getAbilities().creativeMode) return false;
        if (ownerId != null) {
            if (e.getUuid().equals(ownerId)) return false;
            if (e instanceof TameableEntity pet && ownerId.equals(pet.getOwnerUuid())) return false;
        }
        return true;
    }

    /** The smallest bubble {@code e} fits inside, corner to corner. */
    public static float fitRadius(Entity e) {
        float w = e.getWidth(), h = e.getHeight();
        return Math.max(RADIUS, (float) Math.sqrt(w * w * 2 + h * h) * 0.5f + 0.15f);
    }

    /** Keeps what's inside floating in the middle, turning slowly. */
    private void hold(LivingEntity held) {
        holdOffset = holdOffset.multiply(0.75);
        Vec3d at = center().add(holdOffset);
        Vec3d feet = at.subtract(0, held.getHeight() / 2, 0);
        held.fallDistance = 0;
        held.extinguish(); // it's a ball of soapy water: no burning in here, sun or not
        if (held instanceof ServerPlayerEntity sp) {
            // Players move themselves, so steer them, and snap them back if they somehow get out
            if (sp.getPos().squaredDistanceTo(feet) > 0.9 * 0.9) {
                sp.networkHandler.requestTeleport(feet.x, feet.y, feet.z, sp.getYaw(), sp.getPitch());
            } else {
                sp.setVelocity(feet.subtract(sp.getPos()).multiply(0.4).add(getVelocity()));
                sp.velocityModified = true;
            }
            return;
        }
        // An enderman teleporting out just finds itself back inside
        held.setPosition(feet);
        held.setVelocity(Vec3d.ZERO);
        held.setOnGround(false);
        float spin = held.getYaw() + 2.5f;
        held.setYaw(spin);
        held.setBodyYaw(spin);
        if (held instanceof MobEntity mob) mob.getNavigation().stop();
    }

    // ---------------------------------------------------------------------
    // Effects
    // ---------------------------------------------------------------------

    private void ambience(ServerWorld sw, @Nullable LivingEntity held) {
        Vec3d c = center();
        if (age % 30 == 7) {
            sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT, SoundCategory.PLAYERS, 0.5f, 1.4f + random.nextFloat() * 0.3f);
        }
        // Straining near the end: it creaks and wobbles
        if (age > LIFE - 50 && age % 10 == 0) {
            sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_SLIME_SQUISH_SMALL, SoundCategory.PLAYERS, 0.4f, 1.8f);
        }
        if (held != null && age % 14 == 3) {
            sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_AMBIENT, SoundCategory.PLAYERS, 0.25f, 1.6f);
        }
    }

    /** Client: now and then a tiny bubble comes off the film and drifts away behind it. */
    private void shedDroplets() {
        droplets.removeIf(d -> {
            d.prev = d.pos;
            d.pos = d.pos.add(d.vel);
            d.vel = d.vel.multiply(0.92).add(0, 0.004, 0);
            if (++d.age < d.life) return false;
            getWorld().addParticle(ParticleTypes.BUBBLE_POP, d.pos.x, d.pos.y, d.pos.z, 0, 0, 0);
            return true;
        });
        if (random.nextInt(4) != 0 || droplets.size() > 24) return;
        Vec3d c = center();
        Vec3d moving = getPos().subtract(prevX, prevY, prevZ);
        Vec3d back = moving.lengthSquared() > 1e-6 ? moving.normalize().multiply(-1) : Vec3d.ZERO;
        Vec3d out = new Vec3d(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()).normalize().add(back.multiply(0.9)).normalize();
        float r = radius();
        droplets.add(new Droplet(c.add(out.multiply(r * 1.02)), out.multiply(0.02 + random.nextDouble() * 0.03),
                0.06f + random.nextFloat() * 0.12f, 18 + random.nextInt(24)));
    }

    // ---------------------------------------------------------------------
    // Never saved (see shouldSave)
    // ---------------------------------------------------------------------

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {}

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {}
}
