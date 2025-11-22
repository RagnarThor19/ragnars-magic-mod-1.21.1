package net.ragnar.ragnarsmagicmod.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.ai.goal.WanderAroundGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.sound.ModSoundEvents;

import java.util.Objects;

public class SteveEntity extends PathAwareEntity {

    private static final Identifier SPEED_MODIFIER_ID = Identifier.of("ragnarsmagicmod", "steve_haste");

    // This flag ensures we only equip items once, shortly after spawning
    private boolean equipped = false;

    public SteveEntity(EntityType<? extends PathAwareEntity> type, World world) {
        super(type, world);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return MobEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0D)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.30D)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 7.0D)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 32.0D);
    }

    @Override
    protected void initGoals() {
        this.goalSelector.add(1, new MeleeAttackGoal(this, 1.2D, true));
        this.goalSelector.add(2, new WanderAroundGoal(this, 0.8D));
        this.goalSelector.add(3, new LookAtEntityGoal(this, PlayerEntity.class, 8.0F));
        this.goalSelector.add(4, new LookAroundGoal(this));
        this.targetSelector.add(1, new ActiveTargetGoal<>(
                this, LivingEntity.class, 10, true, false, target -> target != null && target != this
        ));
    }

    @Override
    public void tick() {
        super.tick();

        // 1. Server-side: Handle Equipment Logic (The "Tick Thingy")
        if (!this.getWorld().isClient && !equipped) {
            this.equipped = true;

            // Probabilities:
            // 0.00 - 0.15 (15%) : Wooden Pickaxe
            // 0.15 - 0.25 (10%) : Stone Pickaxe
            // 0.25 - 0.85 (60%) : Iron Pickaxe
            // 0.85 - 0.95 (10%) : Diamond Pickaxe
            // 0.95 - 1.00 ( 5%) : Diamond Sword (Special) + Full Armor

            float chance = this.random.nextFloat();

            if (chance < 0.15f) {
                this.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_PICKAXE));
            } else if (chance < 0.25f) {
                this.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_PICKAXE));
            } else if (chance < 0.85f) {
                this.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
            } else if (chance < 0.95f) {
                this.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_PICKAXE));
            } else {
                // 5% Chance: "Super Steve"
                this.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));

                // Full Diamond Armor
                this.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
                this.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
                this.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
                this.equipStack(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));

                // Apply persistent speed boost
                var speedAttr = this.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
                if (speedAttr != null && !speedAttr.hasModifier(SPEED_MODIFIER_ID)) {
                    speedAttr.addPersistentModifier(new EntityAttributeModifier(
                            SPEED_MODIFIER_ID,
                            0.20D, // 20% faster
                            EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE
                    ));
                }
            }
        }

        // 2. Client-side: Enhanced Aura Particles if holding Diamond Sword
        if (this.getWorld().isClient) {
            if (this.getMainHandStack().isOf(Items.DIAMOND_SWORD)) {
                // 50% chance per tick = denser particles
                if (this.random.nextBoolean()) {
                    double rx = (this.random.nextDouble() - 0.5) * 1.2;
                    double ry = this.random.nextDouble() * 2.0; // full body height
                    double rz = (this.random.nextDouble() - 0.5) * 1.2;

                    // Standard magic crit
                    this.getWorld().addParticle(
                            ParticleTypes.ENCHANT,
                            this.getX() + rx,
                            this.getY() + ry,
                            this.getZ() + rz,
                            0.0D, 0.05D, 0.0D // slight upward drift
                    );

                    // Occasional soul fire flame for extra "power" look
                    if (this.random.nextInt(4) == 0) {
                        this.getWorld().addParticle(
                                ParticleTypes.SOUL_FIRE_FLAME,
                                this.getX() + rx * 0.8,
                                this.getY() + ry * 0.8,
                                this.getZ() + rz * 0.8,
                                0.0D, 0.02D, 0.0D
                        );
                    }
                }
            }
        }
    }

    @Override
    protected SoundEvent getAmbientSound() {
        // Custom sound ONLY if holding diamond sword
        if (this.getMainHandStack().isOf(Items.DIAMOND_SWORD)) {
            return ModSoundEvents.STEVE_AURA;
        }
        return null;
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