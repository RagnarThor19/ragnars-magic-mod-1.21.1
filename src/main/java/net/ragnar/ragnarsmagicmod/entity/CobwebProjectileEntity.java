package net.ragnar.ragnarsmagicmod.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.thrown.ThrownItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.spell.CobwebSpell;

/** The Tome of Cobwebs shot: a thrown cobweb that sticks to whatever it hits. */
public class CobwebProjectileEntity extends ThrownItemEntity {
    public CobwebProjectileEntity(EntityType<? extends CobwebProjectileEntity> type, World world) {
        super(type, world);
    }

    public CobwebProjectileEntity(World world, LivingEntity owner) {
        super(ModEntities.COBWEB_PROJECTILE, owner, world);
    }

    @Override
    protected Item getDefaultItem() {
        return Items.COBWEB;
    }

    @Override
    protected double getGravity() {
        return 0.02; // flies mostly straight, droops a little at range
    }

    @Override
    protected void onEntityHit(EntityHitResult hit) {
        super.onEntityHit(hit);
        if (this.getWorld() instanceof ServerWorld sw && hit.getEntity() instanceof LivingEntity target && target != this.getOwner()) {
            CobwebSpell.trap(sw, target);
        }
    }

    @Override
    protected void onBlockHit(BlockHitResult hit) {
        super.onBlockHit(hit);
        if (this.getWorld() instanceof ServerWorld sw) {
            CobwebSpell.stick(sw, hit);
        }
    }

    @Override
    protected void onCollision(net.minecraft.util.hit.HitResult hit) {
        super.onCollision(hit);
        if (!this.getWorld().isClient) this.discard();
    }
}
