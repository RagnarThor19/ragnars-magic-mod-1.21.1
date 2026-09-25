package net.ragnar.ragnarsmagicmod.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.spell.IllusionSpell;

import java.util.Optional;
import java.util.UUID;

/**
 * The Tome of Illusion decoy: a still copy of its caster (skin, armour, held items, name) that
 * mobs go after. The first real hit shatters it.
 */
public class IllusionEntity extends MobEntity {
    private static final TrackedData<Optional<UUID>> OWNER =
            DataTracker.registerData(IllusionEntity.class, TrackedDataHandlerRegistry.OPTIONAL_UUID);

    public IllusionEntity(EntityType<? extends MobEntity> type, World world) {
        super(type, world);
        for (EquipmentSlot slot : EquipmentSlot.values()) setEquipmentDropChance(slot, 0f);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return MobEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.0)
                .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 1.0);
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(OWNER, Optional.empty());
    }

    @Override
    protected void initGoals() {
        // Stands its ground, but watches whatever is coming for it - like you would
        this.goalSelector.add(1, new LookAtEntityGoal(this, HostileEntity.class, 12.0f, 1.0f));
        this.goalSelector.add(2, new LookAtEntityGoal(this, PlayerEntity.class, 8.0f));
    }

    /** Dresses the decoy up as {@code player}. */
    public void copyFrom(PlayerEntity player) {
        this.dataTracker.set(OWNER, Optional.of(player.getUuid()));
        this.refreshPositionAndAngles(player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch());
        this.setHeadYaw(player.getHeadYaw());
        this.setBodyYaw(player.getBodyYaw());
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() == EquipmentSlot.Type.ANIMAL_ARMOR) continue;
            this.equipStack(slot, player.getEquippedStack(slot).copy());
        }
        this.setCustomName(player.getName());
        this.setCustomNameVisible(true);
    }

    public Optional<UUID> getOwnerUuid() {
        return this.dataTracker.get(OWNER);
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        if (this.getWorld().isClient || this.isRemoved()) return false;
        // Only something actually attacking it breaks the illusion, not fire, cacti and the like
        if (source.getAttacker() == null && source.getSource() == null) return false;
        if (this.getWorld() instanceof ServerWorld sw) IllusionSpell.onDecoyHit(sw, this, source);
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        // Left over from a reload or a lost caster - an illusion can't outlive its spell
        if (!this.getWorld().isClient && !IllusionSpell.isLiveDecoy(this)) this.discard();
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean cannotDespawn() {
        return true;
    }

    @Override
    public boolean canPickUpLoot() {
        return false;
    }
}
