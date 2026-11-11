package net.ragnar.ragnarsmagicmod.tornado;

import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.bubbles.Bubbles;
import net.ragnar.ragnarsmagicmod.crows.Crows;
import net.ragnar.ragnarsmagicmod.entity.IllusionEntity;
import net.ragnar.ragnarsmagicmod.phantom.Phantom;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The tornado. Its position is the bottom of the funnel, on the ground (or the water - it makes a waterspout there).
 * <p>
 * <b>Its path:</b> it sets off along the way you were looking, but how hard it's turning drifts about all the time and
 * every so often it lurches - a sudden swerve and a burst of speed - so it snakes, veers and sometimes doubles back.
 * It climbs steps up to a block high, drops off ledges, and runs into walls hard enough to be knocked off in a new
 * direction.
 * <p>
 * <b>What it does:</b> anything within {@link #PULL_RADIUS} blocks is dragged in toward it and swept around it. Once
 * something is inside the funnel it's lifted up it, whirled round and battered ({@link #SPIN_DAMAGE} every
 * second), and after a second or so it's thrown out, sideways and up ({@link #FLING_DAMAGE}, plus the fall). Big
 * heavy things (iron golems, wardens, ravagers...) are only buffeted. Never the caster or their pets.
 * <p>
 * The funnel, the dust skirt and the debris are all drawn on the client (TornadoRenderer); the server only moves it
 * and pushes things around.
 */
public class TornadoEntity extends Entity {
    public static final float HEIGHT = 7f;
    /** Radius of the funnel at the bottom and at the top. */
    public static final float BASE_RADIUS = 0.6f, TOP_RADIUS = 2.6f;
    public static final int LIFE = 8 * 20;
    /** Ticks it takes to spin up, and to blow itself out at the end. */
    public static final int GROW_TICKS = 12, FADE_TICKS = 20;
    public static final double PULL_RADIUS = 7.0;
    /** How high up the funnel caught things are carried before they're thrown. */
    public static final double CARRY_HEIGHT = 4.0;
    /** Damage each second while caught in the funnel (one hit a second, so hurt cooldowns never swallow it). */
    public static final float SPIN_DAMAGE = 2.0f, FLING_DAMAGE = 2.0f;

    private static final double CRUISE = 0.11;
    /** The highest step it climbs without stopping. */
    private static final double STEP = 1.3;

    /** True once it's begun to blow itself out. */
    private static final TrackedData<Boolean> FADING = DataTracker.registerData(TornadoEntity.class, TrackedDataHandlerRegistry.BOOLEAN);

    // ---------------------------------------------------------------------
    // Server
    // ---------------------------------------------------------------------
    @Nullable private UUID ownerId;
    /** Where it's heading (flat, unit length), and the way it was first sent. */
    private Vec3d heading = new Vec3d(0, 0, 1), aim = new Vec3d(0, 0, 1);
    private double turn, burst, phase;
    private int nextLurch = 20;
    /** Leans the top of the funnel back the way it's coming from; eased so it sways. Kept on both sides. */
    public Vec3d lean = Vec3d.ZERO;

    /** Something caught in the funnel. */
    private static final class Caught {
        int spun;
        final int throwAt;

        Caught(int throwAt) {
            this.throwAt = throwAt;
        }
    }

    private final Map<Integer, Caught> caught = new HashMap<>();
    /** Entity id -> the age until which it's flying free after being thrown, and can't be caught again. */
    private final Map<Integer, Integer> thrown = new HashMap<>();
    /** Entity id -> the age it can next be hurt by the spinning. */
    private final Map<Integer, Integer> nextHit = new HashMap<>();

    // ---------------------------------------------------------------------
    // Client
    // ---------------------------------------------------------------------
    @Nullable private Vec3d lerpTo;
    /** How fully spun up it looks (0 to 1), eased. */
    public float shownScale, prevShownScale;
    public Vec3d prevLean = Vec3d.ZERO;
    /** The colour of whatever it's tearing up (RGB), for the dust and debris. */
    public int groundColor = 0x8a7a62;
    /** The colour of what's under that (dirt under grass), which most of the debris is. */
    public int soilColor = 0x8a6040;
    /** The blocks themselves, for their textures: what it's on, and what's under that. */
    public BlockState groundState = net.minecraft.block.Blocks.GRASS_BLOCK.getDefaultState(),
            soilState = net.minecraft.block.Blocks.DIRT.getDefaultState();
    public boolean overWater;
    private int fadeStart = -1;
    public final List<Debris> debris = new ArrayList<>();

