package net.ragnar.ragnarsmagicmod.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.thrown.ThrownItemEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
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
    protected void onEntityHit(EntityHitResult hit) {
        super.onEntityHit(hit);

        if (!(hit.getEntity() instanceof LivingEntity target)) return;

        // stacking slow: first hit amp=0, second amp=1, third hit -> freeze 1s
        var cur = target.getStatusEffect(StatusEffects.SLOWNESS);
        int amp = (cur == null) ? -1 : cur.getAmplifier();

        if (amp >= 1) {
            // third hit: clear slow and apply freeze (20 ticks = 1s)
            target.removeStatusEffect(StatusEffects.SLOWNESS);
            target.setFrozenTicks(target.getFrozenTicks() + 20);
        } else {
            // apply/stack slow for 3 seconds
            int nextAmp = amp + 1; // -1->0, 0->1
            target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, nextAmp, false, true));
        }
    }
}
