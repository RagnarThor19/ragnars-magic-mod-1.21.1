package net.ragnar.ragnarsmagicmod.sleep;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.projectile.thrown.ThrownEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * The Tome of Sleep Darts shot: a small lavender dart that flies fast and nearly flat. It doesn't hurt (that would
 * only wake its target again) - the first living thing it hits just starts nodding off. It breaks on anything else.
 */
public class SleepDartEntity extends ThrownEntity {
    public static final float SPEED = 2.6f;
    private static final int LIFE = 50;

    public SleepDartEntity(EntityType<? extends SleepDartEntity> type, World world) {
        super(type, world);
    }

    public SleepDartEntity(World world, LivingEntity owner) {
        super(Sleep.DART, owner, world);
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
    }

    @Override
    protected double getGravity() {
        return 0.008;
    }

    @Override
    public void tick() {
        super.tick();
        if (getWorld().isClient) {
            // A faint sparkling trail
            Vec3d v = getVelocity();
            for (int i = 0; i < 2; i++) {
                double f = i / 2.0;
                getWorld().addParticle(Sleep.MOTE, getX() - v.x * f, getY() - v.y * f, getZ() - v.z * f, 0, 0, 0);
            }
            if (age % 3 == 0) getWorld().addParticle(ParticleTypes.END_ROD, getX(), getY(), getZ(), 0, 0, 0);
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
        if (getWorld() instanceof ServerWorld sw && hit.getEntity() instanceof LivingEntity target && target != getOwner()) {
            Vec3d at = hit.getPos();
            sw.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_ARROW_HIT, SoundCategory.PLAYERS, 0.7f, 1.8f);
            if (!Sleep.putToSleep(sw, target)) {
                // Too mighty to be put to sleep: the dart just glances off
                sw.playSound(null, at.x, at.y, at.z, SoundEvents.ITEM_SHIELD_BLOCK, SoundCategory.PLAYERS, 0.5f, 1.6f);
            }
        }
    }

    @Override
    protected void onBlockHit(BlockHitResult hit) {
        super.onBlockHit(hit);
        if (getWorld() instanceof ServerWorld sw) {
            Vec3d at = hit.getPos();
            sw.playSound(null, at.x, at.y, at.z, SoundEvents.BLOCK_AMETHYST_CLUSTER_BREAK, SoundCategory.PLAYERS, 0.5f, 1.8f);
        }
    }

    @Override
    protected void onCollision(HitResult hit) {
        super.onCollision(hit);
        if (getWorld() instanceof ServerWorld sw) {
            Vec3d at = hit.getPos();
            sw.spawnParticles(Sleep.DUST, at.x, at.y, at.z, 8, 0.1, 0.1, 0.1, 0.0);
            sw.spawnParticles(ParticleTypes.ENCHANT, at.x, at.y, at.z, 10, 0.15, 0.15, 0.15, 0.4);
            discard();
        }
    }
}