    /** A chunk of ground torn up and whirled up the funnel, then flung out. */
    public static final class Debris {
        public Vec3d pos, prev;
        Vec3d vel = Vec3d.ZERO;
        double angle, h, radius;
        final double angSpeed, climb, top;
        public final float size, r, g, b;
        public float spin, prevSpin;
        final float spinSpeed;
        boolean flung;
        /** A real block it's a chunk of (drawn with its texture), or null for a plain coloured fleck. */
        @Nullable public BlockState state;
        /** A shard of glass pane rather than a lump. */
        public boolean pane;
        int age;

        Debris(double angle, double angSpeed, double climb, double radius, double top, float size, float r, float g, float b, float spinSpeed) {
            this.angle = angle;
            this.top = top;
            this.angSpeed = angSpeed;
            this.climb = climb;
            this.radius = radius;
            this.size = size;
            this.r = r;
            this.g = g;
            this.b = b;
            this.spinSpeed = spinSpeed;
        }
    }

    public TornadoEntity(EntityType<? extends TornadoEntity> type, World world) {
        super(type, world);
        setNoGravity(true);
        noClip = true;
    }

    /** Puts a tornado on the ground a few steps in front of {@code owner}, or returns null if there's nowhere for it. */
    @Nullable
    static TornadoEntity summon(ServerWorld world, PlayerEntity owner) {
        TornadoEntity t = new TornadoEntity(Tornado.TORNADO, world);
        t.ownerId = owner.getUuid();
        Vec3d look = owner.getRotationVec(1f);
        Vec3d flat = new Vec3d(look.x, 0, look.z);
        flat = flat.lengthSquared() < 1e-4 ? Vec3d.fromPolar(0, owner.getYaw()) : flat.normalize();
        t.heading = t.aim = flat;
        t.phase = t.random.nextDouble() * Math.PI * 2;
        t.turn = (t.random.nextDouble() - 0.5) * 0.08;

        // A few steps ahead if the ground's there, otherwise as far as it gets
        Vec3d feet = owner.getPos();
        Vec3d spot = null;
        for (double d = 2.8; d >= 0; d -= 0.4) {
            Vec3d p = feet.add(flat.multiply(d));
            double y = groundAt(world, p.x, p.z, feet.y);
            if (!Double.isNaN(y) && y - feet.y <= STEP && clearPath(world, feet, p)) {
                spot = new Vec3d(p.x, y, p.z);
                break;
            }
        }
        if (spot == null) {
            double y = groundAt(world, feet.x, feet.z, feet.y);
            spot = new Vec3d(feet.x, Double.isNaN(y) ? feet.y : y, feet.z);
        }
        t.refreshPositionAndAngles(spot.x, spot.y, spot.z, 0f, 0f);
        world.spawnEntity(t);
        return t;
    }

    /** No wall taller than a step between {@code from} and {@code to} (feet positions). */
    private static boolean clearPath(World world, Vec3d from, Vec3d to) {
        Vec3d d = to.subtract(from);
        int n = (int) Math.ceil(d.length() / 0.4);
        for (int i = 1; i <= n; i++) {
            Vec3d p = from.add(d.multiply(i / (double) n));
            double y = groundAt(world, p.x, p.z, from.y);
            if (Double.isNaN(y) || y - from.y > STEP) return Double.isNaN(y);
        }
        return true;
    }

    /**
     * The surface (solid ground or the top of water) under the column at {@code x, z}, searching from a step above
     * {@code fromY} to four blocks below it; NaN if there's nothing. If what it finds is higher than a step above
     * {@code fromY}, that's a wall.
     */
    static double groundAt(World world, double x, double z, double fromY) {
        int top = MathHelper.floor(fromY + STEP);
        int bottom = MathHelper.floor(fromY) - 4;
        BlockPos.Mutable p = new BlockPos.Mutable();
        for (int y = top; y >= bottom; y--) {
            p.set(x, y, z);
            BlockState s = world.getBlockState(p);
            FluidState f = s.getFluidState();
            if (!f.isEmpty()) return y + f.getHeight(world, p);
            VoxelShape shape = s.getCollisionShape(world, p);
            if (!shape.isEmpty()) return y + shape.getMax(Direction.Axis.Y);
        }
        return Double.NaN;
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        builder.add(FADING, false);
    }

