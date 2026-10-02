package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.Tameable;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.entity.CloneEntity;
import net.ragnar.ragnarsmagicmod.network.ShakePayload;
import net.ragnar.ragnarsmagicmod.network.ReckoningPayload;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tome of Reckoning: a silent ripple runs out across the ground from the caster to {@link #RADIUS} blocks. Anything
 * standing on the ground as it passes underneath is caught - only being in the air at that exact moment saves you.
 * When the ripple has run its course, everything it caught is drawn up into the air, hangs there shaking, and is
 * hurled down into the ground: {@link #MAX_DAMAGE} near the caster, falling to {@link #MIN_DAMAGE} at the edge.
 * The wave is drawn on each client (ReckoningPayload) so it costs one packet, not a particle flood.
 */
public class ReckoningSpell implements Spell {
    public static final float RADIUS = 18f;
    public static final float SPEED = 0.45f;            // blocks a tick: 40 ticks to the edge
    public static final double BAND = 0.9;              // how wide the front is; you must be airborne while it's under you
    private static final double REACH_UP = 4.0, REACH_DOWN = 4.0; // it follows the floor, within this of the caster
    public static final float MAX_DAMAGE = 26f, MIN_DAMAGE = 16f;

    private static final int RISE_TICKS = 14;
    private static final double RISE_HEIGHT = 4.5;
    private static final int SHAKE_TICKS = 14;
    private static final int FALL_LIMIT = 16;           // ticks after the slam to hit the ground before it counts anyway
    private static final double SLAM_SPEED = -3.2;

    private enum Stage { WAVE, RISE, SHAKE, FALL, DONE }

    private static final class Victim {
        final LivingEntity entity;
        final float damage;
        final boolean hadNoGravity;
        double baseY;
        boolean landed;

        Victim(LivingEntity entity, float damage) {
            this.entity = entity;
            this.damage = damage;
            this.hadNoGravity = entity.hasNoGravity();
        }
    }

    private static final class Reckoning {
        final ServerWorld world;
        final UUID owner;
        final Vec3d center;
        final Set<UUID> settled = new HashSet<>();            // passed by the front, caught or not
        final Map<UUID, Victim> caught = new LinkedHashMap<>();
        Stage stage = Stage.WAVE;
        int age, stageAge;

        Reckoning(ServerWorld world, PlayerEntity owner) {
            this.world = world;
            this.owner = owner.getUuid();
            this.center = owner.getPos();
        }
    }

    private static final List<Reckoning> ACTIVE = new ArrayList<>();

    public static void register() {
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            if (ACTIVE.isEmpty()) return;
            Iterator<Reckoning> it = ACTIVE.iterator();
            while (it.hasNext()) {
                Reckoning u = it.next();
                if (u.world == world && !tick(u)) it.remove();
            }
        });
        // Never leave anything floating with its gravity switched off
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (Reckoning u : ACTIVE) for (Victim v : u.caught.values()) release(v);
            ACTIVE.clear();
        });
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw)) return false;
        Reckoning u = new Reckoning(sw, player);
        u.settled.add(player.getUuid());
        ACTIVE.add(u);

        ReckoningPayload.around(sw, u.center, SPEED, RADIUS);
        Vec3d c = u.center;
        sound(sw, c, SoundEvents.EVENT_MOB_EFFECT_TRIAL_OMEN, 0.7f, 0.6f);
        sound(sw, c, SoundEvents.BLOCK_CONDUIT_DEACTIVATE, 0.6f, 0.5f);
        sw.spawnParticles(ParticleTypes.SCULK_SOUL, c.x, c.y + 0.2, c.z, 12, 0.4, 0.1, 0.4, 0.03);
        return true;
    }

    // ---------------------------------------------------------------------
    // Ticking
    // ---------------------------------------------------------------------

    private static boolean tick(Reckoning u) {
        u.age++;
        u.stageAge++;
        u.caught.values().removeIf(v -> {
            if (v.entity.isAlive() && !v.entity.isRemoved() && v.entity.getWorld() == u.world) return false;
            release(v);
            return true;
        });

        switch (u.stage) {
            case WAVE -> {
                double r = u.age * SPEED;
                sweep(u, r);
                // The ripple barely whispers as it goes
                if (u.age % 5 == 0) {
                    double a = u.world.random.nextDouble() * Math.PI * 2;
                    sound(u.world, u.center.add(Math.cos(a) * r, 0, Math.sin(a) * r), SoundEvents.BLOCK_SCULK_SPREAD, 0.5f, 0.6f);
                }
                if (r >= RADIUS) {
                    if (u.caught.isEmpty()) return false;
                    startRise(u);
                }
            }
            case RISE -> {
                double t = Math.min(1.0, u.stageAge / (double) RISE_TICKS);
                double eased = 1 - (1 - t) * (1 - t);
                for (Victim v : u.caught.values()) hold(v, v.baseY + RISE_HEIGHT * eased, 0.0);
                if (u.stageAge >= RISE_TICKS) {
                    nextStage(u, Stage.SHAKE);
                    sound(u.world, u.center, SoundEvents.BLOCK_TRIAL_SPAWNER_AMBIENT_OMINOUS, 1.0f, 0.5f);
                }
            }
            case SHAKE -> {
                // Shaking harder the longer it holds them
                double k = 0.08 + 0.22 * u.stageAge / SHAKE_TICKS;
                for (Victim v : u.caught.values()) {
                    hold(v, v.baseY + RISE_HEIGHT, k);
                    if (u.stageAge % 3 == 0) {
                        u.world.spawnParticles(ParticleTypes.SCULK_SOUL, v.entity.getX(), v.entity.getBodyY(0.5), v.entity.getZ(), 1, 0.3, 0.3, 0.3, 0.01);
                    }
                }
                if (u.stageAge % 5 == 0) sound(u.world, u.center, SoundEvents.EVENT_MOB_EFFECT_BAD_OMEN, 0.45f, 0.5f + u.stageAge * 0.03f);
                if (u.stageAge >= SHAKE_TICKS) slam(u);
            }
            case FALL -> {
                boolean anyLeft = false;
                for (Victim v : u.caught.values()) {
                    if (v.landed) continue;
                    v.entity.fallDistance = 0;
                    if (v.entity.isOnGround() || v.entity.isTouchingWater() || u.stageAge >= FALL_LIMIT) impact(u, v);
                    else {
                        v.entity.setVelocity(0, SLAM_SPEED, 0);
                        v.entity.velocityModified = true;
                        anyLeft = true;
                    }
                }
                if (!anyLeft) return false;
            }
            case DONE -> {
                return false;
            }
        }
        return true;
    }

    /** The front passes: anything standing on the ground under it is caught. */
    private static void sweep(Reckoning u, double r) {
        PlayerEntity owner = u.world.getPlayerByUuid(u.owner);
        Box area = new Box(u.center, u.center).expand(r + 1.5, 0, r + 1.5).stretch(0, REACH_UP, 0).stretch(0, -REACH_DOWN, 0);
        for (LivingEntity e : u.world.getEntitiesByClass(LivingEntity.class, area, e -> !u.settled.contains(e.getUuid()))) {
            double dx = e.getX() - u.center.x, dz = e.getZ() - u.center.z;
            double d = Math.sqrt(dx * dx + dz * dz);
            double half = e.getWidth() * 0.5;
            if (d - half > r) continue;                      // the front hasn't reached it yet
            if (d + half < r - BAND) {                       // the front has gone past: it jumped clear
                u.settled.add(e.getUuid());
                continue;
            }
            if (!grounded(e) || !canCatch(e, owner)) continue;
            u.settled.add(e.getUuid());
            float damage = MathHelper.lerp((float) MathHelper.clamp(d / RADIUS, 0, 1), MAX_DAMAGE, MIN_DAMAGE);
            u.caught.put(e.getUuid(), new Victim(e, damage));
            u.world.spawnParticles(ParticleTypes.SCULK_SOUL, e.getX(), e.getY() + 0.2, e.getZ(), 6, e.getWidth() * 0.4, 0.1, e.getWidth() * 0.4, 0.02);
            u.world.spawnParticles(ParticleTypes.SCULK_CHARGE_POP, e.getX(), e.getY() + 0.1, e.getZ(), 8, e.getWidth() * 0.5, 0.05, e.getWidth() * 0.5, 0.01);
            sound(u.world, e.getPos(), SoundEvents.BLOCK_SCULK_CHARGE, 0.6f, 0.6f);
        }
    }

    private static boolean grounded(LivingEntity e) {
        return e.isOnGround() || e.isTouchingWater() || e.isClimbing();
    }

    /** Never the caster, their pets or clones; never players who can't be hurt. */
    private static boolean canCatch(LivingEntity e, PlayerEntity owner) {
        if (e instanceof ArmorStandEntity || !e.isAlive()) return false;
        if (e instanceof PlayerEntity p && (p.isCreative() || p.isSpectator())) return false;
        if (owner != null) {
            if (e == owner || e == owner.getVehicle()) return false;
            if (e instanceof Tameable pet && owner.getUuid().equals(pet.getOwnerUuid())) return false;
            if (e instanceof CloneEntity clone && clone.isOwnedBy(owner)) return false;
            if (e instanceof net.ragnar.ragnarsmagicmod.knight.KnightEntity knight && knight.isOwnedBy(owner)) return false;
        }
        return true;
    }

    // ---------------------------------------------------------------------
    // Lift, shake, slam
    // ---------------------------------------------------------------------

    private static void startRise(Reckoning u) {
        nextStage(u, Stage.RISE);
        for (Victim v : u.caught.values()) {
            v.baseY = v.entity.getY();
            v.entity.setNoGravity(true);
            sound(u.world, v.entity.getPos(), SoundEvents.ITEM_OMINOUS_BOTTLE_DISPOSE, 0.7f, 0.5f);
        }
        sound(u.world, u.center, SoundEvents.ENTITY_WARDEN_NEARBY_CLOSE, 0.8f, 0.6f);
    }

    /** Steers a victim to {@code y}, with {@code shake} worth of jitter. */
    private static void hold(Victim v, double y, double shake) {
        LivingEntity e = v.entity;
        e.fallDistance = 0;
        var rand = e.getRandom();
        double jx = shake > 0 ? (rand.nextDouble() - 0.5) * shake : 0;
        double jz = shake > 0 ? (rand.nextDouble() - 0.5) * shake : 0;
        double jy = shake > 0 ? (rand.nextDouble() - 0.5) * shake * 0.5 : 0;
        e.setVelocity(jx, (y - e.getY()) * 0.45 + jy, jz);
        e.velocityModified = true;
    }

    private static void slam(Reckoning u) {
        nextStage(u, Stage.FALL);
        for (Victim v : u.caught.values()) {
            v.entity.setNoGravity(v.hadNoGravity);
            v.entity.setVelocity(0, SLAM_SPEED, 0);
            v.entity.velocityModified = true;
        }
        // The one loud moment
        sound(u.world, u.center, SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE, 0.9f, 0.6f);
    }

    private static void impact(Reckoning u, Victim v) {
        v.landed = true;
        LivingEntity e = v.entity;
        e.fallDistance = 0;
        e.setVelocity(e.getVelocity().multiply(0.2, 0, 0.2));
        e.velocityModified = true;

        PlayerEntity owner = u.world.getPlayerByUuid(u.owner);
        DamageSource source = owner != null ? u.world.getDamageSources().playerAttack(owner) : u.world.getDamageSources().magic();
        e.damage(source, v.damage);

        Vec3d p = e.getPos();
        BlockPos below = BlockPos.ofFloored(p.x, p.y - 0.2, p.z);
        BlockState ground = u.world.getBlockState(below);
        if (!ground.isAir()) {
            u.world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, ground), p.x, p.y + 0.1, p.z, 40, 0.8, 0.1, 0.8, 0.25);
        }
        u.world.spawnParticles(ParticleTypes.SCULK_SOUL, p.x, p.y + 0.3, p.z, 10, 0.5, 0.2, 0.5, 0.06);
        u.world.spawnParticles(ParticleTypes.EXPLOSION, p.x, p.y + 0.3, p.z, 1, 0, 0, 0, 0);
        sound(u.world, p, SoundEvents.BLOCK_HEAVY_CORE_PLACE, 1.6f, 0.5f);
        sound(u.world, p, SoundEvents.ENTITY_RAVAGER_STUNNED, 0.9f, 0.6f);
        sound(u.world, p, SoundEvents.ENTITY_GENERIC_BIG_FALL, 1.0f, 0.6f);
        sound(u.world, p, SoundEvents.BLOCK_SOUL_SOIL_BREAK, 1.2f, 0.5f);
        ShakePayload.around(u.world, p, 6.0, 0.45f, 10);
    }

    private static void release(Victim v) {
        if (v.entity.hasNoGravity() != v.hadNoGravity) v.entity.setNoGravity(v.hadNoGravity);
    }

    private static void nextStage(Reckoning u, Stage stage) {
        u.stage = stage;
        u.stageAge = 0;
    }

    private static void sound(ServerWorld world, Vec3d at, SoundEvent sound, float volume, float pitch) {
        world.playSound(null, at.x, at.y, at.z, sound, SoundCategory.PLAYERS, volume, pitch);
    }

    private static void sound(ServerWorld world, Vec3d at, net.minecraft.registry.entry.RegistryEntry<SoundEvent> sound, float volume, float pitch) {
        world.playSound(null, at.x, at.y, at.z, sound, SoundCategory.PLAYERS, volume, pitch, world.random.nextLong());
    }

    /** For tests: how much damage something caught at {@code distance} from the caster takes. */
    public static float damageAt(double distance) {
        return MathHelper.lerp((float) MathHelper.clamp(distance / RADIUS, 0, 1), MAX_DAMAGE, MIN_DAMAGE);
    }
}
