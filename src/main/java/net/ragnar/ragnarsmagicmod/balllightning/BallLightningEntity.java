package net.ragnar.ragnarsmagicmod.balllightning;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.mob.CreeperEntity;
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
import net.ragnar.ragnarsmagicmod.sound.ModSoundEvents;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The ball itself. No physics of its own: the server flies it in a straight line at {@link #SPEED}. On the way it
 * arcs out to zap whatever comes within {@link #ZAP_RANGE}, and anything it runs straight into takes
 * {@link #HIT_DAMAGE} as it tears through. It goes off when it reaches a block or {@link #RANGE} blocks: a thunderclap
 * that hurts everything within {@link #BOOM_RADIUS}, hardest in the middle, and never breaks blocks. Creepers it
 * touches come away charged, as if struck by lightning.
 * <p>
 * On the client it eases between synced positions and remembers where it's been for its trail.
 */
public class BallLightningEntity extends Entity {
    public static final double SPEED = 0.84;
    public static final double RANGE = 48.0;
    public static final float HIT_DAMAGE = 24f;
    public static final float ZAP_DAMAGE = 4f;
    public static final double ZAP_RANGE = 4.5;
    public static final float BOOM_RADIUS = 5f;
    public static final float BOOM_DAMAGE = 18f, BOOM_DAMAGE_EDGE = 6f;
    /** Ticks between rounds of zapping, how many it can zap at once, and how soon it zaps the same mob again. */
    private static final int ZAP_EVERY = 4, ZAP_TARGETS = 4, ZAP_AGAIN = 8;
    /** Half the ball's size when it comes to running into things. */
    private static final float HIT_RADIUS = 0.5f;
    private static final int MAX_AGE = 100;
    public static final int TRAIL = 8;

    // Server
    @Nullable private UUID ownerId;
    private Vec3d dir = new Vec3d(0, 0, 1);
    private double travelled;
    private final Set<UUID> struck = new HashSet<>();
    private final Map<UUID, Integer> zappedAt = new HashMap<>();

    // Client
    @Nullable private Vec3d lerpTo;
    public final Deque<Vec3d> trail = new ArrayDeque<>();

    public BallLightningEntity(EntityType<? extends BallLightningEntity> type, World world) {
        super(type, world);
        noClip = true;
        setNoGravity(true);
    }

    BallLightningEntity(ServerWorld world, PlayerEntity owner, Vec3d look) {
        this(BallLightning.BALL, world);
        ownerId = owner.getUuid();
        dir = look.normalize();
        Vec3d eye = owner.getEyePos();
        Vec3d start = eye.add(dir.multiply(1.1)).add(0, -0.15, 0);
        // Point blank into a wall: start right at the wall, so the first tick sets it off there
        BlockHitResult wall = world.raycast(new RaycastContext(eye, start, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, owner));
        if (wall.getType() != HitResult.Type.MISS) start = wall.getPos().subtract(dir.multiply(0.05));
        setPosition(start);
        resetPosition();
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {}

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
            if (lerpTo != null) {
                setPosition(lerpTo);
                lerpTo = null;
            }
            trail.addFirst(getPos());
            while (trail.size() > TRAIL) trail.removeLast();
            return;
        }
        ServerWorld sw = (ServerWorld) getWorld();
        ServerPlayerEntity owner = ownerId != null && sw.getPlayerByUuid(ownerId) instanceof ServerPlayerEntity p ? p : null;

        Vec3d from = getPos();
        Vec3d to = from.add(dir.multiply(SPEED));
        BlockHitResult block = sw.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this));
        boolean hitBlock = block.getType() != HitResult.Type.MISS;
        Vec3d end = hitBlock ? block.getPos() : to;

        tearThrough(sw, from, end, owner);
        travelled += from.distanceTo(end);
        setPosition(hitBlock ? end.subtract(dir.multiply(0.1)) : end);

        if (hitBlock || travelled >= RANGE || age > MAX_AGE) {
            explode(sw, owner);
            return;
        }
        if (age % ZAP_EVERY == 1) zapAround(sw, owner); // from the first tick out, then every few
        flightEffects(sw);
    }

    /** Synced positions land here; the next tick moves there, so rendering eases across the gap. */
    @Override
    public void updateTrackedPositionAndAngles(double x, double y, double z, float yaw, float pitch, int steps) {
        lerpTo = new Vec3d(x, y, z);
    }

    // ---------------------------------------------------------------------
    // Hurting things
    // ---------------------------------------------------------------------

    /** Everything the ball passes straight through this tick takes the big hit, once. */
    private void tearThrough(ServerWorld sw, Vec3d from, Vec3d to, @Nullable PlayerEntity owner) {
        Box swept = new Box(from, to).expand(HIT_RADIUS + 1.0);
        for (Entity e : sw.getOtherEntities(this, swept, e -> canHurt(e, owner) && !struck.contains(e.getUuid()))) {
            Box box = e.getBoundingBox().expand(HIT_RADIUS);
            if (!box.contains(from) && box.raycast(from, to).isEmpty()) continue;
            LivingEntity victim = (LivingEntity) e;
            struck.add(victim.getUuid());
            Vec3d c = victim.getBoundingBox().getCenter();
            hurt(sw, victim, HIT_DAMAGE, owner);
            victim.takeKnockback(0.6, -dir.x, -dir.z);
            BallLightning.arc(sw, getPos(), c, true);

            sw.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, 40, victim.getWidth() * 0.5, victim.getHeight() * 0.4, victim.getWidth() * 0.5, 0.5);
            sw.spawnParticles(ParticleTypes.FLASH, c.x, c.y, c.z, 1, 0, 0, 0, 0);
            sw.playSound(null, c.x, c.y, c.z, ModSoundEvents.ZAP_IMPACT, SoundCategory.PLAYERS, 1.2f, 0.8f);
            sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.PLAYERS, 1.2f, 1.2f);
            if (owner instanceof ServerPlayerEntity sp) shake(sp, 0.2f, 5);
        }
    }

    /** Arcs out to the nearest few things around the ball. */
    private void zapAround(ServerWorld sw, @Nullable PlayerEntity owner) {
        Vec3d c = getPos();
        List<LivingEntity> near = new ArrayList<>(sw.getEntitiesByClass(LivingEntity.class, new Box(c, c).expand(ZAP_RANGE + 1),
                e -> canHurt(e, owner) && e.getBoundingBox().getCenter().squaredDistanceTo(c) <= ZAP_RANGE * ZAP_RANGE
                        && age - zappedAt.getOrDefault(e.getUuid(), -100) >= ZAP_AGAIN && canSee(sw, c, e)));
        near.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(c)));
        for (int i = 0; i < Math.min(ZAP_TARGETS, near.size()); i++) {
            LivingEntity e = near.get(i);
            zappedAt.put(e.getUuid(), age);
            Vec3d at = e.getBoundingBox().getCenter();
            hurt(sw, e, ZAP_DAMAGE, owner);
            BallLightning.arc(sw, c, at, false);
            sw.spawnParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, 12, e.getWidth() * 0.4, e.getHeight() * 0.3, e.getWidth() * 0.4, 0.25);
            sw.playSound(null, at.x, at.y, at.z, ModSoundEvents.ZAP_IMPACT, SoundCategory.PLAYERS, 0.6f, 1.4f + random.nextFloat() * 0.3f);
        }
    }

    /** The thunderclap. Hurts and throws back everything in reach (more the closer it is), but no blocks. */
    private void explode(ServerWorld sw, @Nullable PlayerEntity owner) {
        Vec3d c = getPos();
        for (LivingEntity e : sw.getEntitiesByClass(LivingEntity.class, new Box(c, c).expand(BOOM_RADIUS + 1), e -> canHurt(e, owner))) {
            Vec3d at = e.getBoundingBox().getCenter();
            double d = at.distanceTo(c);
            if (d > BOOM_RADIUS || !canSee(sw, c, e)) continue;
            float k = (float) (1.0 - d / BOOM_RADIUS);
            hurt(sw, e, MathHelper.lerp(k, BOOM_DAMAGE_EDGE, BOOM_DAMAGE), owner);
            Vec3d push = at.subtract(c).multiply(1, 0, 1);
            if (push.lengthSquared() > 1e-4) e.takeKnockback(0.5 + 0.9 * k, -push.x, -push.z);
            e.addVelocity(0, 0.25 + 0.3 * k, 0);
            e.velocityModified = true;
            BallLightning.arc(sw, c, at, true);
        }

        sw.spawnParticles(ParticleTypes.FLASH, c.x, c.y, c.z, 2, 0.2, 0.2, 0.2, 0);
        sw.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, 120, 0.4, 0.4, 0.4, 1.1);
        sw.spawnParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, 30, 0.3, 0.3, 0.3, 0.35);
        sw.spawnParticles(ParticleTypes.LARGE_SMOKE, c.x, c.y, c.z, 14, 0.8, 0.6, 0.8, 0.03);
        sw.spawnParticles(ParticleTypes.EXPLOSION, c.x, c.y, c.z, 1, 0, 0, 0, 0);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.PLAYERS, 2.5f, 1.15f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.PLAYERS, 2.0f, 0.8f);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 1.2f, 1.5f);
        sw.playSound(null, c.x, c.y, c.z, ModSoundEvents.ZAP_IMPACT, SoundCategory.PLAYERS, 1.5f, 0.6f);
        BallLightning.boom(sw, c, BOOM_RADIUS);
        ShakePayload.around(sw, c, 10, 0.55f, 12);
        discard();
    }

    private void hurt(ServerWorld sw, LivingEntity e, float amount, @Nullable PlayerEntity owner) {
        // Hits land a few ticks apart; none of them should bounce off hurt immunity
        e.timeUntilRegen = 0;
        DamageSource source = owner != null ? sw.getDamageSources().playerAttack(owner) : sw.getDamageSources().magic();
        e.damage(source, amount);
        if (e instanceof CreeperEntity creeper && !creeper.shouldRenderOverlay()) {
            LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(sw);
            if (bolt != null) {
                bolt.setPosition(creeper.getPos());
                creeper.onStruckByLightning(sw, bolt); // charged, like the real thing (shouldRenderOverlay is "is charged")
            }
        }
    }

    private boolean canHurt(Entity e, @Nullable PlayerEntity owner) {
        if (!(e instanceof LivingEntity le) || !le.isAlive() || le.isSpectator() || !le.canHit() || e instanceof ArmorStandEntity) return false;
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
    // Effects
    // ---------------------------------------------------------------------

    /** Sparks spitting off it, and its hum and crackle. */
    private void flightEffects(ServerWorld sw) {
        Vec3d c = getPos();
        sw.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, 4, 0.25, 0.25, 0.25, 0.15);
        if (random.nextInt(3) == 0) sw.spawnParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, 1, 0.15, 0.15, 0.15, 0.02);
        if (age % 3 == 0) {
            sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_FIREWORK_ROCKET_TWINKLE, SoundCategory.PLAYERS, 0.5f, 1.6f + random.nextFloat() * 0.3f);
        }
        if (age % 8 == 1) sw.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_BEACON_AMBIENT, SoundCategory.PLAYERS, 1.2f, 1.9f);
    }

    private static void shake(ServerPlayerEntity player, float strength, int ticks) {
        if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player, ShakePayload.ID)) {
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, new ShakePayload(strength, ticks));
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