    public boolean isFading() {
        return dataTracker.get(FADING);
    }

    // ---------------------------------------------------------------------
    // Shape - shared by the renderer and the debris
    // ---------------------------------------------------------------------

    /** Radius of the funnel {@code h} blocks up (at full size). */
    public static double radiusAt(double h, double t) {
        double k = MathHelper.clamp(h / HEIGHT, 0, 1);
        return BASE_RADIUS + (TOP_RADIUS - BASE_RADIUS) * Math.pow(k, 1.6) + 0.05 * Math.sin(t * 0.5 + h * 3.0);
    }

    /**
     * How far the middle of the funnel is from its foot, {@code h} blocks up: it snakes about, more toward the top,
     * and the top trails behind the way it's going ({@code lean}).
     */
    public static Vec3d spine(double h, double t, int seed, Vec3d lean) {
        double k = MathHelper.clamp(h / HEIGHT, 0, 1);
        double big = 0.7 * Math.pow(k, 1.3), small = 0.1 * k;
        double x = Math.sin(t * 0.11 + h * 0.45 + seed) * big + Math.sin(t * 0.29 + h * 2.3 + seed * 2.0) * small;
        double z = Math.cos(t * 0.13 + h * 0.4 + seed * 0.7) * big + Math.cos(t * 0.31 + h * 2.1 + seed * 1.3) * small;
        double trail = k * k;
        return new Vec3d(x - lean.x * trail, 0, z - lean.z * trail);
    }

    // ---------------------------------------------------------------------
    // Entity plumbing
    // ---------------------------------------------------------------------

    @Override
    public boolean shouldSave() {
        return false;
    }

    @Override
    public boolean shouldRender(double distance) {
        return distance < 96 * 96;
    }

    @Override
    public Box getVisibilityBoundingBox() {
        // The top of the funnel is far wider than its foot, and leans
        return getBoundingBox().expand(TOP_RADIUS + 1.2, 0.5, TOP_RADIUS + 1.2);
    }

