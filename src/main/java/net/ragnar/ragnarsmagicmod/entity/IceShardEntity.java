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
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.spell.IceShardsSpell;

/**
 * A Tome of Ice Shards projectile: fast and nearly flat. Each one lands its own damage even if another shard just
 * hit the same target, and without knocking it out of the way of the next one.
 */
public class IceShardEntity extends ThrownItemEntity {
    private static final int MAX_AGE = 30;

    private int volley;

    // Fabric's builder needs this ctor (type, world)
    public IceShardEntity(EntityType<? extends IceShardEntity> type, World world) {
        super(type, world);
    }

    public IceShardEntity(World world, LivingEntity owner) {
        super(ModEntities.ICE_SHARD, owner, world);
    }

    public IceShardEntity(World world, double x, double y, double z) {
        super(ModEntities.ICE_SHARD, x, y, z, world);
    }

    /** Which cast this shard came from, so three from the same volley can be counted together. */
    public void setVolley(int volley) {
        this.volley = volley;
    }

    public int getVolley() {
        return volley;
    }

    @Override
    protected Item getDefaultItem() {
        return ModItems.ICE_SHARD_ITEM;
    }

    @Override
    protected double getGravity() {
        return 0.008;
    }

    @Override
    public void tick() {
        super.tick();
        if (getWorld().isClient) {
            // A thin trail of snow behind it
            Vec3d v = getVelocity();
            getWorld().addParticle(ParticleTypes.SNOWFLAKE, getX() - v.x * 0.5, getY() - v.y * 0.5, getZ() - v.z * 0.5, 0, 0, 0);
        } else if (age > MAX_AGE) {
            discard();
        }
    }

    @Override
    protected void onEntityHit(EntityHitResult hit) {
        super.onEntityHit(hit);
        if (!(getWorld() instanceof ServerWorld sw) || !(hit.getEntity() instanceof LivingEntity target)) return;
        LivingEntity owner = getOwner() instanceof LivingEntity le ? le : null;

        // Every shard counts: skip the hurt cooldown, and keep the target where it was for the next one
        Vec3d before = target.getVelocity();
        target.timeUntilRegen = 0;
        target.damage(getDamageSources().thrown(this, owner), IceShardsSpell.SHARD_DAMAGE);
        target.setVelocity(before.add(getVelocity().normalize().multiply(0.05)));
        target.velocityModified = true;

        IceShardsSpell.onShardHit(sw, this, target, owner);
        discard();
    }

    @Override
    protected void onBlockHit(BlockHitResult hit) {
        super.onBlockHit(hit);
        if (getWorld() instanceof ServerWorld sw) {
            Vec3d p = hit.getPos();
            sw.playSound(null, p.x, p.y, p.z, SoundEvents.BLOCK_GLASS_HIT, SoundCategory.PLAYERS, 0.6f, 1.4f);
            sw.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.ICE.getDefaultState()),
                    p.x, p.y, p.z, 8, 0.1, 0.1, 0.1, 0.06);
        }
        discard();
    }
}
