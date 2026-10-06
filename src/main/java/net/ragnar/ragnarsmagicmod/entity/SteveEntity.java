package net.ragnar.ragnarsmagicmod.entity;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.goal.WanderAroundFarGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.sound.ModSoundEvents;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.EnumSet;
import java.util.UUID;

/**
 * A Steve from the Tome of Steve: one of a squad of five called down to fight for whoever summoned them. Nobody
 * controls them. They go after everything alive except their summoner, the summoner's pets and their own squad -
 * first whatever the summoner is fighting or being hurt by, then anything else in sight - and with nothing to fight
 * they roam, keeping their distance from the summoner (see KeepDistanceGoal). The summoner can't hurt them by
 * accident. Each comes kitted out for his role (see {@link Role}), glows a little about the eyes (see
 * SteveRenderer), and after {@link #LIFETIME} ticks he's gone again in a puff. They're never saved: a squad doesn't
 * outlive the session.
 */
public class SteveEntity extends PathAwareEntity {
    /** How long a Steve sticks around: 30 seconds. */
    public static final int LIFETIME = 20 * 30;
    private static final Identifier LEADER_SPEED = Identifier.of("ragnarsmagicmod", "steve_leader_haste");
    private static final Identifier LEADER_HEALTH = Identifier.of("ragnarsmagicmod", "steve_leader_health");
    private static final DustParticleEffect SPARK = new DustParticleEffect(new Vector3f(0.35f, 0.9f, 1f), 0.8f);

    /** What each member of the squad brings. */
    public enum Role {
        /** Diamond sword, shining diamond armour, quicker and tougher than the rest. */
        LEADER,
        /** Iron sword, iron helmet and chestplate. */
        SWORDSMAN,
        /** Iron axe, chainmail: hits hardest, swings slowest. */
        AXEMAN,
        /** The classic: a diamond pickaxe and nothing else. */
        MINER,
        /** Iron pickaxe and an iron helmet. */
        DIGGER
    }

    @Nullable private UUID ownerId;
    private int life = LIFETIME;

