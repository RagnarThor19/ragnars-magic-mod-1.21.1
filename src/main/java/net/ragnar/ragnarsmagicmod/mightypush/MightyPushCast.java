package net.ragnar.ragnarsmagicmod.mightypush;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.ragnar.ragnarsmagicmod.entity.IllusionEntity;
import net.ragnar.ragnarsmagicmod.network.ShakePayload;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One casting of Mighty Push, run by the server:
 * <ol>
 *   <li><b>Charge</b> ({@link #CHARGE_TICKS}): an airborne caster rises {@link #RISE} blocks (less under a ceiling)
 *       and hangs there; a grounded one plants their feet. Either way they can't move while it charges, and the
 *       ground trembles harder and harder.</li>
 *   <li><b>Push</b> ({@link #PUSH_TICKS}): a barrier swells out of them to {@link #MAX_RADIUS}. Anything it reaches
 *       takes {@link #HIT_DAMAGE} and is carried outward on its face - items, arrows and TNT too. Anything that can't
 *       be carried (pinned against a wall, or the ground beneath an airborne caster) is crushed for
 *       {@link #CRUSH_DAMAGE}.</li>
 *   <li><b>Settle</b> ({@link #SETTLE_TICKS}): an airborne caster hangs a moment longer, then drifts down under Slow
 *       Falling.</li>
 * </ol>
 * It never hurts the caster, their pets or their teammates, and never changes a block.
 */
public final class MightyPushCast {
    public static final int CHARGE_TICKS = 70;
    public static final int PUSH_TICKS = 50;
    public static final int SETTLE_TICKS = 12;
    public static final double RISE = 20;
    public static final int RISE_TICKS = 24;
    public static final double MAX_RADIUS = 30;
    public static final float HIT_DAMAGE = 12f;
    public static final float CRUSH_DAMAGE = 34f;
    /** How many ticks something has to be stuck behind the barrier's face before it counts as crushed. */
    private static final int STUCK_TICKS = 3, PLAYER_STUCK_TICKS = 7;

    /** How something caught by the barrier is getting on. */
    private static final class Caught {
        double lastDist = -1;
        int stuck;
        boolean crushed;
    }

    private final ServerWorld world;
    private final UUID casterId;
    private final boolean airborne;
    private final double hoverY;
    private int age;
    private boolean done;
    @Nullable private Vec3d center;
    private final Map<UUID, Caught> caught = new HashMap<>();

    private static final List<MightyPushCast> ACTIVE = new ArrayList<>();

    private MightyPushCast(ServerWorld world, ServerPlayerEntity caster, boolean airborne) {
        this.world = world;
        this.casterId = caster.getUuid();
        this.airborne = airborne;
        this.hoverY = airborne ? riseTo(world, caster) : caster.getY();
    }

    public static boolean isCasting(PlayerEntity player) {
        return of(player) != null;
    }

    /** Starts the cast. Public so tests can choose whether it begins in the air. */
    public static void begin(ServerPlayerEntity caster, boolean airborne) {
        ServerWorld world = caster.getServerWorld();
        MightyPushCast cast = new MightyPushCast(world, caster, airborne);
        ACTIVE.add(cast);
        caster.setVelocity(Vec3d.ZERO);
        caster.velocityModified = true;
        if (airborne) caster.setNoGravity(true);
        MightyPush.sendNear(world, caster.getPos(), new MightyPush.ChargePayload(caster.getId(), airborne));
        Vec3d c = caster.getPos();
        world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 3f, 0.5f);
        // The charge-up: a nether portal's rising roar, which builds for about as long as the chant takes
        world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_PORTAL_TRIGGER, SoundCategory.PLAYERS, 3f, 0.95f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_EVOKER_PREPARE_WOLOLO, SoundCategory.PLAYERS, 1.5f, 0.5f);
        if (airborne) world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_BREEZE_JUMP, SoundCategory.PLAYERS, 2f, 0.6f);
    }

    /** How high an airborne caster rises: {@link #RISE} blocks, or less if something's overhead. */
    private static double riseTo(ServerWorld world, ServerPlayerEntity caster) {
        Vec3d head = caster.getPos().add(0, caster.getHeight(), 0);
        BlockHitResult roof = world.raycast(new RaycastContext(head, head.add(0, RISE, 0),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, caster));
        double top = roof.getType() == HitResult.Type.MISS ? caster.getY() + RISE : roof.getPos().y - caster.getHeight() - 0.5;
        return Math.max(caster.getY(), top);
    }

    // ---------------------------------------------------------------------
    // Ticking
    // ---------------------------------------------------------------------

    static void tickAll(ServerWorld world) {
        if (ACTIVE.isEmpty()) return;
        Iterator<MightyPushCast> it = ACTIVE.iterator();
        while (it.hasNext()) {
            MightyPushCast c = it.next();
            if (c.world != world) continue;
            if (!c.tick()) it.remove();
        }
    }

    /** Runs one tick. False once it's over. Public so tests can run it by hand. */
    public boolean tick() {
        if (done) return false;
        ServerPlayerEntity caster = world.getServer().getPlayerManager().getPlayer(casterId);
        if (caster == null || !caster.isAlive() || caster.getServerWorld() != world) {
            if (caster != null) release(caster);
            done = true;
            return false;
        }
        age++;
        caster.fallDistance = 0;
        if (age <= CHARGE_TICKS) charge(caster);
        else if (age <= CHARGE_TICKS + PUSH_TICKS) push(caster, age - CHARGE_TICKS);
        else if (age <= CHARGE_TICKS + PUSH_TICKS + SETTLE_TICKS) hold(caster);
        else {
            release(caster);
            done = true;
            return false;
        }
        return true;
    }

    /** Keeps the caster where they are: hanging in the air, or rooted to the ground. */
    private void hold(ServerPlayerEntity caster) {
        if (airborne) {
            double vy = MathHelper.clamp((hoverY - caster.getY()) * 0.25, -0.5, 1.6);
            caster.setVelocity(0, vy, 0);
            caster.setNoGravity(true);
        } else {
            caster.setVelocity(0, Math.min(0, caster.getVelocity().y), 0);
        }
        caster.velocityModified = true;
    }

    private void charge(ServerPlayerEntity caster) {
        if (airborne && age <= RISE_TICKS) {
            // Up into the sky, slowing as it gets there
            double vy = MathHelper.clamp((hoverY - caster.getY()) * 0.18, 0, 1.6);
            caster.setVelocity(0, vy, 0);
            caster.velocityModified = true;
            if (age % 3 == 0) world.spawnParticles(ParticleTypes.CLOUD, caster.getX(), caster.getY() - 0.5, caster.getZ(), 2, 0.2, 0.1, 0.2, 0.01);
        } else {
            hold(caster);
        }
        Vec3d c = caster.getPos();
        float k = age / (float) CHARGE_TICKS;
        // A slow heartbeat, getting faster
        int beat = age < 35 ? 16 : age < 55 ? 11 : 7;
        if (age % beat == 0) world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 2.5f, 0.6f + 0.3f * k);
        // On each beat the caster draws the air in, deeper each time
        for (int beatTick : PULL_TICKS) {
            if (age == beatTick) world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_BREEZE_INHALE, SoundCategory.PLAYERS, 2.5f, 0.75f - 0.1f * k);
        }
        // And an ominous surge just before it goes
        if (age == CHARGE_TICKS - 16) world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_TRIAL_SPAWNER_OMINOUS_ACTIVATE, SoundCategory.PLAYERS, 3f, 0.6f);
        // The ground trembles harder and harder
        if (age % 4 == 0) ShakePayload.around(world, c, 24, 0.05f + 0.3f * k * k, 6);
    }

    /** When the caster draws the air in, in ticks from the start. */
    private static final int[] PULL_TICKS = {8, 22, 36, 50};

    // ---------------------------------------------------------------------
    // The push
    // ---------------------------------------------------------------------

    /** How far the barrier has reached {@code t} ticks into the push: quick off the mark, slowing as it goes. */
    public static double radiusAt(double t) {
        double p = MathHelper.clamp(t / PUSH_TICKS, 0, 1);
        return MAX_RADIUS * (1 - Math.pow(1 - p, 1.5));
    }

    private void push(ServerPlayerEntity caster, int t) {
        hold(caster);
        if (t == 1) letGo(caster);
        Vec3d c = center;
        double radius = radiusAt(t);
        if (t % 3 == 0) ShakePayload.around(world, c, MAX_RADIUS, 0.25f + 0.5f * (1f - t / (float) PUSH_TICKS), 6);

        Box reach = new Box(c, c).expand(radius + 2);
        for (Entity e : world.getOtherEntities(caster, reach, e -> canPush(e, caster))) {
            Vec3d at = e.getBoundingBox().getCenter();
            double d = at.distanceTo(c);
            if (d > radius) continue;
            Caught got = caught.computeIfAbsent(e.getUuid(), k -> new Caught());
            if (got.crushed) continue;
            if (got.lastDist < 0 && e instanceof LivingEntity le) firstHit(le, caster, at);

            // Stuck behind the face of the barrier - against a wall, or pressed into the ground - is crushed
            if (got.lastDist >= 0 && d - got.lastDist < 0.15 && d < radius - 1.2) got.stuck++;
            else got.stuck = 0;
            got.lastDist = d;
            // Players move on their own side and reach us a little late: give them longer before they count as pinned
            int needed = e instanceof PlayerEntity ? PLAYER_STUCK_TICKS : STUCK_TICKS;
            if (got.stuck >= needed && e instanceof LivingEntity le) {
                got.crushed = true;
                crush(le, caster, at);
                continue;
            }

            // Carried along on the barrier's face, and a little more
            Vec3d out = at.subtract(c);
            out = out.lengthSquared() < 1e-4 ? new Vec3d(1, 0, 0) : out.normalize();
            double speed = Math.min(3.0, Math.max(0.9, (radius + 0.8 - d) * 0.6));
            Vec3d v = out.multiply(speed);
            if (out.y > -0.3) v = v.add(0, 0.15, 0);
            e.setVelocity(v);
            e.velocityModified = true;
        }
    }

    /** The moment it goes: the words are cried, the light flares, and the barrier starts out. */
    private void letGo(ServerPlayerEntity caster) {
        center = caster.getPos().add(0, caster.getHeight() * 0.55, 0);
        Vec3d c = center;
        MightyPush.sendNear(world, c, new MightyPush.ReleasePayload(caster.getId(), c.toVector3f()));
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.PLAYERS, 5f, 0.5f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 5f, 0.45f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ITEM_TRIDENT_THUNDER, SoundCategory.PLAYERS, 4f, 0.5f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_RESPAWN_ANCHOR_DEPLETE, SoundCategory.PLAYERS, 4f, 0.5f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ITEM_MACE_SMASH_GROUND_HEAVY, SoundCategory.PLAYERS, 4f, 0.5f);
        ShakePayload.around(world, c, MAX_RADIUS + 10, 1.4f, 30);
    }

    private void firstHit(LivingEntity e, ServerPlayerEntity caster, Vec3d at) {
        e.timeUntilRegen = 0;
        e.damage(world.getDamageSources().indirectMagic(caster, caster), HIT_DAMAGE);
        world.spawnParticles(ParticleTypes.SWEEP_ATTACK, at.x, at.y, at.z, 1, 0, 0, 0, 0);
        world.spawnParticles(ParticleTypes.CLOUD, at.x, at.y, at.z, 6, 0.3, 0.4, 0.3, 0.1);
        world.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK, SoundCategory.PLAYERS, 1.5f, 0.5f);
        if (e instanceof ServerPlayerEntity sp) shake(sp, 1.2f, 22);
    }

    private void crush(LivingEntity e, ServerPlayerEntity caster, Vec3d at) {
        e.timeUntilRegen = 0;
        e.damage(world.getDamageSources().indirectMagic(caster, caster), CRUSH_DAMAGE);
        BlockPos behind = BlockPos.ofFloored(at.add(at.subtract(center).normalize().multiply(e.getWidth() * 0.5 + 0.6)));
        var wall = world.getBlockState(behind);
        if (!wall.isAir()) {
            world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, wall), at.x, at.y, at.z, 40, 0.4, 0.5, 0.4, 0.3);
        }
        world.spawnParticles(ParticleTypes.CRIT, at.x, at.y, at.z, 30, 0.3, 0.5, 0.3, 0.5);
        world.spawnParticles(ParticleTypes.DAMAGE_INDICATOR, at.x, at.y, at.z, 10, 0.3, 0.5, 0.3, 0.2);
        world.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_GENERIC_BIG_FALL, SoundCategory.PLAYERS, 2f, 0.5f);
        world.playSound(null, at.x, at.y, at.z, SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, 1.2f, 0.5f);
        world.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, SoundCategory.PLAYERS, 1.5f, 0.6f);
        if (e instanceof ServerPlayerEntity sp) shake(sp, 1.6f, 28);
    }

    private boolean canPush(Entity e, ServerPlayerEntity caster) {
        if (e.isSpectator() || e instanceof ArmorStandEntity || e.hasVehicle()) return false;
        if (e instanceof PlayerEntity p && p.isCreative()) return false;
        if (e instanceof IllusionEntity) return false;
        if (e instanceof TameableEntity pet && casterId.equals(pet.getOwnerUuid())) return false;
        if (e instanceof LivingEntity le && !le.isAlive()) return false;
        return !e.isTeammate(caster);
    }

    // ---------------------------------------------------------------------
    // Ending
    // ---------------------------------------------------------------------

    /** Lets the caster go: gravity back, and a gentle way down if they're up in the air. */
    private void release(ServerPlayerEntity caster) {
        if (airborne) {
            caster.setNoGravity(false);
            caster.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, 20 * 8, 0, false, false, true));
        }
        caster.fallDistance = 0;
        MightyPush.sendNear(world, caster.getPos(), new MightyPush.EndPayload(caster.getId()));
    }

    static void abandon(ServerPlayerEntity player) {
        Iterator<MightyPushCast> it = ACTIVE.iterator();
        while (it.hasNext()) {
            MightyPushCast c = it.next();
            if (c.casterId.equals(player.getUuid())) {
                if (c.airborne) player.setNoGravity(false);
                it.remove();
            }
        }
    }

    static void abandonAll(MinecraftServer server) {
        for (MightyPushCast c : ACTIVE) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(c.casterId);
            if (p != null && c.airborne) p.setNoGravity(false);
        }
        ACTIVE.clear();
    }

    /** The cast {@code player} is in the middle of, if any (for tests). */
    @Nullable
    public static MightyPushCast of(PlayerEntity player) {
        for (MightyPushCast c : ACTIVE) if (!c.done && c.casterId.equals(player.getUuid())) return c;
        return null;
    }

    private static void shake(ServerPlayerEntity player, float strength, int ticks) {
        if (ServerPlayNetworking.canSend(player, ShakePayload.ID)) ServerPlayNetworking.send(player, new ShakePayload(strength, ticks));
    }
}
