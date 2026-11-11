package net.ragnar.ragnarsmagicmod.geysir;

import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.bubbles.Bubbles;
import net.ragnar.ragnarsmagicmod.crows.Crows;
import net.ragnar.ragnarsmagicmod.entity.IllusionEntity;
import net.ragnar.ragnarsmagicmod.phantom.Phantom;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * One geysir. It lives {@link #LIFE} ticks, standing on the ground where a creature was when the tome was cast:
 * <ol>
 *   <li>{@link #WARN} ticks of warning: the ground shakes and water seeps up;</li>
 *   <li>then it erupts - everything over it is thrown up, hardest dead centre, lower and outward toward the edge of
 *       {@link #REACH} - and the column shoots up {@link #MAX_HEIGHT} blocks over {@link #RISE} ticks;</li>
 *   <li>it stands roaring for {@link #HOLD} ticks, then falls back over {@link #FALL} ticks.</li>
 * </ol>
 * The server does the throwing and the sounds; each client draws it (GeysirRenderer) and throws its own spray, all from
 * its age, so nothing but its spawn is ever sent.
 */
public class GeysirEntity extends Entity {
    public static final int WARN = 20, RISE = 5, HOLD = 12, FALL = 14;
    public static final int LIFE = WARN + RISE + HOLD + FALL;
    /** How tall the column stands. */
    public static final double MAX_HEIGHT = 8.0;
    /** Anything this close to the middle (horizontally) is thrown; dead centre is within {@link #CENTRE}. */
    public static final double REACH = 2.6, CENTRE = 0.7;
    /** How high it throws, dead centre and at the very edge, in blocks. */
    public static final double DIRECT_HEIGHT = 15.0, EDGE_HEIGHT = 3.5;
    /** And how much it hurts, dead centre and at the edge. */
    public static final float DIRECT_DAMAGE = 4f, EDGE_DAMAGE = 1.5f;

    @Nullable private UUID ownerId;
    /** Whether this one makes its own sounds (only the first few of a cast do, so a crowd isn't deafening). */
    private boolean loud = true;

    public GeysirEntity(EntityType<? extends GeysirEntity> type, World world) {
        super(type, world);
        setNoGravity(true);
        noClip = true;
    }

    /** Puts a geysir on the ground at {@code at}, cast by {@code owner}. */
    public static GeysirEntity erupt(ServerWorld world, Vec3d at, PlayerEntity owner, boolean loud) {
        GeysirEntity g = new GeysirEntity(Geysir.GEYSIR, world);
        g.ownerId = owner.getUuid();
        g.loud = loud;
        g.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
        world.spawnEntity(g);
        return g;
    }

    /** Whether a geysir cast by {@code ownerId} throws {@code e}: never the caster or their pets, nor bosses or ghosts. */
    public static boolean canLaunch(Entity e, @Nullable UUID ownerId) {
        if (!(e instanceof LivingEntity le) || !le.isAlive() || le.isSpectator() || le instanceof ArmorStandEntity) return false;
        if (le instanceof EnderDragonEntity || le instanceof WitherEntity) return false;
        if (le instanceof IllusionEntity || Bubbles.bubbleOf(le) != null || Crows.isHidden(le) || Phantom.isPhantom(le)) return false;
        if (le instanceof PlayerEntity p && (p.getAbilities().creativeMode || p.getAbilities().flying)) return false;
        if (le.hasVehicle()) return false;
        if (ownerId != null) {
            if (le.getUuid().equals(ownerId)) return false;
            if (le instanceof TameableEntity pet && ownerId.equals(pet.getOwnerUuid())) return false;
        }
        return true;
    }

    // ---------------------------------------------------------------------
    // The shape, shared by the renderer and the spray
    // ---------------------------------------------------------------------

    /** How tall the column is {@code t} ticks into its life (0 before it erupts). */
    public static double heightAt(double t) {
        double e = t - WARN;
        if (e <= 0) return 0;
        if (e < RISE) {
            double k = e / RISE;
            return MAX_HEIGHT * (1 - Math.pow(1 - k, 3)) * 1.06; // shoots up, overshooting a touch
        }
        if (e < RISE + HOLD) {
            double k = (e - RISE) / HOLD;
            return MAX_HEIGHT * (1.06 - 0.06 * k + 0.04 * Math.sin(e * 1.7)); // settles, surging
        }
        double k = MathHelper.clamp((e - RISE - HOLD) / FALL, 0, 1);
        return MAX_HEIGHT * (1 - k) * (1 - k);
    }

    /** How strongly the ground is shaking {@code t} ticks in: rising through the warning, then gone. */
    public static double shakeAt(double t) {
        return t < WARN ? Math.pow(t / WARN, 1.5) : Math.max(0, 1 - (t - WARN) / 4.0);
    }

    // ---------------------------------------------------------------------
    // Ticking
    // ---------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (getWorld().isClient) {
            GeysirSpray.tick(this);
            return;
        }
        ServerWorld sw = (ServerWorld) getWorld();
        if (age >= LIFE) {
            discard();
            return;
        }
        if (loud) sounds(sw);
        if (age == WARN) launch(sw);
    }

    private void sounds(ServerWorld sw) {
        double x = getX(), y = getY(), z = getZ();
        Random r = random;
        if (age == 1) {
            // A rumble underfoot and the gurgle of water forcing its way up
            sw.playSound(null, x, y, z, SoundEvents.BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT, SoundCategory.PLAYERS, 1.2f, 0.6f);
            sw.playSound(null, x, y, z, SoundEvents.ENTITY_WARDEN_DIG, SoundCategory.PLAYERS, 0.5f, 1.3f);
        }
        if (age > 1 && age < WARN && age % 5 == 0) {
            sw.playSound(null, x, y, z, SoundEvents.BLOCK_POINTED_DRIPSTONE_DRIP_WATER, SoundCategory.PLAYERS, 0.8f, 0.6f + r.nextFloat() * 0.4f);
        }
        if (age == WARN) {
            // The burst: a deep splash, the hiss of steam and the rush of water going up
            sw.playSound(null, x, y, z, SoundEvents.ENTITY_GENERIC_SPLASH, SoundCategory.PLAYERS, 1.6f, 0.55f);
            sw.playSound(null, x, y, z, SoundEvents.ENTITY_PLAYER_SPLASH_HIGH_SPEED, SoundCategory.PLAYERS, 1.2f, 0.7f);
            sw.playSound(null, x, y, z, SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.PLAYERS, 0.6f, 0.55f);
            sw.playSound(null, x, y, z, SoundEvents.ENTITY_WIND_CHARGE_WIND_BURST.value(), SoundCategory.PLAYERS, 0.7f, 0.5f);
        }
        if (age > WARN && age < WARN + RISE + HOLD && age % 6 == 0) {
            sw.playSound(null, x, y + 3, z, SoundEvents.BLOCK_WATER_AMBIENT, SoundCategory.PLAYERS, 1.0f, 0.8f + r.nextFloat() * 0.3f);
        }
        if (age == WARN + RISE + HOLD + 4) {
            // The water crashing back down
            sw.playSound(null, x, y, z, SoundEvents.ENTITY_GENERIC_SPLASH, SoundCategory.PLAYERS, 1.0f, 0.9f);
        }
    }

    /**
     * Throws everything standing over it. Dead centre goes straight up {@link #DIRECT_HEIGHT} blocks; further out it's
     * lower and thrown away from the middle, down to {@link #EDGE_HEIGHT} at {@link #REACH}. Where two geysirs overlap,
     * the stronger throw wins. Public so tests can call it.
     */
    public void launch(ServerWorld sw) {
        Vec3d c = getPos();
        Box box = new Box(c.x - REACH, c.y - 1.0, c.z - REACH, c.x + REACH, c.y + 3.0, c.z + REACH);
        PlayerEntity owner = ownerId == null ? null : sw.getPlayerByUuid(ownerId);
        DamageSource source = owner != null ? sw.getDamageSources().indirectMagic(this, owner) : sw.getDamageSources().magic();
        for (LivingEntity e : sw.getEntitiesByClass(LivingEntity.class, box, e -> canLaunch(e, ownerId))) {
            double dx = e.getX() - c.x, dz = e.getZ() - c.z;
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d > REACH) continue;
            double k = MathHelper.clamp(1 - (d - CENTRE) / (REACH - CENTRE), 0, 1); // 1 dead centre, 0 at the edge
            double vy = velocityFor(MathHelper.lerp(k * k, EDGE_HEIGHT, DIRECT_HEIGHT));
            if (e.getVelocity().y >= vy - 1e-3) continue; // a stronger geysir already has it
            Vec3d out = d < 1e-3 ? Vec3d.ZERO : new Vec3d(dx / d, 0, dz / d);
            double side = (1 - k) * 0.55;

            e.timeUntilRegen = 0;
            e.damage(source, MathHelper.lerp((float) k, EDGE_DAMAGE, DIRECT_DAMAGE));
            e.extinguish();
            e.setVelocity(out.x * side, vy, out.z * side);
            e.fallDistance = 0f;
            e.setOnGround(false);
            e.velocityDirty = true;
            e.velocityModified = true;
        }
        // The ground bursting open, for everyone (the column and spray are drawn on each client)
        BlockState ground = getWorld().getBlockState(getBlockPos().down());
        if (!ground.isAir()) {
            sw.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, ground), c.x, c.y + 0.1, c.z, 30, 0.5, 0.1, 0.5, 0.3);
        }
    }

    /** The upward speed that carries something {@code height} blocks up (vanilla's gravity and drag). */
    public static double velocityFor(double height) {
        double lo = 0, hi = 4;
        for (int i = 0; i < 40; i++) {
            double mid = (lo + hi) / 2;
            if (riseFrom(mid) < height) lo = mid;
            else hi = mid;
        }
        return hi;
    }

    /** How high {@code vy} carries something, in blocks. */
    public static double riseFrom(double vy) {
        double y = 0, top = 0;
        for (int i = 0; i < 300 && vy > 0; i++) {
            y += vy;
            top = Math.max(top, y);
            vy = (vy - 0.08) * 0.98;
        }
        return top;
    }

    // ---------------------------------------------------------------------
    // Entity plumbing
    // ---------------------------------------------------------------------

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {}

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
        return getBoundingBox().expand(2.5, MAX_HEIGHT + 2, 2.5);
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
    protected void readCustomDataFromNbt(NbtCompound nbt) {}

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {}
}
