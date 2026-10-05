package net.ragnar.ragnarsmagicmod.shadowhands;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.entity.IllusionEntity;
import net.ragnar.ragnarsmagicmod.network.ShakePayload;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One cast of the Tome of Unseen Hands, run on the server: the pool creeping out for {@link ShadowHands#WAVE_TICKS}
 * (a heartbeat under it, quickening), the grab, {@link ShadowHands#SQUEEZE_TICKS} of crushing with everything caught
 * pinned in the air, then the release.
 */
public class Grasp {
    /** How far above and below the pool something can be and still get caught. */
    static final double REACH_UP = 6.0, REACH_DOWN = 2.0;
    /** How high the hands lift what they catch. */
    static final double LIFT = 0.5;
    private static final Vector3f SHADOW = new Vector3f(0.22f, 0.02f, 0.32f);

    private record Victim(LivingEntity entity, Vec3d pin) {}

    private final ServerWorld world;
    private final UUID ownerId;
    private final Vec3d center;
    private final int id = ShadowHands.nextId();
    private int age;
    private int nextBeat;
    private final List<Victim> victims = new ArrayList<>();

    Grasp(ServerWorld world, PlayerEntity owner, Vec3d center) {
        this.world = world;
        this.ownerId = owner.getUuid();
        this.center = center;
    }

    public Vec3d center() {
        return center;
    }

    void begin() {
        ShadowHands.send(world, center, new ShadowHands.StartPayload(id, center.toVector3f(), ShadowHands.RADIUS));
        PlayerEntity owner = owner();
        if (owner != null) {
            world.playSound(null, owner.getX(), owner.getEyeY(), owner.getZ(), SoundEvents.ENTITY_WITHER_AMBIENT, SoundCategory.PLAYERS, 0.8f, 0.5f);
        }
        world.playSound(null, center.x, center.y, center.z, SoundEvents.AMBIENT_SOUL_SAND_VALLEY_MOOD.value(), SoundCategory.PLAYERS, 3.0f, 0.6f);
        world.playSound(null, center.x, center.y, center.z, SoundEvents.BLOCK_SCULK_CATALYST_BLOOM, SoundCategory.PLAYERS, 2.0f, 0.5f);
        world.playSound(null, center.x, center.y, center.z, SoundEvents.ENTITY_WARDEN_SONIC_CHARGE, SoundCategory.PLAYERS, 1.2f, 0.5f);
        world.spawnParticles(ParticleTypes.SQUID_INK, center.x, center.y + 0.1, center.z, 10, 0.3, 0.05, 0.3, 0.04);
    }

    @Nullable
    private PlayerEntity owner() {
        return world.getPlayerByUuid(ownerId);
    }

    /** One tick. False once it's all over. */
    boolean tick() {
        age++;
        int grabAt = ShadowHands.WAVE_TICKS, releaseAt = grabAt + ShadowHands.SQUEEZE_TICKS;
        if (age < grabAt) {
            creep();
        } else if (age == grabAt) {
            grab();
        } else if (age <= releaseAt) {
            hold();
            if ((age - grabAt) % ShadowHands.PULSE_EVERY == 0) squeeze();
            if (age == releaseAt) release();
        }
        return age < releaseAt + ShadowHands.AFTER_TICKS;
    }

    // ---------------------------------------------------------------------
    // The creeping
    // ---------------------------------------------------------------------

    private void creep() {
        float t = (float) age / ShadowHands.WAVE_TICKS;
        double r = ShadowHands.RADIUS * frontAt(t);
        // Ink and smoke boiling up off the creeping edge
        for (int i = 0; i < 3; i++) {
            double a = world.random.nextDouble() * Math.PI * 2;
            double x = center.x + Math.cos(a) * r, z = center.z + Math.sin(a) * r;
            if (i == 0) world.spawnParticles(ParticleTypes.SQUID_INK, x, center.y + 0.15, z, 1, 0.1, 0.02, 0.1, 0.01);
            world.spawnParticles(new DustParticleEffect(SHADOW, 1.6f), x, center.y + 0.2, z, 1, 0.2, 0.05, 0.2, 0);
        }
        // A heartbeat under the ground, quickening the whole time
        if (age >= nextBeat) {
            float rate = MathHelper.lerp(t, 18f, 6f);
            nextBeat = age + Math.round(rate);
            world.playSound(null, center.x, center.y, center.z, SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 2.0f + 2f * t, 0.6f + 0.3f * t);
        }
        if (age % 12 == 0) {
            double a = world.random.nextDouble() * Math.PI * 2;
            world.playSound(null, center.x + Math.cos(a) * r, center.y, center.z + Math.sin(a) * r, SoundEvents.BLOCK_SCULK_SPREAD,
                    SoundCategory.PLAYERS, 1.5f, 0.5f + world.random.nextFloat() * 0.2f);
        }
        if (age == ShadowHands.WAVE_TICKS - 14) {
            world.playSound(null, center.x, center.y, center.z, SoundEvents.ENTITY_WARDEN_NEARBY_CLOSEST, SoundCategory.PLAYERS, 3.0f, 0.5f);
        }
    }

    /** How far out the creeping edge is, 0..1, {@code t} of the way through the creep: slow to start, then spreading. */
    public static float frontAt(float t) {
        t = MathHelper.clamp(t, 0f, 1f);
        return t * t * (3f - 2f * t) * 0.35f + t * 0.65f;
    }

    // ---------------------------------------------------------------------
    // The grab
    // ---------------------------------------------------------------------

    private void grab() {
        PlayerEntity owner = owner();
        double r = ShadowHands.RADIUS;
        Box area = new Box(center.x - r, center.y - REACH_DOWN, center.z - r, center.x + r, center.y + REACH_UP, center.z + r);
        List<Integer> ids = new ArrayList<>();
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, area, e -> canGrab(e, owner))) {
            double dx = e.getX() - center.x, dz = e.getZ() - center.z;
            if (dx * dx + dz * dz > r * r) continue;
            victims.add(new Victim(e, e.getPos()));
            ids.add(e.getId());
            Vec3d p = e.getPos();
            world.spawnParticles(ParticleTypes.SQUID_INK, p.x, p.y + 0.1, p.z, 8, 0.5, 0.1, 0.5, 0.06);
            world.spawnParticles(ParticleTypes.LARGE_SMOKE, p.x, p.y + 0.3, p.z, 8, 0.4, 0.2, 0.4, 0.02);
            world.playSound(null, p.x, p.y, p.z, SoundEvents.ENTITY_EVOKER_FANGS_ATTACK, SoundCategory.PLAYERS, 1.5f, 0.5f);
            world.playSound(null, p.x, p.y, p.z, SoundEvents.ENTITY_WARDEN_ATTACK_IMPACT, SoundCategory.PLAYERS, 1.2f, 0.6f);
        }
        ShadowHands.send(world, center, new ShadowHands.GrabPayload(id, ids));

        world.playSound(null, center.x, center.y, center.z, SoundEvents.ENTITY_WARDEN_EMERGE, SoundCategory.PLAYERS, 3.0f, 1.3f);
        world.playSound(null, center.x, center.y, center.z, SoundEvents.ENTITY_WARDEN_ROAR, SoundCategory.PLAYERS, 2.0f, 1.6f);
        world.playSound(null, center.x, center.y, center.z, SoundEvents.BLOCK_SCULK_SHRIEKER_SHRIEK, SoundCategory.PLAYERS, 2.0f, 0.5f);
        ShakePayload.around(world, center, ShadowHands.RADIUS, 0.6f, 14);
    }

    private boolean canGrab(Entity e, @Nullable PlayerEntity owner) {
        if (!(e instanceof LivingEntity le) || !le.isAlive() || le.isSpectator() || e instanceof ArmorStandEntity
                || e instanceof EnderDragonEntity || e instanceof IllusionEntity) return false;
        if (e.getUuid().equals(ownerId)) return false;
        if (e instanceof TameableEntity pet && ownerId.equals(pet.getOwnerUuid())) return false;
        if (e instanceof PlayerEntity p && p.isCreative()) return false;
        return owner == null || !e.isTeammate(owner);
    }

    // ---------------------------------------------------------------------
    // The squeezing
    // ---------------------------------------------------------------------

    /** Keeps everything caught pinned where the hands hold it, lifted a little off its feet. */
    private void hold() {
        float lift = MathHelper.clamp((float) (age - ShadowHands.WAVE_TICKS) / ShadowHands.RISE_TICKS, 0f, 1f);
        victims.removeIf(v -> !v.entity().isAlive() || v.entity().isRemoved());
        for (Victim v : victims) {
            LivingEntity e = v.entity();
            Vec3d at = v.pin().add(0, LIFT * lift, 0);
            e.setVelocity(Vec3d.ZERO);
            e.fallDistance = 0;
            if (e instanceof ServerPlayerEntity sp) {
                if (sp.getPos().squaredDistanceTo(at) > 0.0025) sp.networkHandler.requestTeleport(at.x, at.y, at.z, sp.getYaw(), sp.getPitch());
            } else {
                e.setPosition(at);
                e.velocityModified = true;
                if (e instanceof MobEntity mob) mob.getNavigation().stop();
            }
            if (world.random.nextInt(3) == 0) {
                Vec3d c = e.getBoundingBox().getCenter();
                world.spawnParticles(new DustParticleEffect(SHADOW, 1.2f), c.x, c.y, c.z, 1, e.getWidth() * 0.5, e.getHeight() * 0.4, e.getWidth() * 0.5, 0);
            }
        }
    }

    /** One crushing squeeze: everything caught takes its share of the damage, armour or not. */
    private void squeeze() {
        PlayerEntity owner = owner();
        DamageSource source = owner != null ? world.getDamageSources().indirectMagic(owner, owner) : world.getDamageSources().magic();
        for (Victim v : victims) {
            LivingEntity e = v.entity();
            e.timeUntilRegen = 0;
            e.damage(source, ShadowHands.DAMAGE_PER_PULSE);
            Vec3d c = e.getBoundingBox().getCenter();
            world.spawnParticles(ParticleTypes.SQUID_INK, c.x, c.y, c.z, 3, e.getWidth() * 0.4, e.getHeight() * 0.3, e.getWidth() * 0.4, 0.02);
            world.spawnParticles(new DustParticleEffect(SHADOW, 2.0f), c.x, c.y, c.z, 8, e.getWidth() * 0.5, e.getHeight() * 0.4, e.getWidth() * 0.5, 0);
            world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_BONE_BLOCK_BREAK, SoundCategory.PLAYERS, 1.4f, 0.55f + world.random.nextFloat() * 0.15f);
            world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_SLIME_SQUISH, SoundCategory.PLAYERS, 1.0f, 0.5f);
            if (!e.isAlive()) world.spawnParticles(ParticleTypes.SCULK_SOUL, c.x, c.y, c.z, 6, 0.3, 0.3, 0.3, 0.05);
        }
        if (!victims.isEmpty()) {
            world.playSound(null, center.x, center.y, center.z, SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 3.0f, 0.5f);
            ShakePayload.around(world, center, ShadowHands.RADIUS, 0.25f, 6);
        }
    }

    /** Lets go of everything, gently: nothing takes fall damage from where the hands held it. */
    void release() {
        for (Victim v : victims) v.entity().fallDistance = 0;
        world.playSound(null, center.x, center.y, center.z, SoundEvents.ENTITY_WARDEN_DIG, SoundCategory.PLAYERS, 2.5f, 0.8f);
        world.playSound(null, center.x, center.y, center.z, SoundEvents.BLOCK_SCULK_CATALYST_BREAK, SoundCategory.PLAYERS, 1.5f, 0.5f);
        victims.clear();
    }

    /** What's held right now (for tests). */
    public List<LivingEntity> held() {
        return victims.stream().map(Victim::entity).toList();
    }
}
