package net.ragnar.ragnarsmagicmod.lavafish;

import net.minecraft.advancement.criterion.Criteria;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.ai.goal.FleeEntityGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.passive.FishEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsage;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.Comparator;

/**
 * The lava fish: a cod of charred stone and bare bone, cracked through with lava. It's only ever pulled out of lava
 * (see Fishing), and it's a fish in every way but temper:
 * <ul>
 *   <li>on land it flops about like any fish, but it's red hot: anything that comes within reach is scorched and set
 *       alight. It can last {@link #MAX_AIR} ticks out of liquid, twice as long as a cod;</li>
 *   <li>in water it swims like any other fish, but it never flees, and anything that comes right up to it - a
 *       player or a mob - may get bitten;</li>
 *   <li>in lava it's at home: it can't burn or drown, and it drifts about under the surface. Bites there burn.</li>
 * </ul>
 * Catch it in a water bucket (Bucket of Lava Fish) or a lava bucket (Lava Bucket of Lava Fish) and take it home: it
 * never despawns, so it's a keepsake.
 */
public class LavaFishEntity extends FishEntity {
    public static final int MAX_AIR = 600;
    public static final float SCORCH_DAMAGE = 2f, BITE_DAMAGE = 3f;
    static final int SCORCH_EVERY = 10, BITE_EVERY = 20;
    static final double SCORCH_REACH = 0.9, BITE_REACH = 0.6;

    private int scorchCooldown, biteCooldown, wanderCooldown;

    public LavaFishEntity(EntityType<? extends FishEntity> type, World world) {
        super(type, world);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return FishEntity.createFishAttributes().add(EntityAttributes.GENERIC_MAX_HEALTH, 8.0);
    }

    @Override
    protected void initGoals() {
        super.initGoals();
        // A cod flees from players; this one doesn't
        goalSelector.getGoals().removeIf(g -> g.getGoal() instanceof FleeEntityGoal<?>);
    }

    // ---------------------------------------------------------------------
    // Breathing: water or lava, and twice a cod's staying power out of both
    // ---------------------------------------------------------------------

    @Override
    public int getMaxAir() {
        return MAX_AIR;
    }

    @Override
    protected void tickWaterBreathingAir(int air) {
        if (isAlive() && !isInsideWaterOrBubbleColumn() && !isInLava()) {
            setAir(air - 1);
            if (getAir() == -20) {
                setAir(0);
                damage(getDamageSources().dryOut(), 2.0f);
            }
        } else {
            setAir(getMaxAir());
        }
    }

    // ---------------------------------------------------------------------
    // Lava: drifting under the surface instead of sinking and flopping on the bottom
    // ---------------------------------------------------------------------

    @Override
    public void travel(Vec3d input) {
        if (canMoveVoluntarily() && isInLava()) {
            updateVelocity(0.01f, input);
            move(MovementType.SELF, getVelocity());
            Vec3d v = getVelocity().multiply(0.9);
            // Hover just under the surface: buoyed up while under, pulled back down the moment it breaks through
            double lift = isSubmergedIn(FluidTags.LAVA) ? 0.003 : -0.02;
            setVelocity(v.x, v.y + lift, v.z);
            return;
        }
        super.travel(input);
    }

    @Override
    public void tickMovement() {
        if (!getWorld().isClient && isInLava() && --wanderCooldown <= 0) {
            // Nothing can path through lava, so it wanders on little pushes of its own
            wanderCooldown = 30 + random.nextInt(50);
            float yaw = random.nextFloat() * MathHelper.TAU;
            addVelocity(-MathHelper.sin(yaw) * 0.08, (random.nextFloat() - 0.5) * 0.02, MathHelper.cos(yaw) * 0.08);
            setYaw(yaw * MathHelper.DEGREES_PER_RADIAN);
            bodyYaw = getYaw();
        }
        super.tickMovement();
    }

    // ---------------------------------------------------------------------
    // Temper
    // ---------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (!(getWorld() instanceof ServerWorld world) || !isAlive()) return;
        boolean wet = isTouchingWater(), molten = isInLava();

