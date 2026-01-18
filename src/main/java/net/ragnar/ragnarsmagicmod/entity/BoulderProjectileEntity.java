package net.ragnar.ragnarsmagicmod.entity;

import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.thrown.ThrownItemEntity;
import net.minecraft.item.Item;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.ModItems;

import java.util.List;

public class BoulderProjectileEntity extends ThrownItemEntity {

    public BoulderProjectileEntity(EntityType<? extends BoulderProjectileEntity> type, World world) {
        super(type, world);
    }

    public BoulderProjectileEntity(World world, LivingEntity owner) {
        super(ModEntities.BOULDER_PROJECTILE, owner, world);
    }

    @Override
    protected Item getDefaultItem() {
        return ModItems.BOULDER_ITEM;
    }

    // Make it feel heavy
    @Override
    protected double getGravity() {
        return 0.08; // Default is usually 0.03, this is heavier
    }

    @Override
    protected void onCollision(HitResult hitResult) {
        super.onCollision(hitResult);

        if (this.getWorld().isClient) return;

        // AoE Logic
        explode();
        this.discard();
    }

    private void explode() {
        // 1. Visuals & Sound
        this.getWorld().playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.BLOCK_STONE_BREAK, SoundCategory.PLAYERS, 1.0f, 0.5f); // Low pitch for heaviness

        this.getWorld().playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.BLOCK_HEAVY_CORE_BREAK, SoundCategory.PLAYERS, 1.0f, 0.8f);

        if (this.getWorld() instanceof ServerWorld sw) {
            sw.spawnParticles(
                    new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.STONE.getDefaultState()),
                    this.getX(), this.getY() + 0.5, this.getZ(),
                    30, 0.5, 0.5, 0.5, 0.15
            );
            sw.spawnParticles(ParticleTypes.EXPLOSION,
                    this.getX(), this.getY() + 0.5, this.getZ(),
                    1, 0, 0, 0, 0);
        }

        // 2. Damage & Knockback
        double radius = 3.5;
        Box box = this.getBoundingBox().expand(radius);
        List<LivingEntity> targets = this.getWorld().getEntitiesByClass(LivingEntity.class, box, e -> e != this.getOwner());

        for (LivingEntity target : targets) {
            double distSq = this.squaredDistanceTo(target);
            if (distSq < radius * radius) {
                // Damage: 4 hearts = 8.0f
                target.damage(this.getDamageSources().thrown(this, this.getOwner() instanceof LivingEntity le ? le : null), 6.0F);

                // Heavy Knockback away from boulder
                Vec3d dir = target.getPos().subtract(this.getPos()).normalize().multiply(1.5); // 1.5 multiplier is strong
                // Add vertical pop
                target.addVelocity(dir.x, 0.4, dir.z);
                target.velocityModified = true;
            }
        }
    }
}