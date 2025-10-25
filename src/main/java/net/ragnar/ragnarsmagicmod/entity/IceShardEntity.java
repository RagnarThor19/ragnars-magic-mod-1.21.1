package net.ragnar.ragnarsmagicmod.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.thrown.ThrownItemEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.ModItems;

public class IceShardEntity extends ThrownItemEntity {
    // Fabric’s builder needs this ctor (type, world)
    public IceShardEntity(EntityType<? extends IceShardEntity> type, World world) {
        super(type, world);
    }

    // Convenience ctor for spawning from a player/owner
    public IceShardEntity(World world, LivingEntity owner) {
        super(ModEntities.ICE_SHARD, owner, world);
    }

    // Optional: spawn from coords
    public IceShardEntity(World world, double x, double y, double z) {
        super(ModEntities.ICE_SHARD, x, y, z, world);
    }

    // What item the renderer shows (use snowball for now, swap to your own later)
    @Override
    protected Item getDefaultItem() {
        return ModItems.ICE_SHARD_ITEM;
    }




    @Override
    protected void onEntityHit(net.minecraft.util.hit.EntityHitResult hit) {
        super.onEntityHit(hit);

        if (!(hit.getEntity() instanceof net.minecraft.entity.LivingEntity target)) return;

        // --- damage (2 hearts) ---
        net.minecraft.entity.LivingEntity owner = (this.getOwner() instanceof net.minecraft.entity.LivingEntity le) ? le : null;
        target.damage(this.getDamageSources().thrown(this, owner), 4.0F);

        // --- heavy slow for 2s + freeze for 1s ---
        target.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                net.minecraft.entity.effect.StatusEffects.SLOWNESS, 50, 100, false, true));
        target.setFrozenTicks(target.getFrozenTicks() + 20);

        // --- impact sound ---
        this.getWorld().playSound(
                null,
                target.getBlockPos(),
                net.minecraft.sound.SoundEvents.BLOCK_GLASS_BREAK,    // ice shatter
                net.minecraft.sound.SoundCategory.PLAYERS,
                0.6f,
                1.2f
        );

        // --- ice block shards particles ---
        if (!this.getWorld().isClient) {
            net.minecraft.server.world.ServerWorld sw = (net.minecraft.server.world.ServerWorld) this.getWorld();
            var effect = new net.minecraft.particle.BlockStateParticleEffect(
                    net.minecraft.particle.ParticleTypes.BLOCK,
                    net.minecraft.block.Blocks.ICE.getDefaultState()
            );
            sw.spawnParticles(
                    effect,
                    target.getX(), target.getBodyY(0.5), target.getZ(),
                    12,           // count
                    0.25, 0.25, 0.25, // spread xyz
                    0.08          // speed
            );
        }

        this.discard();
    }

    @Override
    protected void onBlockHit(net.minecraft.util.hit.BlockHitResult hit) {
        super.onBlockHit(hit);

        // sound
        this.getWorld().playSound(
                null,
                hit.getBlockPos(),
                net.minecraft.sound.SoundEvents.BLOCK_GLASS_BREAK,
                net.minecraft.sound.SoundCategory.PLAYERS,
                0.5f,
                1.1f
        );

        // particles at impact point
        if (!this.getWorld().isClient) {
            net.minecraft.server.world.ServerWorld sw = (net.minecraft.server.world.ServerWorld) this.getWorld();
            var effect = new net.minecraft.particle.BlockStateParticleEffect(
                    net.minecraft.particle.ParticleTypes.BLOCK,
                    net.minecraft.block.Blocks.ICE.getDefaultState()
            );
            var p = hit.getPos();
            sw.spawnParticles(effect, p.x, p.y, p.z, 10, 0.2, 0.2, 0.2, 0.08);
        }

        this.discard();
    }


}