        if (!wet && !molten) {
            // Out on land: red hot
            if (age % 4 == 0) world.spawnParticles(random.nextBoolean() ? ParticleTypes.SMOKE : ParticleTypes.SMALL_FLAME,
                    getX(), getY() + 0.2, getZ(), 1, 0.15, 0.05, 0.15, 0.01);
            if (age % 25 == 0) world.spawnParticles(ParticleTypes.LAVA, getX(), getY() + 0.2, getZ(), 1, 0, 0, 0, 0);
            if (--scorchCooldown <= 0) {
                scorchCooldown = SCORCH_EVERY;
                for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, getBoundingBox().expand(SCORCH_REACH), this::fairGame)) {
                    if (e.damage(getDamageSources().mobAttack(this), SCORCH_DAMAGE)) {
                        e.setOnFireFor(3);
                        world.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.HOSTILE, 0.5f, 1.6f);
                    }
                }
            }
            return;
        }

        // In water it hisses a little steam now and then
        if (wet && age % 8 == 0) world.spawnParticles(ParticleTypes.BUBBLE, getX(), getY() + 0.1, getZ(), 2, 0.1, 0.05, 0.1, 0.02);

        // Swimming: anything right up against it may get bitten
        if (--biteCooldown > 0) return;
        biteCooldown = BITE_EVERY;
        LivingEntity target = world.getEntitiesByClass(LivingEntity.class, getBoundingBox().expand(BITE_REACH), this::fairGame).stream()
                .min(Comparator.comparingDouble(this::squaredDistanceTo)).orElse(null);
        if (target == null || random.nextInt(3) != 0) return;
        if (target.damage(getDamageSources().mobAttack(this), BITE_DAMAGE)) {
            if (molten) target.setOnFireFor(4);
            world.playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_FOX_BITE, SoundCategory.HOSTILE, 0.8f, 1.5f);
            lookAtEntity(target, 30f, 30f);
        }
    }

    /** Anything it'll scorch or bite: any living thing but another lava fish (or a player who can't be hurt). */
    private boolean fairGame(LivingEntity e) {
        if (e == this || e instanceof LavaFishEntity || !e.isAlive() || e.isSpectator()) return false;
        return !(e instanceof PlayerEntity p) || !p.getAbilities().invulnerable;
    }

    // ---------------------------------------------------------------------
    // Buckets: water or lava
    // ---------------------------------------------------------------------

    @Override
    protected ActionResult interactMob(PlayerEntity player, Hand hand) {
        ItemStack held = player.getStackInHand(hand);
        if (held.isOf(Items.LAVA_BUCKET) && isAlive()) {
            playSound(SoundEvents.ITEM_BUCKET_FILL_LAVA, 1.0f, 1.0f);
            ItemStack filled = new ItemStack(LavaFish.LAVA_BUCKET_OF_LAVA_FISH);
            copyDataToStack(filled);
            player.setStackInHand(hand, ItemUsage.exchangeStack(held, player, filled, false));
            if (!getWorld().isClient && player instanceof ServerPlayerEntity sp) Criteria.FILLED_BUCKET.trigger(sp, filled);
            discard();
            return ActionResult.success(getWorld().isClient);
        }
        return super.interactMob(player, hand); // a water bucket, as for any fish
    }

    @Override
    public ItemStack getBucketItem() {
        return new ItemStack(LavaFish.BUCKET_OF_LAVA_FISH);
    }

    // A keepsake: never despawns
    @Override
    public boolean cannotDespawn() {
        return true;
    }

    @Override
    public boolean canImmediatelyDespawn(double distanceSquared) {
        return false;
    }

    @Override
    protected SoundEvent getFlopSound() {
        return SoundEvents.ENTITY_COD_FLOP;
    }

    @Override
    protected SoundEvent getHurtSound(net.minecraft.entity.damage.DamageSource source) {
        return SoundEvents.ENTITY_COD_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.ENTITY_COD_DEATH;
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.ENTITY_COD_AMBIENT;
    }
}
