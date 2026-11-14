package net.ragnar.ragnarsmagicmod.wizard;

import net.minecraft.component.type.DyedColorComponent;
import net.minecraft.entity.EntityData;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.Leashable;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ai.NoPenaltyTargeting;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.goal.GoToWalkTargetGoal;
import net.minecraft.entity.ai.goal.LookAtCustomerGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.StopAndLookAtEntityGoal;
import net.minecraft.entity.ai.goal.StopFollowingCustomerGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.goal.WanderAroundFarGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.passive.HorseEntity;
import net.minecraft.entity.passive.WanderingTraderEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.DyeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LocalDifficulty;
import net.minecraft.world.ServerWorldAccess;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.sleep.Sleep;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Wandering Wizard: a wandering trader who took up magic. He wanders the same way, leading his horse in dyed
 * leather armour, and trades tomes for tomes (see WizardTrades). At night he turns himself invisible with a spell
 * rather than a potion. Each cast has a wind-up the client draws (see WanderingWizardClient): he's sent what he's
 * casting through {@link #CAST}. Hit him - or be a monster coming for him - and he backs away and fights with the three
 * tomes he carries (see WizardSpell), never the same one twice running, until you're down, asleep, far away, or you've
 * left him alone for a while. Whatever falls asleep is left alone, and he won't put the same one to sleep again for
 * {@link #SLEEP_GRACE_TICKS}, so nobody can be kept asleep.
 */
public class WanderingWizardEntity extends WanderingTraderEntity {
    /** The share of their usual damage his spells do: he's a traveller, not a boss. */
    public static final float SPELL_DAMAGE_SCALE = 0.5f;
    /** Wind-up before a cast: the tome glows and the air hums, so you can see it coming. */
    public static final int WINDUP_TICKS = 14;
    /** A player he's fighting has to keep at it: this long without being hit again and he calms down. */
    public static final int CALM_TICKS = 20 * 20;
    /** He tries to keep at least this far from whatever he's fighting. */
    private static final double KEEP_AWAY = 7.0;
    /** And gives up on a fight past this distance. */
    private static final double GIVE_UP = 32.0;
    public static final int INVISIBILITY_WINDUP = 30;
    /** What he's casting, for the client to draw: a WizardSpell's ordinal, {@link #CAST_INVISIBILITY}, or -1. */
    private static final TrackedData<Integer> CAST = DataTracker.registerData(WanderingWizardEntity.class, TrackedDataHandlerRegistry.INTEGER);
    public static final int CAST_INVISIBILITY = 100;
    public static final int INVISIBILITY_COLOR = 0x9FE0C8;
    /** How long the circle takes to flare out after a cast goes off. */
    public static final int FLASH_TICKS = 8;

    // Client only: how far into a cast the visuals are, and the flare when it goes off
    private float castAnim, prevCastAnim;
    private int castKind = -1;
    private int flashTicks;
    private boolean wasCasting;

    /** How many tomes he carries, and how rarely one of them is the Tome of Thunderstrike (one wizard in this many). */
    public static final int LOADOUT = 3;
    public static final int THUNDERSTRIKE_ONE_IN = 25;
    /** After he's put something to sleep, how long before he'd use a sleep spell on it again (a minute). */
    public static final int SLEEP_GRACE_TICKS = 20 * 60;

    private List<WizardSpell> spells = new ArrayList<>();
    /** The spell he'll cast next (its tome is the one in his hands), the one he's casting, and the one he last cast. */
    @Nullable private WizardSpell next, casting, last;
    private final Map<WizardSpell, Integer> lastCastAge = new EnumMap<>(WizardSpell.class);
    /** Whatever he's seen fall asleep, and when (world time). */
    private final Map<UUID, Long> sleptAt = new HashMap<>();
    private int castCooldown = 20;
    private int windup = -1;
    private int shotsLeft, shotGap, shotIndex, volley;
    private int calmAt;
    private boolean spellInvisible;
    private int invisibilityWindup = -1;
    private boolean needsHorse;
    @Nullable private BlockPos walkTarget;

    public WanderingWizardEntity(EntityType<? extends WanderingWizardEntity> type, World world) {
        super(type, world);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return MobEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 24.0)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 24.0);
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(CAST, -1);
    }

    @Override
    protected void initGoals() {
        // The wandering trader's own goals, except that instead of running from danger he stands his ground
        goalSelector.add(0, new SwimGoal(this));
        goalSelector.add(1, new StopFollowingCustomerGoal(this));
        goalSelector.add(1, new FightGoal());
        goalSelector.add(1, new LookAtCustomerGoal(this));
        goalSelector.add(2, new WalkToTargetGoal());
        goalSelector.add(4, new GoToWalkTargetGoal(this, 0.35));
        goalSelector.add(8, new WanderAroundFarGoal(this, 0.35));
        goalSelector.add(9, new StopAndLookAtEntityGoal(this, PlayerEntity.class, 3.0f, 1.0f));
        goalSelector.add(10, new LookAtEntityGoal(this, MobEntity.class, 8.0f));
    }

    @Override
    public EntityData initialize(ServerWorldAccess world, LocalDifficulty difficulty, SpawnReason reason, @Nullable EntityData data) {
        setSpells(rollLoadout(world.getRandom()));
        needsHorse = true;
        return super.initialize(world, difficulty, reason, data);
    }

    /**
     * Three different spells, at most one of them a sleep spell. One wizard in {@link #THUNDERSTRIKE_ONE_IN} carries the
     * Tome of Thunderstrike as one of his three; no other ever does.
     */
    public static List<WizardSpell> rollLoadout(net.minecraft.util.math.random.Random random) {
        List<WizardSpell> pool = new ArrayList<>(List.of(WizardSpell.values()));
        pool.remove(WizardSpell.THUNDERSTRIKE);
        List<WizardSpell> out = new ArrayList<>();
        while (out.size() < LOADOUT && !pool.isEmpty()) {
            WizardSpell s = pool.remove(random.nextInt(pool.size()));
            out.add(s);
            if (s.sleeps()) pool.removeIf(WizardSpell::sleeps);
        }
        if (random.nextInt(THUNDERSTRIKE_ONE_IN) == 0) out.set(random.nextInt(out.size()), WizardSpell.THUNDERSTRIKE);
        return out;
    }

    public List<WizardSpell> getSpells() {
        return List.copyOf(spells);
    }

    /** The spell he'll cast next - the tome in his hands. */
    public WizardSpell getSpell() {
        return next != null ? next : spells.isEmpty() ? null : spells.get(0);
    }

    public void setSpells(List<WizardSpell> spells) {
        this.spells = new ArrayList<>(spells);
        last = null;
        lastCastAge.clear();
        hold(this.spells.get(random.nextInt(this.spells.size())), false);
    }

    /** Just the one spell (for tests and photo shoots). */
    public void setSpell(WizardSpell spell) {
        setSpells(List.of(spell));
    }

    /** Takes out the tome for {@code spell}, with the sound of a book being swapped if he's changing tomes. */
    private void hold(WizardSpell spell, boolean sound) {
        next = spell;
        if (getMainHandStack().isOf(spell.tome())) return;
        equipStack(EquipmentSlot.MAINHAND, new ItemStack(spell.tome()));
        setEquipmentDropChance(EquipmentSlot.MAINHAND, 0f);
        if (sound && !getWorld().isClient) {
            getWorld().playSound(null, getX(), getEyeY(), getZ(), SoundEvents.ITEM_BOOK_PAGE_TURN, SoundCategory.NEUTRAL, 1.0f, 0.9f);
        }
    }

    /**
     * Which of his spells to cast next at {@code target}: never the one he just cast (when he has another), not one
     * still recovering, and no sleep spell on something he put to sleep in the last minute. Null if none will do yet.
     */
    @Nullable
    public WizardSpell chooseNext(@Nullable LivingEntity target) {
        List<WizardSpell> ok = new ArrayList<>();
        for (WizardSpell s : spells) {
            if (s == last && spells.size() > 1) continue;
            Integer at = lastCastAge.get(s);
            if (at != null && age - at < s.recast()) continue;
            if (s.sleeps() && target != null && sleepGuarded(target)) continue;
            ok.add(s);
        }
        return ok.isEmpty() ? null : ok.get(random.nextInt(ok.size()));
    }

    /** True while {@code e} fell asleep too recently for him to put it to sleep again. */
    public boolean sleepGuarded(LivingEntity e) {
        Long at = sleptAt.get(e.getUuid());
        return at != null && getWorld().getTime() - at < SLEEP_GRACE_TICKS;
    }

    /** For tests: as if he'd just cast {@code spell}. */
    public void markCast(WizardSpell spell) {
        last = spell;
        lastCastAge.put(spell, age);
    }

    /** For tests: as if he'd just seen {@code e} fall asleep. */
    public void markSlept(LivingEntity e) {
        sleptAt.put(e.getUuid(), getWorld().getTime());
    }

    // ---------------------------------------------------------------------
    // Trading
    // ---------------------------------------------------------------------

    @Override
    protected void fillRecipes() {
        WizardTrades.fill(getOffers(), random);
    }

    @Override
    public ActionResult interactMob(PlayerEntity player, Hand hand) {
        if (getTarget() == player) {
            // No trading with someone he's fighting
            if (!getWorld().isClient) {
                setHeadRollingTimeLeft(40);
                playSound(SoundEvents.ENTITY_WANDERING_TRADER_NO, getSoundVolume(), getSoundPitch());
            }
            return ActionResult.success(getWorld().isClient);
        }
        return super.interactMob(player, hand);
    }

    /** A little deeper-voiced than the trader. */
    @Override
    public float getSoundPitch() {
        return super.getSoundPitch() * 0.85f;
    }

    @Override
    public void setWanderTarget(@Nullable BlockPos pos) {
        super.setWanderTarget(pos);
        walkTarget = pos;
    }

    // ---------------------------------------------------------------------
    // Fighting
    // ---------------------------------------------------------------------

    @Override
    public boolean damage(DamageSource source, float amount) {
        boolean hurt = super.damage(source, amount);
        if (hurt && !getWorld().isClient && source.getAttacker() instanceof LivingEntity attacker && isFoe(attacker)) {
            provokedBy(attacker);
        }
        return hurt;
    }

    private boolean isFoe(LivingEntity e) {
        return e != this && e.isAlive() && canTarget(e) && !(e instanceof WanderingWizardEntity)
                && !(e instanceof Leashable l && l.getLeashHolder() == this);
    }

    private void provokedBy(LivingEntity foe) {
        if (getTarget() != foe) {
            setTarget(foe);
            castCooldown = Math.min(castCooldown, 10); // the first spell comes quickly
        }
        calmAt = age + CALM_TICKS;
        setCustomer(null);
        reveal();
    }

    /** Who he's fighting right now, or null - dropping the fight once it's over. */
    @Nullable
    private LivingEntity foe() {
        LivingEntity t = getTarget();
        if (t == null) return null;
        boolean over = !t.isAlive() || t.getWorld() != getWorld() || !isFoe(t) || squaredDistanceTo(t) > GIVE_UP * GIVE_UP
                || (t instanceof PlayerEntity && age > calmAt)
                // Whatever's nodding off or asleep is left to snore: he never hits a sleeper
                || Sleep.isSleepy(t);
        if (over) {
            if (Sleep.isSleepy(t)) {
                markSlept(t);
                // And he walks off a little way
                Vec3d away = NoPenaltyTargeting.findFrom(this, 14, 5, t.getPos());
                if (away != null) setWanderTarget(BlockPos.ofFloored(away));
            }
            setTarget(null);
            if (windup >= 0) dataTracker.set(CAST, -1);
            windup = -1;
            shotsLeft = 0;
            return null;
        }
        return t;
    }

    @Override
    protected void mobTick() {
        super.mobTick();
        if (!(getWorld() instanceof ServerWorld world)) return;
        if (spells.isEmpty()) setSpells(rollLoadout(random));
        if (age % 200 == 0) sleptAt.values().removeIf(at -> world.getTime() - at > SLEEP_GRACE_TICKS);
        if (needsHorse) {
            needsHorse = false;
            bringHorse(world);
        }

        // Monsters coming for him get the same treatment as anyone who hits him
        if (getTarget() == null && age % 10 == 0) {
            MobEntity threat = null;
            for (MobEntity m : world.getEntitiesByClass(MobEntity.class, getBoundingBox().expand(12.0),
                    m -> m instanceof Monster && m.getTarget() == this && isFoe(m))) {
                if (threat == null || squaredDistanceTo(m) < squaredDistanceTo(threat)) threat = m;
            }
            if (threat != null) provokedBy(threat);
        }

        LivingEntity foe = foe();
        if (foe != null) {
            tickCasting(world, foe);
        } else {
            tickInvisibility(world);
        }
    }

    private void tickCasting(ServerWorld world, LivingEntity foe) {
        if (shotsLeft > 0) {
            if (--shotGap <= 0) shoot(world, foe);
            return;
        }
        if (windup >= 0) {
            face(foe);
            windupFx(world);
            if (--windup < 0) {
                dataTracker.set(CAST, -1);
                release(world);
                shotsLeft = casting.shots;
                shotIndex = 0;
                volley = -(1 + random.nextInt(Integer.MAX_VALUE - 1)); // never clashes with a player's volley
                shoot(world, foe);
            }
            return;
        }
        if (--castCooldown > 0) return;
        // The tome in his hands may not suit any more (a sleep spell on someone who just woke): pick again
        if (next == null || next == last && spells.size() > 1 || next.sleeps() && sleepGuarded(foe)) {
            WizardSpell pick = chooseNext(foe);
            if (pick == null) return;
            hold(pick, true);
        }
        if (canSee(foe) && distanceTo(foe) <= next.range) {
            casting = next;
            windup = WINDUP_TICKS;
            dataTracker.set(CAST, casting.ordinal());
            Vec3d at = getEyePos();
            world.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_EVOKER_PREPARE_ATTACK, SoundCategory.HOSTILE, 0.6f, 1.4f);
            world.playSound(null, at.x, at.y, at.z, SoundEvents.BLOCK_ENCHANTMENT_TABLE_USE, SoundCategory.HOSTILE, 1.0f, 1.2f);
        }
    }

    private void shoot(ServerWorld world, LivingEntity foe) {
        face(foe);
        casting.cast(world, this, foe, shotIndex++, volley);
        shotsLeft--;
        shotGap = casting.shotGap;
        if (shotsLeft <= 0) {
            castCooldown = casting.cooldown + random.nextInt(20);
            markCast(casting);
            // Straight on to the next tome, so you can see what's coming
            WizardSpell pick = chooseNext(foe);
            if (pick != null) hold(pick, true);
        }
    }

    /** Turns square on to it, so the sigil and the spell point its way. */
    private void face(LivingEntity foe) {
        getLookControl().lookAt(foe, 360f, 360f);
        double dx = foe.getX() - getX(), dz = foe.getZ() - getZ();
        float yaw = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90f;
        setYaw(yaw);
        setHeadYaw(yaw);
        setBodyYaw(yaw);
    }

    /** Where the tome is while he holds it up to cast. */
    private Vec3d tomePos() {
        float yaw = bodyYaw * MathHelper.RADIANS_PER_DEGREE;
        return getPos().add(-MathHelper.sin(yaw) * 0.6, 1.3, MathHelper.cos(yaw) * 0.6);
    }

    private static DustParticleEffect dust(int color, float size) {
        return new DustParticleEffect(new Vector3f((color >> 16 & 255) / 255f, (color >> 8 & 255) / 255f, (color & 255) / 255f), size);
    }

    /** The wind-up: motes in the spell's colour drawn in to the tome, and a chime climbing in pitch. */
    private void windupFx(ServerWorld world) {
        windupFx(world, casting.color(), WINDUP_TICKS - windup, WINDUP_TICKS);
    }

    private void windupFx(ServerWorld world, int color, int tick, int of) {
        Vec3d tome = tomePos();
        DustParticleEffect mote = dust(color, 0.7f);
        double a = random.nextDouble() * MathHelper.TAU;
        double r = 1.1 - 0.5 * tick / (double) of;
        double x = tome.x + Math.cos(a) * r, z = tome.z + Math.sin(a) * r;
        double y = tome.y + (random.nextDouble() - 0.5) * 0.9;
        Vec3d in = tome.subtract(x, y, z).multiply(0.2);
        world.spawnParticles(mote, x, y, z, 0, in.x, in.y, in.z, 1.0);
        if (tick % 2 == 0) world.spawnParticles(ParticleTypes.ENCHANT, tome.x, tome.y + 0.6, tome.z, 2, 0.4, 0.3, 0.4, 0.7);
        if (tick % 4 == 0) {
            world.playSound(null, tome.x, tome.y, tome.z, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.HOSTILE,
                    0.7f, 0.8f + 1.0f * tick / of);
        }
    }

    /** The moment the spell leaves the tome: a ring of sparks in its colour. */
    private void release(ServerWorld world) {
        Vec3d tome = tomePos();
        DustParticleEffect spark = dust(casting.color(), 1.0f);
        for (int i = 0; i < 14; i++) {
            double a = i / 14.0 * MathHelper.TAU;
            world.spawnParticles(spark, tome.x, tome.y, tome.z, 0, Math.cos(a), 0.15, Math.sin(a), 0.25);
        }
        world.spawnParticles(ParticleTypes.ENCHANTED_HIT, tome.x, tome.y, tome.z, 8, 0.2, 0.2, 0.2, 0.3);
        world.playSound(null, tome.x, tome.y, tome.z, SoundEvents.ENTITY_EVOKER_CAST_SPELL, SoundCategory.HOSTILE, 0.5f, 1.4f);
    }

    // ---------------------------------------------------------------------
    // Client-side cast animation
    // ---------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (getWorld().isClient) tickCastAnim();
    }

    private void tickCastAnim() {
        prevCastAnim = castAnim;
        if (flashTicks > 0) flashTicks--;
        int kind = dataTracker.get(CAST);
        if (kind >= 0) {
            castKind = kind;
            castAnim = Math.min(1f, castAnim + 1f / (kind == CAST_INVISIBILITY ? INVISIBILITY_WINDUP : WINDUP_TICKS));
            wasCasting = true;
        } else {
            // A cast that got all the way through flares out; one that was cut short just fades
            if (wasCasting && castAnim >= 0.85f) flashTicks = FLASH_TICKS;
            wasCasting = false;
            castAnim = Math.max(0f, castAnim - 0.25f);
        }
    }

    /** 0 to 1 through the wind-up of a cast. */
    public float getCastAnim(float tickDelta) {
        return MathHelper.lerp(tickDelta, prevCastAnim, castAnim);
    }

    /** 1 just as a cast goes off, down to 0 as its circle flares out. */
    public float getCastFlash(float tickDelta) {
        return flashTicks <= 0 ? 0f : MathHelper.clamp((flashTicks - tickDelta) / FLASH_TICKS, 0f, 1f);
    }

    /** The colour of the cast being drawn. */
    public int getCastColor() {
        WizardSpell[] all = WizardSpell.values();
        return castKind >= 0 && castKind < all.length ? all[castKind].color() : INVISIBILITY_COLOR;
    }

    public boolean isCastingInvisibility() {
        return castKind == CAST_INVISIBILITY;
    }

    /** Backs away from what he's fighting to cast from a safe distance, and closes in if it's out of reach. */
    private final class FightGoal extends Goal {
        private int repath;

        FightGoal() {
            setControls(EnumSet.of(Control.MOVE, Control.LOOK));
        }

        @Override
        public boolean canStart() {
            return getTarget() != null && getTarget().isAlive();
        }

        @Override
        public boolean shouldContinue() {
            return canStart();
        }

        @Override
        public void start() {
            repath = 0;
        }

        @Override
        public void stop() {
            getNavigation().stop();
        }

        @Override
        public boolean shouldRunEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            LivingEntity t = getTarget();
            if (t == null) return;
            getLookControl().lookAt(t, 30f, 30f);
            if (--repath > 0) return;
            repath = 10;
            double d = distanceTo(t);
            if (d < KEEP_AWAY) {
                Vec3d away = NoPenaltyTargeting.findFrom(WanderingWizardEntity.this, 10, 4, t.getPos());
                if (away != null) getNavigation().startMovingTo(away.x, away.y, away.z, 0.6);
            } else if (next != null && (d > next.range - 2 || !canSee(t))) {
                getNavigation().startMovingTo(t, 0.5);
            } else {
                getNavigation().stop();
            }
        }
    }

    /** Heads for the spot he was sent to (near a player, when he arrives), as the wandering trader does. */
    private final class WalkToTargetGoal extends Goal {
        WalkToTargetGoal() {
            setControls(EnumSet.of(Control.MOVE));
        }

        @Override
        public boolean canStart() {
            return walkTarget != null && getTarget() == null && !walkTarget.isWithinDistance(getPos(), 2.0);
        }

        @Override
        public void stop() {
            walkTarget = null;
            getNavigation().stop();
        }

        @Override
        public void tick() {
            if (walkTarget == null || !getNavigation().isIdle()) return;
            Vec3d to = Vec3d.ofBottomCenter(walkTarget);
            Vec3d step = to.subtract(getPos());
            if (step.length() > 10.0) {
                // Far off: a stretch at a time
                Vec3d mid = getPos().add(step.normalize().multiply(10.0));
                Vec3d spot = NoPenaltyTargeting.findTo(WanderingWizardEntity.this, 10, 7, mid, Math.PI / 2);
                if (spot != null) getNavigation().startMovingTo(spot.x, spot.y, spot.z, 0.35);
            } else {
                getNavigation().startMovingTo(to.x, to.y, to.z, 0.35);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Night invisibility
    // ---------------------------------------------------------------------

    private void tickInvisibility(ServerWorld world) {
        boolean night = world.isNight();
        if (invisibilityWindup >= 0) {
            if (!night) {
                invisibilityWindup = -1;
                dataTracker.set(CAST, -1);
                return;
            }
            Vec3d c = getPos().add(0, 1.0, 0);
            windupFx(world, INVISIBILITY_COLOR, INVISIBILITY_WINDUP - invisibilityWindup, INVISIBILITY_WINDUP);
            if (--invisibilityWindup < 0) {
                dataTracker.set(CAST, -1);
                addStatusEffect(new StatusEffectInstance(StatusEffects.INVISIBILITY, StatusEffectInstance.INFINITE, 0, false, false));
                spellInvisible = true;
                world.spawnParticles(ParticleTypes.PORTAL, c.x, c.y, c.z, 40, 0.4, 0.7, 0.4, 0.4);
                world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_ILLUSIONER_MIRROR_MOVE, SoundCategory.NEUTRAL, 1.0f, 1.0f);
            }
            return;
        }
        if (age % 20 != 0) return;
        if (night && !hasStatusEffect(StatusEffects.INVISIBILITY)) {
            invisibilityWindup = INVISIBILITY_WINDUP;
            dataTracker.set(CAST, CAST_INVISIBILITY);
            world.playSound(null, getX(), getEyeY(), getZ(), SoundEvents.ENTITY_ILLUSIONER_PREPARE_MIRROR, SoundCategory.NEUTRAL, 0.8f, 1.1f);
        } else if (!night && spellInvisible) {
            // Morning: the spell wears off
            reveal();
        }
    }

    /** Drops his invisibility spell, with a shimmer. */
    private void reveal() {
        if (invisibilityWindup >= 0) dataTracker.set(CAST, -1);
        invisibilityWindup = -1;
        if (!spellInvisible) return;
        spellInvisible = false;
        removeStatusEffect(StatusEffects.INVISIBILITY);
        if (getWorld() instanceof ServerWorld world) {
            Vec3d c = getPos().add(0, 1.0, 0);
            world.spawnParticles(ParticleTypes.WITCH, c.x, c.y, c.z, 20, 0.4, 0.7, 0.4, 0.0);
            world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.NEUTRAL, 1.0f, 0.8f);
        }
    }

    // ---------------------------------------------------------------------
    // His horse
    // ---------------------------------------------------------------------

    /** A tame, saddled horse in leather armour of a random dye colour, on a lead behind him. */
    private void bringHorse(ServerWorld world) {
        BlockPos at = getBlockPos().add(random.nextBetween(-2, 2), 0, random.nextBetween(-2, 2));
        HorseEntity horse = EntityType.HORSE.create(world);
        if (horse == null) return;
        horse.refreshPositionAndAngles(at.getX() + 0.5, getY(), at.getZ() + 0.5, random.nextFloat() * 360f, 0f);
        if (!world.isSpaceEmpty(horse)) horse.refreshPositionAndAngles(getX(), getY(), getZ(), getYaw(), 0f);
        horse.initialize(world, world.getLocalDifficulty(at), SpawnReason.EVENT, null);
        horse.setTame(true);
        horse.saddle(new ItemStack(Items.SADDLE), null);
        // Dyed the way a player would dye it, with a single dye
        DyeColor dye = DyeColor.values()[random.nextInt(DyeColor.values().length)];
        horse.equipBodyArmor(DyedColorComponent.setColor(new ItemStack(Items.LEATHER_HORSE_ARMOR), List.of(DyeItem.byColor(dye))));
        world.spawnEntity(horse);
        horse.attachLeash(this, true);
    }

    @Override
    public void remove(RemovalReason reason) {
        // When he leaves for good (his time's up), the horse on his lead goes with him
        if (reason == RemovalReason.DISCARDED && getWorld() instanceof ServerWorld world) {
            for (HorseEntity horse : world.getEntitiesByClass(HorseEntity.class, getBoundingBox().expand(16.0), h -> h.getLeashHolder() == this)) {
                horse.discard();
            }
        }
        super.remove(reason);
    }

    // ---------------------------------------------------------------------
    // Saving
    // ---------------------------------------------------------------------

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        NbtList list = new NbtList();
        for (WizardSpell s : spells) list.add(NbtString.of(s.name()));
        nbt.put("WizardSpells", list);
        nbt.putBoolean("WizardInvisible", spellInvisible);
        nbt.putBoolean("WizardNeedsHorse", needsHorse);
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        List<WizardSpell> read = new ArrayList<>();
        for (net.minecraft.nbt.NbtElement e : nbt.getList("WizardSpells", NbtElement.STRING_TYPE)) {
            try {
                read.add(WizardSpell.valueOf(e.asString()));
            } catch (IllegalArgumentException ignored) {
                // A spell that's since gone
            }
        }
        // A wizard from before he carried three: keep his one spell and give him two more
        if (nbt.contains("WizardSpell")) {
            try {
                WizardSpell old = WizardSpell.valueOf(nbt.getString("WizardSpell"));
                read = new ArrayList<>(rollLoadout(random));
                read.remove(old);
                read.set(0, old);
                while (read.size() > LOADOUT) read.remove(read.size() - 1);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (!read.isEmpty()) {
            spells = read;
            next = spells.stream().filter(s -> getMainHandStack().isOf(s.tome())).findFirst().orElse(spells.get(0));
        }
        spellInvisible = nbt.getBoolean("WizardInvisible");
        needsHorse = nbt.getBoolean("WizardNeedsHorse");
    }
}