    public SteveEntity(EntityType<? extends PathAwareEntity> type, World world) {
        super(type, world);
        for (EquipmentSlot slot : EquipmentSlot.values()) setEquipmentDropChance(slot, 0f);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return MobEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0D)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.30D)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 1.0D) // like a player: the weapon does the work
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 24.0D);
    }

    // ---------------------------------------------------------------------
    // Squad setup
    // ---------------------------------------------------------------------

    /** Ties this Steve to {@code owner} and kits him out for {@code role}. */
    public void enlist(PlayerEntity owner, Role role) {
        this.ownerId = owner.getUuid();
        switch (role) {
            case LEADER -> {
                equip(EquipmentSlot.MAINHAND, Items.DIAMOND_SWORD, true);
                equip(EquipmentSlot.HEAD, Items.DIAMOND_HELMET, true);
                equip(EquipmentSlot.CHEST, Items.DIAMOND_CHESTPLATE, true);
                equip(EquipmentSlot.LEGS, Items.DIAMOND_LEGGINGS, true);
                equip(EquipmentSlot.FEET, Items.DIAMOND_BOOTS, true);
                var speed = getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
                if (speed != null) speed.addPersistentModifier(new EntityAttributeModifier(LEADER_SPEED, 0.2, EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE));
                var health = getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
                if (health != null) health.addPersistentModifier(new EntityAttributeModifier(LEADER_HEALTH, 10, EntityAttributeModifier.Operation.ADD_VALUE));
                setHealth(getMaxHealth());
            }
            case SWORDSMAN -> {
                equip(EquipmentSlot.MAINHAND, Items.IRON_SWORD, false);
                equip(EquipmentSlot.HEAD, Items.IRON_HELMET, false);
                equip(EquipmentSlot.CHEST, Items.IRON_CHESTPLATE, false);
            }
            case AXEMAN -> {
                equip(EquipmentSlot.MAINHAND, Items.IRON_AXE, false);
                equip(EquipmentSlot.CHEST, Items.CHAINMAIL_CHESTPLATE, false);
                equip(EquipmentSlot.LEGS, Items.CHAINMAIL_LEGGINGS, false);
            }
            case MINER -> equip(EquipmentSlot.MAINHAND, Items.DIAMOND_PICKAXE, false);
            case DIGGER -> {
                equip(EquipmentSlot.MAINHAND, Items.IRON_PICKAXE, false);
                equip(EquipmentSlot.HEAD, Items.IRON_HELMET, false);
            }
        }
    }

    private void equip(EquipmentSlot slot, Item item, boolean shine) {
        ItemStack stack = new ItemStack(item);
        if (shine) stack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        equipStack(slot, stack);
    }

    public boolean isLeader() {
        return getMainHandStack().isOf(Items.DIAMOND_SWORD);
    }

    @Nullable
    public UUID getOwnerId() {
        return ownerId;
    }

    @Nullable
    public PlayerEntity getOwner() {
        return ownerId != null ? getWorld().getPlayerByUuid(ownerId) : null;
    }

    /** True for {@code e} and anything on its side: the summoner, their pets, and their Steves. */
    public boolean isFriend(@Nullable Entity e) {
        if (e == null || ownerId == null) return false;
        if (e.getUuid().equals(ownerId)) return true;
        if (e instanceof SteveEntity other && ownerId.equals(other.ownerId)) return true;
        return e instanceof TameableEntity pet && ownerId.equals(pet.getOwnerUuid());
    }

    /** Fair game: alive, hittable, and not a friend. */
    private boolean isFoe(@Nullable LivingEntity e) {
        if (e == null || e == this || !e.isAlive() || isFriend(e) || e instanceof ArmorStandEntity) return false;
        return !(e instanceof PlayerEntity p) || !p.isCreative() && !p.isSpectator();
    }

    // ---------------------------------------------------------------------
    // Brains
    // ---------------------------------------------------------------------

    @Override
    protected void initGoals() {
        this.goalSelector.add(0, new SwimGoal(this));
        this.goalSelector.add(1, new MeleeAttackGoal(this, 1.25D, true));
        this.goalSelector.add(2, new KeepDistanceGoal(this));
        this.goalSelector.add(3, new WanderAroundFarGoal(this, 0.9D));
        this.goalSelector.add(4, new LookAtEntityGoal(this, PlayerEntity.class, 8.0F));
        this.goalSelector.add(5, new LookAroundGoal(this));
        this.targetSelector.add(2, new ActiveTargetGoal<>(this, LivingEntity.class, 5, true, false, this::isFoe));
    }

    @Override
    public boolean canTarget(LivingEntity target) {
        return isFoe(target) && super.canTarget(target);
    }

    @Override
    public void setTarget(@Nullable LivingEntity target) {
        super.setTarget(target != null && !isFoe(target) ? null : target);
    }

    /** The summoner's AoE never thins out their own squad. */
    @Override
    public boolean damage(DamageSource source, float amount) {
        if (isFriend(source.getAttacker())) return false;
        return super.damage(source, amount);
    }

    @Override
    protected void mobTick() {
        super.mobTick();
        PlayerEntity owner = getOwner();
        // Whatever the summoner is fighting - or being hurt by - comes first
        if (owner != null && age % 5 == 0) {
            LivingEntity threat = owner.getAttacker() != null && isFoe(owner.getAttacker()) ? owner.getAttacker() : null;
            if (threat == null && owner.getAttacking() != null && isFoe(owner.getAttacking())) threat = owner.getAttacking();
            if (threat != null && threat != getTarget()) setTarget(threat);
        }
        if (getTarget() != null && !isFoe(getTarget())) setTarget(null);
    }

    @Override
    public void tick() {
        super.tick();
        World world = getWorld();
        if (world.isClient) {
            // Faint sparks off a Steve, and a steady glow of them off the leader
            if (random.nextInt(isLeader() ? 2 : 6) == 0) {
                world.addParticle(isLeader() ? ParticleTypes.ENCHANT : ParticleTypes.END_ROD,
                        getX() + (random.nextDouble() - 0.5) * 0.8, getY() + random.nextDouble() * 1.9, getZ() + (random.nextDouble() - 0.5) * 0.8,
                        0, 0.02, 0);
            }
            return;
        }
        if (ownerId == null || --life <= 0) vanish();
    }

    /** Gone in a puff: the summons ran out, or the summoner called a new squad. */
    public void vanish() {
        if (getWorld() instanceof ServerWorld sw) {
            Vec3d c = getBoundingBox().getCenter();
            sw.spawnParticles(ParticleTypes.POOF, c.x, c.y, c.z, 14, 0.3, 0.5, 0.3, 0.03);
            sw.spawnParticles(SPARK, c.x, c.y, c.z, 12, 0.35, 0.6, 0.35, 0);
            sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.HOSTILE, 0.8f, 0.6f);
        }
        discard();
    }

    @Override
    public boolean tryAttack(Entity target) {
        boolean hit = super.tryAttack(target);
        if (hit && getWorld() instanceof ServerWorld sw) {
            Vec3d c = target.getBoundingBox().getCenter();
            sw.spawnParticles(isLeader() ? ParticleTypes.ENCHANTED_HIT : ParticleTypes.CRIT, c.x, c.y, c.z, 8, 0.25, 0.3, 0.25, 0.2);
            if (getMainHandStack().getItem() == Items.IRON_AXE) {
                sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK, SoundCategory.HOSTILE, 0.8f, 0.8f);
            } else {
                sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.HOSTILE, 0.8f, 1.0f);
            }
        }
        return hit;
    }

    /** A squad never outlives the session. */
    @Override
    public boolean shouldSave() {
        return false;
    }

    @Override
    public boolean cannotDespawn() {
        return true;
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return isLeader() ? ModSoundEvents.STEVE_AURA : null;
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

    /**
     * With nothing to fight, Steves roam freely but keep their distance from the summoner: never closer than
     * {@link #NEAR}, never further than {@link #FAR}. Out of that band they walk back into it (to {@link #SETTLE}),
     * and if left very far behind they teleport to catch up - at a distance, not on top of the summoner.
     */
    private static final class KeepDistanceGoal extends Goal {
        private static final double NEAR = 4, FAR = 14, SETTLE = 8, TELEPORT = 28;
        private final SteveEntity steve;
        @Nullable private PlayerEntity owner;
        @Nullable private Vec3d spot;

        KeepDistanceGoal(SteveEntity steve) {
            this.steve = steve;
            setControls(EnumSet.of(Control.MOVE));
        }

        @Override
        public boolean canStart() {
            owner = steve.getOwner();
            if (owner == null || steve.getTarget() != null) return false;
            double d2 = steve.squaredDistanceTo(owner);
            return d2 < NEAR * NEAR || d2 > FAR * FAR;
        }

        @Override
        public void start() {
            spot = settleSpot();
            if (spot != null) steve.getNavigation().startMovingTo(spot.x, spot.y, spot.z, 1.15);
        }

        @Override
        public boolean shouldContinue() {
            return owner != null && owner.isAlive() && steve.getTarget() == null && spot != null
                    && steve.squaredDistanceTo(spot) > 1.5 * 1.5 && !steve.getNavigation().isIdle();
        }

        @Override
        public void tick() {
            if (owner == null) return;
            if (steve.squaredDistanceTo(owner) > TELEPORT * TELEPORT && owner.isOnGround()) {
                Vec3d to = settleSpot();
                if (to == null) return;
                steve.refreshPositionAndAngles(to.x, owner.getY(), to.z, steve.getYaw(), steve.getPitch());
                steve.getNavigation().stop();
                spot = null;
            }
        }

        /** {@link #SETTLE} blocks from the summoner, on this Steve's side of them (a random side if right on top). */
        @Nullable
        private Vec3d settleSpot() {
            if (owner == null) return null;
            Vec3d away = steve.getPos().subtract(owner.getPos()).multiply(1, 0, 1);
            if (away.lengthSquared() < 0.25) {
                double a = steve.random.nextDouble() * Math.PI * 2;
                away = new Vec3d(Math.cos(a), 0, Math.sin(a));
            }
            return owner.getPos().add(away.normalize().multiply(SETTLE));
        }

        @Override
        public void stop() {
            owner = null;
            spot = null;
        }
    }
}