    @Override
    public boolean canHit() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean isCollidable() {
        return false;
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isFireImmune() {
        return true;
    }

    @Override
    public void updateTrackedPositionAndAngles(double x, double y, double z, float yaw, float pitch, int steps) {
        lerpTo = new Vec3d(x, y, z);
    }

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {}

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {}

    // ---------------------------------------------------------------------
    // Ticking
    // ---------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (getWorld().isClient) {
            clientTick();
            return;
        }
        ServerWorld sw = (ServerWorld) getWorld();
        if (age >= LIFE) {
            dissipate(sw);
            return;
        }
        if (age == LIFE - FADE_TICKS) {
            dataTracker.set(FADING, true);
            releaseAll(sw);
        }
        Vec3d before = getPos();
        wander(sw);
        Vec3d moved = getPos().subtract(before);
        lean = lean.multiply(0.88).add(moved.x * 0.84, 0, moved.z * 0.84);
        whirl(sw, moved);
        ambience(sw);
    }

    /** 0 to 1: how strong it is right now (spinning up, at full strength, blowing out). */
    private float strength() {
        float grow = MathHelper.clamp(age / (float) GROW_TICKS, 0f, 1f);
        float fade = MathHelper.clamp((LIFE - age) / (float) FADE_TICKS, 0f, 1f);
        return grow * fade;
    }

    private void wander(ServerWorld sw) {
        // How hard it's turning drifts about, and slowly settles; a gentle pull back toward where it was sent
        turn += random.nextGaussian() * 0.025;
        turn = MathHelper.clamp(turn * 0.96, -0.14, 0.14);
        // Every so often it lurches: a sudden swerve and a burst of speed
        if (--nextLurch <= 0) {
            turn += (random.nextBoolean() ? 1 : -1) * (0.12 + random.nextDouble() * 0.18);
            burst = 0.12 + random.nextDouble() * 0.1;
            nextLurch = 14 + random.nextInt(30);
        }
        Vec3d push = aim.multiply(0.02);
        // It won't come back for the one who cast it
        if (ownerId != null && sw.getEntity(ownerId) instanceof PlayerEntity owner) {
            Vec3d away = new Vec3d(getX() - owner.getX(), 0, getZ() - owner.getZ());
            double d = away.length();
            if (d < 5 && d > 1e-3) push = push.add(away.multiply(0.1 * (5 - d) / (5 * d)));
        }
        heading = rotate(heading, turn).add(push).normalize();
        burst *= 0.9;
        double speed = (CRUISE + 0.04 * Math.sin(age * 0.21 + phase) + burst) * Math.max(0.25, strength());

        Vec3d next = getPos().add(heading.multiply(speed));
        double ground = groundAt(sw, next.x, next.z, getY());
        if (!Double.isNaN(ground) && ground - getY() > STEP) {
            // A wall: knocked off it in a new direction, and it stays put this tick
            heading = rotate(heading.multiply(-1), (random.nextDouble() - 0.5) * 2.2);
            turn = (random.nextDouble() - 0.5) * 0.2;
            burst = 0.08;
            Vec3d c = getPos().add(heading.multiply(-0.6)).add(0, 1.2, 0);
            sw.spawnParticles(ParticleTypes.GUST, c.x, c.y, c.z, 1, 0, 0, 0, 0);
            sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_BREEZE_DEFLECT, SoundCategory.PLAYERS, 0.8f, 0.7f);
            return;
        }
        double y;
        if (Double.isNaN(ground)) {
            y = getY() - 0.4; // over a drop: it sinks down it
        } else {
            y = ground > getY() ? ground : getY() + (ground - getY()) * 0.5;
        }
        if (y < sw.getBottomY() - 2) {
            discard();
            return;
        }
        setPosition(next.x, y, next.z);
    }

    private static Vec3d rotate(Vec3d v, double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new Vec3d(v.x * c - v.z * s, 0, v.x * s + v.z * c);
    }

    /** Drags things in, spins them up the funnel and throws them out. */
    private void whirl(ServerWorld sw, Vec3d moved) {
        float str = strength();
        Box box = getBoundingBox().expand(PULL_RADIUS, 2.0, PULL_RADIUS).stretch(0, 2.0, 0);
        @Nullable Entity owner = ownerId != null ? sw.getEntity(ownerId) : null;
        List<LivingEntity> near = sw.getEntitiesByClass(LivingEntity.class, box, this::canAffect);

        thrown.values().removeIf(until -> until < age);
        Iterator<Map.Entry<Integer, Caught>> it = caught.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Caught> e = it.next();
            Entity ent = sw.getEntityById(e.getKey());
            if (!(ent instanceof LivingEntity le) || !near.contains(le)) it.remove();
        }

        for (LivingEntity e : near) {
            int id = e.getId();
            Vec3d rel = e.getPos().subtract(getX(), 0, getZ());
            double relY = e.getY() - getY();
            Vec3d axis = spine(Math.max(0, relY), age, getId(), lean);
            Vec3d flat = new Vec3d(rel.x - axis.x, 0, rel.z - axis.z);
            double d = flat.length();
            if (d > PULL_RADIUS || relY < -2 || relY > HEIGHT + 1.5) continue;
            Vec3d out = d > 1e-3 ? flat.multiply(1 / d) : new Vec3d(1, 0, 0);
            Vec3d around = new Vec3d(-out.z, 0, out.x); // anticlockwise from above
            double heavy = MathHelper.clamp(e.getAttributeValue(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE), 0, 1);
            double give = 1 - heavy * 0.75;

            if (thrown.containsKey(id)) continue; // flying free

            double funnel = radiusAt(Math.max(0, relY), age) * 0.9 + e.getWidth() * 0.5;
            Caught c = caught.get(id);
            if (c == null && d < funnel && str > 0.6f && heavy < 0.9 && relY < CARRY_HEIGHT * 0.8) {
                c = new Caught(14 + random.nextInt(18));
                caught.put(id, c);
                sw.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.ENTITY_BREEZE_JUMP, SoundCategory.PLAYERS, 0.8f, 0.6f + random.nextFloat() * 0.3f);
            }

            // Battered while it's in the funnel
            if (d < funnel && age >= nextHit.getOrDefault(id, 0)) {
                nextHit.put(id, age + 20);
                hurt(sw, e, owner, SPIN_DAMAGE);
            }

            if (c != null) {
                // Whirled round and up the funnel
                c.spun++;
                double ring = 0.7 + Math.max(0, relY) * 0.2;
                double up = relY < CARRY_HEIGHT ? 0.26 : 0.08 + 0.04 * Math.sin(age * 0.6);
                Vec3d v = moved
                        .add(around.multiply(0.55))
                        .add(out.multiply((ring - d) * 0.45));
                e.setVelocity(v.x, up, v.z);
                e.fallDistance = 0;
                e.velocityModified = true;
                if (e instanceof MobEntity) {
                    e.setYaw(e.getYaw() + 35f);
                    e.setHeadYaw(e.getYaw());
                    e.setBodyYaw(e.getYaw());
                }
                if (c.spun >= c.throwAt) {
                    caught.remove(id);
                    fling(sw, e, out, around, owner, 1.0);
                }
            } else {
                // Dragged in and swept round, harder the closer it gets
                double k = (1 - d / PULL_RADIUS) * str * give;
                Vec3d pull = out.multiply(-(0.07 * k + 0.012 * str)).add(around.multiply(0.12 * k));
                double lift = d < funnel ? 0.06 * str * give : 0;
                e.addVelocity(pull.x, lift, pull.z);
                e.velocityModified = true;
            }
        }
    }

    /** Throws {@code e} out of the funnel, sideways and up. */
    private void fling(ServerWorld sw, LivingEntity e, Vec3d out, Vec3d around, @Nullable Entity owner, double power) {
        double sideways = (1.05 + random.nextDouble() * 0.55) * power;
        Vec3d dir = around.multiply(0.7).add(out.multiply(0.7)).normalize().multiply(sideways);
        hurt(sw, e, owner, FLING_DAMAGE * (float) power);
        // After the hurt, so its knockback doesn't spoil the throw
        e.setVelocity(dir.x, (0.42 + random.nextDouble() * 0.28) * power, dir.z);
        e.fallDistance = 0;
        e.velocityModified = true;
        thrown.put(e.getId(), age + 25);
        Vec3d at = e.getBoundingBox().getCenter();
        sw.spawnParticles(ParticleTypes.GUST, at.x, at.y, at.z, 1, 0, 0, 0, 0);
        sw.spawnParticles(ParticleTypes.CLOUD, at.x, at.y, at.z, 8, 0.3, 0.3, 0.3, 0.12);
        sw.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_WIND_CHARGE_WIND_BURST.value(), SoundCategory.PLAYERS, 0.9f, 0.8f + random.nextFloat() * 0.3f);
    }

    private void hurt(ServerWorld sw, LivingEntity e, @Nullable Entity owner, float amount) {
        Vec3d v = e.getVelocity();
        DamageSource src = owner != null ? sw.getDamageSources().indirectMagic(this, owner) : sw.getDamageSources().magic();
        e.damage(src, amount);
        e.setVelocity(v); // no knockback from it; the wind does the pushing
    }

    /** Everything still in the funnel is thrown out as it starts to blow itself out. */
    private void releaseAll(ServerWorld sw) {
        @Nullable Entity owner = ownerId != null ? sw.getEntity(ownerId) : null;
        for (Integer id : new ArrayList<>(caught.keySet())) {
            if (sw.getEntityById(id) instanceof LivingEntity e && e.isAlive()) {
                Vec3d flat = new Vec3d(e.getX() - getX(), 0, e.getZ() - getZ());
                Vec3d out = flat.lengthSquared() > 1e-6 ? flat.normalize() : new Vec3d(1, 0, 0);
                fling(sw, e, out, new Vec3d(-out.z, 0, out.x), owner, 0.8);
            }
        }
        caught.clear();
    }

    private boolean canAffect(LivingEntity e) {
        if (!e.isAlive() || e.isSpectator() || e instanceof ArmorStandEntity) return false;
        if (e instanceof EnderDragonEntity || e instanceof WitherEntity) return false;
        if (e instanceof IllusionEntity || Bubbles.bubbleOf(e) != null || Crows.isHidden(e) || Phantom.isPhantom(e)) return false;
        if (e instanceof PlayerEntity p && (p.getAbilities().creativeMode || p.getAbilities().flying)) return false;
        if (e.hasVehicle()) return false;
        if (ownerId != null) {
            if (e.getUuid().equals(ownerId)) return false;
            if (e instanceof TameableEntity pet && ownerId.equals(pet.getOwnerUuid())) return false;
        }
        return true;
    }

    private void ambience(ServerWorld sw) {
        // Gusts now and then; the steady roar is a looping sound on each client (TornadoSound) that stops with it
        if (age % 24 == 0 && age < LIFE - FADE_TICKS) {
            sw.playSound(null, getX(), getY() + 2, getZ(), SoundEvents.ENTITY_BREEZE_WHIRL, SoundCategory.PLAYERS,
                    1.2f * strength() + 0.2f, 0.55f + random.nextFloat() * 0.2f);
        }
    }

    private void dissipate(ServerWorld sw) {
        Vec3d c = getPos().add(0, HEIGHT * 0.5, 0);
        sw.spawnParticles(ParticleTypes.CLOUD, c.x, c.y, c.z, 40, 0.9, HEIGHT * 0.3, 0.9, 0.12);
        sw.spawnParticles(ParticleTypes.POOF, getX(), getY() + 0.3, getZ(), 20, 0.8, 0.1, 0.8, 0.08);
        sw.spawnParticles(ParticleTypes.GUST_EMITTER_SMALL, c.x, c.y, c.z, 1, 0, 0, 0, 0);
        sw.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_WIND_CHARGE_WIND_BURST.value(), SoundCategory.PLAYERS, 1.0f, 0.45f);
        discard();
    }

    // ---------------------------------------------------------------------
    // Client: easing, debris and dust
    // ---------------------------------------------------------------------

    private void clientTick() {
        if (lerpTo != null) {
            setPosition(lerpTo);
            lerpTo = null;
        }
        World w = getWorld();
        Vec3d moving = getPos().subtract(prevX, prevY, prevZ);
        prevLean = lean;
        lean = lean.multiply(0.88).add(moving.x * 0.84, 0, moving.z * 0.84);

        if (isFading() && fadeStart < 0) fadeStart = age;
        float grow = MathHelper.clamp(age / (float) GROW_TICKS, 0f, 1f);
        float fade = fadeStart < 0 ? 1f : MathHelper.clamp(1f - (age - fadeStart) / (float) FADE_TICKS, 0f, 1f);
        prevShownScale = shownScale;
        shownScale += (grow * fade - shownScale) * 0.3f;

        // What it's standing on, for the colour of the dust and the debris
        BlockPos under = BlockPos.ofFloored(getX(), getY() - 0.05, getZ());
        BlockState ground = w.getBlockState(under);
        overWater = ground.getFluidState().isIn(FluidTags.WATER) || w.getFluidState(under.up()).isIn(FluidTags.WATER);
        if (overWater) {
            groundColor = 0x5f86c9;
        } else if (!ground.isAir()) {
            MapColor mc = ground.getMapColor(w, under);
            if (mc != MapColor.CLEAR && mc.color != 0) groundColor = mc.color;
            BlockState below = w.getBlockState(under.down());
            MapColor soil = below.isAir() ? MapColor.CLEAR : below.getMapColor(w, under.down());
            soilColor = soil != MapColor.CLEAR && soil.color != 0 ? soil.color : groundColor;
            if (ground.getRenderType() == net.minecraft.block.BlockRenderType.MODEL) groundState = ground;
            soilState = below.getRenderType() == net.minecraft.block.BlockRenderType.MODEL ? below : groundState;
        }
        if (overWater) soilColor = groundColor;

        tickDebris();
        dustParticles(ground, under);
    }

    private void tickDebris() {
        float s = shownScale;
        if (s > 0.3f && !isFading() && debris.size() < 46 && random.nextFloat() < 0.75f) {
            float shade = 0.7f + random.nextFloat() * 0.45f;
            int c = random.nextInt(3) == 0 ? groundColor : soilColor;
            float r = ((c >> 16) & 255) / 255f * shade, g = ((c >> 8) & 255) / 255f * shade, b = (c & 255) / 255f * shade;
            // Now and then a darker clod or a pale leaf-coloured fleck, so it's not all one colour
            if (random.nextInt(5) == 0) {
                r *= 0.6f;
                g *= 0.6f;
                b *= 0.6f;
            }
            Debris d = new Debris(random.nextDouble() * Math.PI * 2, 0.32 + random.nextDouble() * 0.25,
                    0.05 + random.nextDouble() * 0.07, 0.75 + random.nextDouble() * 0.5,
                    HEIGHT * (0.7 + random.nextDouble() * 0.3), (2 + random.nextInt(3)) / 16f, Math.min(r, 1f), Math.min(g, 1f), Math.min(b, 1f),
                    (random.nextFloat() - 0.5f) * 0.9f);
            d.h = random.nextDouble() * 0.4;
            // Most of it is chunks of the soil; now and then a glinting shard of glass pane
            if (!overWater && random.nextInt(3) != 0) d.state = soilState;
            if (random.nextInt(9) == 0) {
                d.pane = true;
                d.state = null;
            }
            d.pos = d.prev = place(d);
            debris.add(d);
        }
        Iterator<Debris> it = debris.iterator();
        while (it.hasNext()) {
            Debris d = it.next();
            d.prev = d.pos;
            d.prevSpin = d.spin;
            d.spin += d.spinSpeed;
            d.age++;
            if (!d.flung) {
                d.angle += d.angSpeed;
                d.h += d.climb;
                d.pos = place(d);
                if (d.h > d.top || (isFading() && random.nextInt(4) == 0)) {
                    d.flung = true;
                    d.age = 0;
                    Vec3d out = new Vec3d(Math.cos(d.angle), 0, Math.sin(d.angle));
                    Vec3d around = new Vec3d(-Math.sin(d.angle), 0, Math.cos(d.angle));
                    d.vel = around.multiply(0.3).add(out.multiply(0.18)).add(0, 0.12, 0);
                }
            } else {
                d.pos = d.pos.add(d.vel);
                d.vel = d.vel.multiply(0.94).add(0, -0.045, 0);
                if (d.age > 22) it.remove();
            }
        }
    }

    private Vec3d place(Debris d) {
        double h = d.h * shownScale;
        Vec3d axis = spine(h, age, getId(), lean);
        double r = radiusAt(h, age) * d.radius * shownScale;
        return getPos().add(axis.x + Math.cos(d.angle) * r, h, axis.z + Math.sin(d.angle) * r);
    }

    private void dustParticles(BlockState ground, BlockPos under) {
        World w = getWorld();
        float s = shownScale;
        if (s < 0.2f) return;
        // Dust (or spray) kicked up and swept round the foot
        for (int i = 0; i < 2; i++) {
            double a = random.nextDouble() * Math.PI * 2;
            double r = 0.4 + random.nextDouble() * 1.4 * s;
            double x = getX() + Math.cos(a) * r, z = getZ() + Math.sin(a) * r;
            double vx = -Math.sin(a) * 0.22 + Math.cos(a) * 0.05, vz = Math.cos(a) * 0.22 + Math.sin(a) * 0.05;
            if (overWater) {
                w.addParticle(ParticleTypes.SPLASH, x, getY() + 0.1, z, vx, 0.25, vz);
                if (random.nextBoolean()) w.addParticle(ParticleTypes.BUBBLE_POP, x, getY() + 0.15, z, vx * 0.5, 0.05, vz * 0.5);
            } else if (!ground.isAir() && ground.getRenderType() != net.minecraft.block.BlockRenderType.INVISIBLE) {
                w.addParticle(new BlockStateParticleEffect(ParticleTypes.BLOCK, ground), x, getY() + 0.1, z, vx * 3, 0.4, vz * 3);
            }
        }
        if (random.nextInt(5) == 0) {
            double a = random.nextDouble() * Math.PI * 2;
            double r = 0.6 + random.nextDouble() * 0.9 * s;
            w.addParticle(ParticleTypes.POOF, getX() + Math.cos(a) * r, getY() + 0.15, getZ() + Math.sin(a) * r,
                    -Math.sin(a) * 0.12, 0.02, Math.cos(a) * 0.12);
        }
        // Wisps thrown off the rim at the top
        if (random.nextInt(7) == 0) {
            double a = random.nextDouble() * Math.PI * 2;
            double h = HEIGHT * s * (0.8 + random.nextDouble() * 0.2);
            Vec3d axis = spine(h, age, getId(), lean);
            double r = radiusAt(h, age) * s;
            w.addParticle(ParticleTypes.CLOUD, getX() + axis.x + Math.cos(a) * r, getY() + h, getZ() + axis.z + Math.sin(a) * r,
                    -Math.sin(a) * 0.12 + Math.cos(a) * 0.05, 0.01, Math.cos(a) * 0.12 + Math.sin(a) * 0.05);
        }
    }
}
