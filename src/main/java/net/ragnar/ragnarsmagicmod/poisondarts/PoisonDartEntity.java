package net.ragnar.ragnarsmagicmod.poisondarts;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.projectile.thrown.ThrownEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * The poison dart: a little green dart that flies like a blowpipe shot - very fast and dead straight at first, only
 * starting to dip toward the end of its flight. The first living thing it hits takes {@link #DAMAGE} and is poisoned
 * ({@link #POISON_TICKS}, Poison I). If it hits a block it sticks there, quivering, for a couple of seconds.
 */
public class PoisonDartEntity extends ThrownEntity {
    public static final float SPEED = 3.4f;
    public static final float DAMAGE = 10f;
    public static final int POISON_TICKS = 6 * 20;
    private static final int LIFE = 60;
    /** How long it stays stuck in a block. */
    private static final int STUCK_TICKS = 40;
    /** Flies flat this long before it starts to drop. */
    private static final int FLAT_TICKS = 8;

    private static final TrackedData<Boolean> STUCK = DataTracker.registerData(PoisonDartEntity.class, TrackedDataHandlerRegistry.BOOLEAN);

    private int stuckAt = -1;

    public PoisonDartEntity(EntityType<? extends PoisonDartEntity> type, World world) {
        super(type, world);
    }

    public PoisonDartEntity(World world, LivingEntity owner) {
        super(PoisonDarts.DART, owner, world);
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        builder.add(STUCK, false);
    }

    public boolean isStuck() {
        return dataTracker.get(STUCK);
    }

    @Override
    protected double getGravity() {
        if (isStuck()) return 0;
        // A blowpipe dart holds its line, then falls off
        return age < FLAT_TICKS ? 0.002 : 0.03;
    }

    @Override
    public void tick() {
        if (isStuck()) {
            // Stuck in a block: it stays put and fades after a moment
            if (!getWorld().isClient && age - stuckAt > STUCK_TICKS) discard();
            return;
        }
        super.tick();
        if (getWorld().isClient) {
            // A thin trail of green motes, and now and then a drip of poison
            Vec3d v = getVelocity();
            for (int i = 0; i < 3; i++) {
                double f = i / 3.0;
                getWorld().addParticle(PoisonDarts.MOTE, getX() - v.x * f, getY() - v.y * f, getZ() - v.z * f, 0, 0, 0);
            }
            if (age % 4 == 0) getWorld().addParticle(ParticleTypes.FALLING_SPORE_BLOSSOM, getX(), getY(), getZ(), 0, 0, 0);
        } else if (age > LIFE) {
            discard();
        }
    }

    @Override
    protected boolean canHit(Entity entity) {
        return super.canHit(entity) && entity instanceof LivingEntity;
    }

    @Override
    protected void onEntityHit(EntityHitResult hit) {
        super.onEntityHit(hit);
        if (!(getWorld() instanceof ServerWorld sw) || !(hit.getEntity() instanceof LivingEntity target) || target == getOwner()) return;
        Entity owner = getOwner();
        Vec3d at = hit.getPos();
        target.damage(sw.getDamageSources().thrown(this, owner), DAMAGE);
        target.addStatusEffect(new StatusEffectInstance(StatusEffects.POISON, POISON_TICKS, 0), owner);

        // A wet thwack, a squelch of poison, and the caster hears the ding of a hit like with a bow
        sw.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_ARROW_HIT, SoundCategory.PLAYERS, 0.9f, 1.5f);
        sw.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_SLIME_SQUISH_SMALL, SoundCategory.PLAYERS, 0.8f, 1.3f);
        if (owner instanceof ServerPlayerEntity sp) {
            sp.playSoundToPlayer(SoundEvents.ENTITY_ARROW_HIT_PLAYER, SoundCategory.PLAYERS, 0.45f, 1.3f);
        }
        sw.spawnParticles(PoisonDarts.DUST, at.x, at.y, at.z, 12, 0.15, 0.15, 0.15, 0.0);
        sw.spawnParticles(ParticleTypes.ITEM_SLIME, at.x, at.y, at.z, 6, 0.1, 0.1, 0.1, 0.05);
        discard();
    }

    @Override
    protected void onBlockHit(BlockHitResult hit) {
        super.onBlockHit(hit);
        if (!(getWorld() instanceof ServerWorld sw)) return;
        Vec3d at = hit.getPos();
        sw.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_ARROW_HIT, SoundCategory.PLAYERS, 0.7f, 1.9f);
        sw.spawnParticles(PoisonDarts.MOTE, at.x, at.y, at.z, 6, 0.06, 0.06, 0.06, 0.0);
        // Sink the tip a little way in and stay there
        Vec3d dir = getVelocity().normalize();
        Vec3d stuck = at.subtract(dir.multiply(0.12));
        setVelocity(Vec3d.ZERO);
        setPosition(stuck.x, stuck.y, stuck.z);
        velocityModified = true;
        dataTracker.set(STUCK, true);
        stuckAt = age;
    }
}
