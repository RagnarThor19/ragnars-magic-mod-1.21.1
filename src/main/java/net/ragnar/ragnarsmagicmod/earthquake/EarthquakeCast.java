package net.ragnar.ragnarsmagicmod.earthquake;

import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.entity.IllusionEntity;
import net.ragnar.ragnarsmagicmod.network.ShakePayload;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * One casting of Earthquake, run by the server:
 * <ol>
 *   <li><b>Charge</b> ({@link #CHARGE_TICKS}): the caster is rooted to the spot while the ground rumbles harder and
 *       harder around them.</li>
 *   <li><b>Shock</b> ({@link #WAVE_TICKS}): a wave rolls out through the ground to {@link #RADIUS} blocks. Everything
 *       it passes under (within {@link #REACH_Y} blocks up or down, so it works in caves and on hillsides) takes
 *       between {@link #EDGE_DAMAGE} and {@link #CENTER_DAMAGE}, hardest near the middle, and is flung up and away.
 *       Dropped items get tossed too.</li>
 *   <li><b>Aftershocks</b>: two smaller jolts at {@link #AFTERSHOCKS} that knock anything standing on the ground
 *       off its feet for a little more.</li>
 * </ol>
 * It never hurts the caster, their pets or their teammates, and never changes a block. Everything it hits is found
 * once, when the shock goes off, so the wave itself costs almost nothing per tick.
 */
public final class EarthquakeCast {
    public static final int CHARGE_TICKS = 60;
    public static final int WAVE_TICKS = 20;
    public static final double RADIUS = 25;
    public static final double REACH_Y = 12;
    public static final float CENTER_DAMAGE = 26f, EDGE_DAMAGE = 10f;
    /** Ticks after the shock that each aftershock hits, and how hard. */
    public static final int[] AFTERSHOCKS = {24, 44};
    private static final float[] AFTERSHOCK_DAMAGE = {4f, 3f};
    private static final double[] AFTERSHOCK_HOP = {0.45, 0.32};
    public static final int TOTAL_TICKS = CHARGE_TICKS + 60;

    private final ServerWorld world;
    private final UUID casterId;
    private final int casterNetId;
    public final Vec3d center;
    private int age;
    private boolean done;
    /** Everything the wave will reach, nearest first, and how far through that list it's got. */
    private final List<Entity> targets = new ArrayList<>();
    private int next;

    private static final List<EarthquakeCast> ACTIVE = new ArrayList<>();

    private EarthquakeCast(ServerWorld world, ServerPlayerEntity caster) {
        this.world = world;
        this.casterId = caster.getUuid();
        this.casterNetId = caster.getId();
        this.center = caster.getPos();
    }

    public static boolean isCasting(PlayerEntity player) {
        return of(player) != null;
    }

    /** The cast {@code player} is in the middle of, if any. */
    @Nullable
    public static EarthquakeCast of(PlayerEntity player) {
        for (EarthquakeCast c : ACTIVE) if (!c.done && c.casterId.equals(player.getUuid())) return c;
        return null;
    }

    public static EarthquakeCast begin(ServerPlayerEntity caster) {
        ServerWorld world = caster.getServerWorld();
        EarthquakeCast cast = new EarthquakeCast(world, caster);
        ACTIVE.add(cast);
        Earthquake.sendNear(world, cast.center, new Earthquake.ChargePayload(caster.getId(), cast.center.toVector3f(), world.random.nextLong()));
        Vec3d c = cast.center;
        caster.swingHand(Hand.MAIN_HAND, true);
        // The staff driven into the ground, and the earth groaning in answer
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ITEM_MACE_SMASH_GROUND, SoundCategory.PLAYERS, 2f, 0.5f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_WARDEN_DIG, SoundCategory.PLAYERS, 3f, 0.6f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_DEEPSLATE_BREAK, SoundCategory.PLAYERS, 2f, 0.5f);
        return cast;
    }

    // ---------------------------------------------------------------------
    // Ticking
    // ---------------------------------------------------------------------

    static void tickAll(ServerWorld world) {
        if (ACTIVE.isEmpty()) return;
        Iterator<EarthquakeCast> it = ACTIVE.iterator();
        while (it.hasNext()) {
            EarthquakeCast c = it.next();
            if (c.world != world) continue;
            if (!c.tick()) it.remove();
        }
    }

    /** Runs one tick. False once it's over. Public so tests can run it by hand. */
    public boolean tick() {
        if (done) return false;
        ServerPlayerEntity caster = world.getServer().getPlayerManager().getPlayer(casterId);
        boolean casterHere = caster != null && caster.isAlive() && caster.getServerWorld() == world;
        age++;
        if (age <= CHARGE_TICKS) {
            // Cut short if the caster isn't around to finish it
            if (!casterHere) {
                Earthquake.sendNear(world, center, new Earthquake.ShockPayload(casterNetId, -1));
                done = true;
                return false;
            }
            charge(caster);
            if (age == CHARGE_TICKS) shock(caster);
            return true;
        }
        int t = age - CHARGE_TICKS;
        if (t <= WAVE_TICKS + 1) wave(t, casterHere ? caster : null);
        for (int i = 0; i < AFTERSHOCKS.length; i++) {
            if (t == AFTERSHOCKS[i]) aftershock(i, casterHere ? caster : null);
        }
        if (age >= TOTAL_TICKS) {
            done = true;
            return false;
        }
        return true;
    }

    public int age() {
        return age;
    }

    /** How far the wave has rolled {@code t} ticks after the shock: off like a shot, slowing toward the edge. */
    public static double radiusAt(double t) {
        double p = MathHelper.clamp(t / WAVE_TICKS, 0, 1);
        return RADIUS * (1 - (1 - p) * (1 - p));
    }

    private void charge(ServerPlayerEntity caster) {
        // Rooted: no walking off, though they can still look around
        caster.setVelocity(0, Math.min(0, caster.getVelocity().y), 0);
        caster.velocityModified = true;

        float k = age / (float) CHARGE_TICKS;
        Vec3d c = center;
        if (age % 5 == 0) {
            world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_DEEPSLATE_BREAK, SoundCategory.PLAYERS, 1.2f + 1.5f * k, 0.45f + world.random.nextFloat() * 0.25f);
        }
        if (age == 28) world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_WARDEN_DIG, SoundCategory.PLAYERS, 3.5f, 0.5f);
        if (age == CHARGE_TICKS - 14) world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_WARDEN_EMERGE, SoundCategory.PLAYERS, 2.5f, 1.2f);
        // The ground trembles harder and harder
        if (age % 4 == 0) ShakePayload.around(world, c, RADIUS, 0.04f + 0.4f * k * k, 6);
    }

    // ---------------------------------------------------------------------
    // The shock
    // ---------------------------------------------------------------------

    private void shock(ServerPlayerEntity caster) {
        caster.swingHand(Hand.MAIN_HAND, true);
        Earthquake.sendNear(world, center, new Earthquake.ShockPayload(casterNetId, 0));
        Vec3d c = center;
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ITEM_MACE_SMASH_GROUND_HEAVY, SoundCategory.PLAYERS, 4f, 0.5f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 4f, 0.45f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.PLAYERS, 3f, 0.4f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_RAVAGER_STUNNED, SoundCategory.PLAYERS, 2f, 0.5f);
        ShakePayload.around(world, c, RADIUS, 1.4f, 36);

        // Everything the wave will reach, found once, nearest first
        Box area = new Box(c, c).expand(RADIUS + 1, REACH_Y, RADIUS + 1);
        for (Entity e : world.getOtherEntities(caster, area, e -> canShake(e, caster))) {
            if (flat(e.getPos()) <= RADIUS) targets.add(e);
        }
        targets.sort(Comparator.comparingDouble(e -> flat(e.getPos())));
    }

    /** Hits everything the wave rolled under this tick. */
    private void wave(int t, @Nullable ServerPlayerEntity caster) {
        double radius = radiusAt(t);
        int thuds = 0;
        while (next < targets.size()) {
            Entity e = targets.get(next);
            double d = flat(e.getPos());
            if (d > radius) break;
            next++;
            if (e.isRemoved() || !e.isAlive()) continue;
            double k = 1 - d / RADIUS; // 1 at the middle, 0 at the edge
            Vec3d out = horizontalOut(e.getPos());
            if (e instanceof LivingEntity le) {
                le.timeUntilRegen = 0;
                float dmg = MathHelper.lerp((float) k, EDGE_DAMAGE, CENTER_DAMAGE);
                le.damage(caster != null ? world.getDamageSources().playerAttack(caster) : world.getDamageSources().magic(), dmg);
                launch(le, out.multiply(0.3 + 0.35 * k).add(0, 0.75 + 0.55 * k, 0));
            } else {
                launch(e, out.multiply(0.15).add(0, 0.35 + 0.3 * world.random.nextDouble(), 0));
            }
            // The ground bursting up under its feet
            BlockState under = world.getBlockState(e.getBlockPos().down());
            if (!under.isAir()) {
                world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, under), e.getX(), e.getY() + 0.1, e.getZ(),
                        18, e.getWidth() * 0.6, 0.1, e.getWidth() * 0.6, 0.25);
            }
            if (e instanceof LivingEntity && thuds++ < 6) {
                world.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.ITEM_MACE_SMASH_GROUND, SoundCategory.PLAYERS, 0.9f, 0.6f + world.random.nextFloat() * 0.2f);
            }
        }
    }

    private void aftershock(int i, @Nullable ServerPlayerEntity caster) {
        Earthquake.sendNear(world, center, new Earthquake.ShockPayload(casterNetId, i + 1));
        Vec3d c = center;
        float vol = i == 0 ? 2.5f : 1.8f;
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ITEM_MACE_SMASH_GROUND_HEAVY, SoundCategory.PLAYERS, vol, 0.45f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_DEEPSLATE_BREAK, SoundCategory.PLAYERS, vol, 0.4f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_WARDEN_DIG, SoundCategory.PLAYERS, vol, 0.7f);
        ShakePayload.around(world, c, RADIUS, i == 0 ? 0.6f : 0.4f, 16);

        Box area = new Box(c, c).expand(RADIUS + 1, REACH_Y, RADIUS + 1);
        for (Entity e : world.getOtherEntities(caster, area, e -> canShake(e, caster) && e.isOnGround())) {
            double d = flat(e.getPos());
            if (d > RADIUS) continue;
            if (e instanceof LivingEntity le) {
                le.timeUntilRegen = 0;
                le.damage(caster != null ? world.getDamageSources().playerAttack(caster) : world.getDamageSources().magic(), AFTERSHOCK_DAMAGE[i]);
            }
            launch(e, horizontalOut(e.getPos()).multiply(0.1).add(0, AFTERSHOCK_HOP[i], 0));
        }
    }

    private static void launch(Entity e, Vec3d vel) {
        e.setVelocity(e.getVelocity().multiply(0.3).add(vel));
        e.velocityModified = true;
        e.setOnGround(false);
    }

    private boolean canShake(Entity e, @Nullable PlayerEntity caster) {
        if (e instanceof ItemEntity) return true;
        if (!(e instanceof LivingEntity le) || !le.isAlive() || le.isSpectator() || e instanceof ArmorStandEntity || e instanceof IllusionEntity) return false;
        if (Math.abs(e.getY() - center.y) > REACH_Y) return false;
        if (e.getUuid().equals(casterId)) return false;
        if (e instanceof TameableEntity pet && casterId.equals(pet.getOwnerUuid())) return false;
        if (e instanceof PlayerEntity p && p.getAbilities().creativeMode) return false;
        return caster == null || !e.isTeammate(caster);
    }

    private double flat(Vec3d p) {
        double dx = p.x - center.x, dz = p.z - center.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private Vec3d horizontalOut(Vec3d p) {
        Vec3d out = new Vec3d(p.x - center.x, 0, p.z - center.z);
        return out.lengthSquared() < 1e-4 ? new Vec3d(world.random.nextDouble() - 0.5, 0, world.random.nextDouble() - 0.5).normalize() : out.normalize();
    }

    // ---------------------------------------------------------------------
    // Leaving and stopping
    // ---------------------------------------------------------------------

    static void abandon(ServerPlayerEntity player) {
        for (EarthquakeCast c : ACTIVE) {
            // A quake that has already gone off carries on without them; one still charging fizzles
            if (c.casterId.equals(player.getUuid()) && c.age < CHARGE_TICKS) {
                Earthquake.sendNear(c.world, c.center, new Earthquake.ShockPayload(c.casterNetId, -1));
                c.done = true;
            }
        }
        ACTIVE.removeIf(c -> c.done);
    }

    static void abandonAll() {
        ACTIVE.clear();
    }
}
