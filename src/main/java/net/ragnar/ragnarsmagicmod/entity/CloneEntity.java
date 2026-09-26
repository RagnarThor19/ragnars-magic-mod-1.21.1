package net.ragnar.ragnarsmagicmod.entity;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.spell.ClonesSpell;

import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

/**
 * A Tome of Clones copy of its caster. It keeps near them in a loose formation, mirrors what they do
 * (sneaking, jumping, swinging, where they look, what they hold) and fights whatever ClonesSpell points it at.
 * Right-click one of your own to swap places with it.
 */
public class CloneEntity extends PathAwareEntity implements PlayerCopy {
    private static final TrackedData<Optional<UUID>> OWNER =
            DataTracker.registerData(CloneEntity.class, TrackedDataHandlerRegistry.OPTIONAL_UUID);

    /** Where each clone stands around its caster, in degrees from the way they face: back-left, back-right, behind. */
    private static final float[] SLOT_ANGLES = {125f, -125f, 180f};
    private static final double CATCH_UP_DISTANCE = 32.0;

    private int slot;
    private int jumpIn = -1;
    private int swingIn = -1;

    public CloneEntity(EntityType<? extends PathAwareEntity> type, World world) {
        super(type, world);
        for (EquipmentSlot s : EquipmentSlot.values()) setEquipmentDropChance(s, 0f);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return MobEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.3)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 2.0)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 48.0);
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(OWNER, Optional.empty());
    }

    @Override
    protected void initGoals() {
        // No target goals: who to fight is decided by ClonesSpell from the caster's orders
        this.goalSelector.add(0, new SwimGoal(this));
        this.goalSelector.add(1, new MeleeAttackGoal(this, 1.35, true));
        this.goalSelector.add(2, new FollowCasterGoal());
    }

    /** Makes this clone a copy of {@code player}, standing where they stand. */
    public void copyFrom(PlayerEntity player, int slot) {
        this.slot = slot;
        this.dataTracker.set(OWNER, Optional.of(player.getUuid()));
        this.refreshPositionAndAngles(player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch());
        this.setHeadYaw(player.getHeadYaw());
        this.setBodyYaw(player.getBodyYaw());
        mirrorGear(player, false);
        this.setCustomName(player.getName());
    }

    @Override
    public Optional<UUID> getOwnerUuid() {
        return this.dataTracker.get(OWNER);
    }

    public boolean isOwnedBy(Entity entity) {
        return entity != null && getOwnerUuid().map(entity.getUuid()::equals).orElse(false);
    }

    public PlayerEntity getOwner() {
        return getOwnerUuid().map(id -> getWorld().getPlayerByUuid(id)).orElse(null);
    }

    public void mimicJump(int delay) {
        if (jumpIn < 0) jumpIn = delay;
    }

    public void mimicSwing(int delay) {
        if (swingIn < 0) swingIn = delay;
    }

    /** Wears what the caster wears; holds what they hold, or their hardest-hitting hotbar item in a fight. */
    public void mirrorGear(PlayerEntity owner, boolean fighting) {
        for (EquipmentSlot s : EquipmentSlot.values()) {
            if (s.getType() == EquipmentSlot.Type.ANIMAL_ARMOR || s == EquipmentSlot.MAINHAND) continue;
            equipIfChanged(s, owner.getEquippedStack(s));
        }
        equipIfChanged(EquipmentSlot.MAINHAND, fighting ? bestWeapon(owner) : owner.getMainHandStack());
    }

    private void equipIfChanged(EquipmentSlot s, ItemStack stack) {
        if (!ItemStack.areEqual(getEquippedStack(s), stack)) equipStack(s, stack.copy());
    }

    private static ItemStack bestWeapon(PlayerEntity owner) {
        ItemStack best = owner.getMainHandStack();
        double bestDamage = attackDamage(best);
        for (int i = 0; i < 9; i++) {
            ItemStack stack = owner.getInventory().getStack(i);
            double d = attackDamage(stack);
            if (d > bestDamage) {
                best = stack;
                bestDamage = d;
            }
        }
        return best;
    }

    private static double attackDamage(ItemStack stack) {
        double[] sum = {0};
        stack.getOrDefault(DataComponentTypes.ATTRIBUTE_MODIFIERS, AttributeModifiersComponent.DEFAULT)
                .applyModifiers(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
                    if (attribute.equals(EntityAttributes.GENERIC_ATTACK_DAMAGE)
                            && modifier.operation() == EntityAttributeModifier.Operation.ADD_VALUE) {
                        sum[0] += modifier.value();
                    }
                });
        return sum[0];
    }

    /** The spot in the formation around {@code owner} this clone is heading for. */
    private Vec3d slotPos(PlayerEntity owner, float jitterDegrees, double radius) {
        float yaw = owner.getYaw() + SLOT_ANGLES[slot % SLOT_ANGLES.length] + jitterDegrees;
        double a = Math.toRadians(yaw);
        return owner.getPos().add(-Math.sin(a) * radius, 0, Math.cos(a) * radius);
    }

    @Override
    public void tick() {
        super.tick();
        if (!(getWorld() instanceof ServerWorld)) return;
        // Left over from a reload or a lost caster - a clone can't outlive its spell
        if (!ClonesSpell.isLiveClone(this)) {
            discard();
            return;
        }

        if (jumpIn >= 0 && jumpIn-- == 0 && isOnGround()) getJumpControl().setActive();
        if (swingIn >= 0 && swingIn-- == 0) swingHand(Hand.MAIN_HAND);

        PlayerEntity owner = getOwner();
        boolean idle = getTarget() == null && owner != null;

        // Crouch when the caster crouches
        boolean sneak = idle && owner.isSneaking();
        if (sneak != isSneaking()) {
            setSneaking(sneak);
            setPose(sneak ? EntityPose.CROUCHING : EntityPose.STANDING);
        }

        // Stare at whatever the caster is looking at, all of them at once
        if (idle && squaredDistanceTo(owner) < 24 * 24) {
            Vec3d focus = owner.getEyePos().add(owner.getRotationVec(1f).multiply(24.0));
            getLookControl().lookAt(focus.x, focus.y, focus.z, 25f, 25f);
        }
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        // Your own swings (and your other clones') pass right through
        Entity attacker = source.getAttacker();
        if (isOwnedBy(attacker) || (attacker instanceof CloneEntity other && other.getOwnerUuid().equals(getOwnerUuid()))) {
            return false;
        }
        return super.damage(source, amount);
    }

    @Override
    public void onDeath(DamageSource source) {
        super.onDeath(source);
        if (getWorld() instanceof ServerWorld sw) ClonesSpell.shatter(sw, this);
    }

    @Override
    protected ActionResult interactMob(PlayerEntity player, Hand hand) {
        if (!isOwnedBy(player) || player.isSneaking()) return ActionResult.PASS;
        if (player instanceof ServerPlayerEntity sp) ClonesSpell.swapInto(sp, this);
        return ActionResult.success(getWorld().isClient);
    }

    // The caster can walk right through their clones
    @Override
    public void pushAwayFrom(Entity entity) {
        if (!isOwnedBy(entity)) super.pushAwayFrom(entity);
    }

    @Override
    protected void pushAway(Entity entity) {
        if (!isOwnedBy(entity)) super.pushAway(entity);
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.ENTITY_PLAYER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return null; // it shatters instead
    }

    @Override
    public boolean cannotDespawn() {
        return true;
    }

    @Override
    public boolean canPickUpLoot() {
        return false;
    }

    /** Keeps the clone around its caster: walking, sprinting and sneaking with them, and shuffling about when they stand still. */
    private final class FollowCasterGoal extends Goal {
        private int repath;
        private int reroll;
        private float jitter;
        private double radius = 3.0;

        FollowCasterGoal() {
            setControls(EnumSet.of(Control.MOVE));
        }

        @Override
        public boolean canStart() {
            return getTarget() == null && getOwner() != null;
        }

        @Override
        public void stop() {
            getNavigation().stop();
            setSprinting(false);
        }

        @Override
        public boolean shouldRunEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            PlayerEntity owner = getOwner();
            if (owner == null) return;

            // Every few seconds pick a slightly different spot, so they mill about rather than stand in a grid
            if (--reroll <= 0) {
                reroll = 60 + random.nextInt(80);
                jitter = (random.nextFloat() - 0.5f) * 60f;
                radius = 2.4 + random.nextDouble() * 2.0;
            }
            Vec3d spot = slotPos(owner, jitter, radius);

            if (squaredDistanceTo(owner) > CATCH_UP_DISTANCE * CATCH_UP_DISTANCE) {
                ClonesSpell.catchUp((ServerWorld) getWorld(), CloneEntity.this, owner);
                return;
            }

            double d = squaredDistanceTo(spot);
            boolean hurry = owner.isSprinting() || d > 8 * 8;
            setSprinting(hurry && d > 2 * 2 && !owner.isSneaking());
            if (--repath <= 0) {
                repath = 5;
                if (d > 1.2 * 1.2) {
                    double speed = owner.isSneaking() ? 0.55 : hurry ? 1.45 : 1.1;
                    getNavigation().startMovingTo(spot.x, spot.y, spot.z, speed);
                } else {
                    getNavigation().stop();
                    // Settled in: turn to face the way the caster does
                    setBodyYaw(MathHelper.stepUnwrappedAngleTowards(getBodyYaw(), owner.getYaw(), 10f));
                }
            }
        }
    }
}
