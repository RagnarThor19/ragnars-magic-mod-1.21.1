package net.ragnar.ragnarsmagicmod.entity;

import net.minecraft.entity.*;
import net.minecraft.entity.ai.goal.*;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvent;
import net.minecraft.world.LocalDifficulty;
import net.minecraft.world.World;
import net.minecraft.util.math.random.Random;
import net.ragnar.ragnarsmagicmod.sound.ModSoundEvents;

public class SteveEntity extends PathAwareEntity {

    public SteveEntity(EntityType<? extends PathAwareEntity> type, World world) {
        super(type, world);
    }

    // ===== Attributes =====
    public static DefaultAttributeContainer.Builder createAttributes() {
        return MobEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0D)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.30D)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 7.0D)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 32.0D);
    }

    // ===== AI Goals =====
    @Override
    protected void initGoals() {
        this.goalSelector.add(1, new MeleeAttackGoal(this, 1.2D, true));
        this.goalSelector.add(2, new WanderAroundGoal(this, 0.8D));
        this.goalSelector.add(3, new LookAtEntityGoal(this, PlayerEntity.class, 8.0F));
        this.goalSelector.add(4, new LookAroundGoal(this));

        // Attack ALL living entities
        this.targetSelector.add(1, new ActiveTargetGoal<>(
                this,
                LivingEntity.class,
                10,
                true,
                false,
                target -> target != null && target != this
        ));
    }

    // ===== Equipment =====
    @Override
    protected void initEquipment(Random random, LocalDifficulty difficulty) {
        this.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
    }
    private boolean equipped = false;

    @Override
    public void tick() {
        super.tick();

        if (!this.getWorld().isClient && !equipped) {
            this.equipped = true;
            this.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
        }
    }
    @Override
    protected void playHurtSound(DamageSource source) {
        this.playSound(ModSoundEvents.STEVE_OOF, 1.0F, 1.0F);
    }

    @Override
    protected SoundEvent getDeathSound() {
        return ModSoundEvents.STEVE_OOF;
    }


    @Override
    public boolean canPickUpLoot() {
        return false;
    }
}
