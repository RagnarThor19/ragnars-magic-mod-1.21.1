package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import org.joml.Vector3f;

import java.util.*;

/**
 * The warden's sonic boom (vanilla {@code SonicBoomTask}) with a master-tier wind-up: the charge sound,
 * a 34-tick wind-up, then a line of shockwave rings - one every block - that passes through
 * walls, ignores armour and shields, and flings whatever it hits.
 */
public class SonicBoomSpell implements Spell {

    // --- tuning ---
    private static final double RANGE = 25.0;
    private static final float DAMAGE = 20.0f;
    private static final double HIT_RADIUS = 0.5;       // how far from the line an entity's hitbox still counts
    private static final int CHARGE_TIME = 34;          // same wind-up as the warden (SonicBoomTask.SOUND_DELAY)
    private static final double KNOCKBACK_HORIZONTAL = 2.5; // warden values
    private static final double KNOCKBACK_VERTICAL = 0.5;
    private static final double RECOIL_STRENGTH = 1.2;  // How hard it pushes you back

    private static final Map<RegistryKey<World>, List<Beam>> ACTIVE = new HashMap<>();
    private static boolean TICK_REGISTERED = false;

    private static void ensureTicker() {
        if (TICK_REGISTERED) return;
        ServerTickEvents.END_WORLD_TICK.register(SonicBoomSpell::tickWorld);
        TICK_REGISTERED = true;
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        ensureTicker();

        ServerWorld sw = (ServerWorld) world;
        ACTIVE.computeIfAbsent(sw.getRegistryKey(), k -> new ArrayList<>())
                .add(new Beam(player.getUuid(), sw.getTime()));

        world.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ENTITY_WARDEN_SONIC_CHARGE, SoundCategory.PLAYERS, 3.0f, 1.0f);
        return true;
    }

    private static void tickWorld(ServerWorld world) {
        List<Beam> beams = ACTIVE.get(world.getRegistryKey());
        if (beams == null || beams.isEmpty()) return;

        long now = world.getTime();
        Iterator<Beam> it = beams.iterator();
        while (it.hasNext()) {
            Beam b = it.next();
            PlayerEntity owner = world.getPlayerByUuid(b.owner);
            if (owner == null || !owner.isAlive()) { it.remove(); continue; }

            int age = (int) (now - b.startTick);
            if (age >= CHARGE_TIME) {
                fire(world, owner);
                it.remove();
            } else {
                chargeFx(world, owner, age);
            }
        }
    }

    // Heartbeats that speed up as the boom builds
    private static final int[] HEARTBEATS = {0, 12, 21, 27, 31};

    /** The wind-up: sculk energy is drawn into a point in front of the caster's chest, faster and faster. */
    private static void chargeFx(ServerWorld world, PlayerEntity player, int age) {
        Random rand = world.getRandom();
        float t = age / (float) CHARGE_TIME;
        Vec3d look = player.getRotationVector().normalize();
        Vec3d core = chargeCore(player, look);

        // Soul energy streaming in from all around; more of it, from further out, as it builds
        int streams = 2 + (int) (t * 7);
        for (int i = 0; i < streams; i++) {
            Vec3d dir = new Vec3d(rand.nextGaussian(), rand.nextGaussian(), rand.nextGaussian()).normalize();
            double r = 1.4 + t * 1.4 + rand.nextDouble() * 0.6;
            Vec3d from = core.add(dir.multiply(r));
            // Slowing particles travel ~25x their start speed, so this lands them on the core
            Vec3d vel = dir.multiply(-r * 0.042);
            world.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, from.x, from.y, from.z, 0, vel.x, vel.y, vel.z, 1.0);
        }
        if (rand.nextFloat() < 0.25f + t * 0.5f) {
            Vec3d dir = new Vec3d(rand.nextGaussian(), rand.nextGaussian(), rand.nextGaussian()).normalize();
            Vec3d from = core.add(dir.multiply(1.8));
            world.spawnParticles(ParticleTypes.SCULK_SOUL, from.x, from.y, from.z, 0, -dir.x, -dir.y, -dir.z, 0.06);
        }

        // A ring that closes in on the core, pulsing faster near the end
        int ringEvery = t < 0.5f ? 6 : t < 0.8f ? 4 : 2;
        if (age % ringEvery == 0) {
            spawnRing(world, core, look, 1.1 - t * 0.8, 20);
        }

        // The core itself: a dense, crackling knot once it's nearly ready
        if (t > 0.45f) {
            world.spawnParticles(ParticleTypes.SCULK_CHARGE_POP, core.x, core.y, core.z, 1 + (int) (t * 3), 0.08, 0.08, 0.08, 0.01);
        }
        world.spawnParticles(new DustParticleEffect(CORE_COLOR, 0.6f + t * 1.4f), core.x, core.y, core.z, 1, 0.03, 0.03, 0.03, 0);

        for (int beat : HEARTBEATS) {
            if (age == beat) {
                world.playSound(null, player.getX(), player.getY(), player.getZ(),
                        SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 1.5f, 0.8f + t * 0.6f);
            }
        }
    }

    private static final Vector3f CORE_COLOR = new Vector3f(0.15f, 0.85f, 0.95f); // warden's glowing cyan

    private static Vec3d chargeCore(PlayerEntity player, Vec3d look) {
        return player.getPos().add(0, player.getHeight() * 0.65, 0).add(look.multiply(1.3));
    }

    private static void spawnRing(ServerWorld world, Vec3d center, Vec3d forward, double radius, int points) {
        Vec3d helper = Math.abs(forward.y) > 0.9 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
        Vec3d u = forward.crossProduct(helper).normalize();
        Vec3d v = forward.crossProduct(u).normalize();
        DustParticleEffect dust = new DustParticleEffect(CORE_COLOR, 0.7f);
        for (int i = 0; i < points; i++) {
            double a = 2 * Math.PI * i / points;
            Vec3d p = center.add(u.multiply(Math.cos(a) * radius)).add(v.multiply(Math.sin(a) * radius));
            world.spawnParticles(dust, p.x, p.y, p.z, 1, 0, 0, 0, 0);
        }
    }

    private static void fire(ServerWorld world, PlayerEntity player) {
        // The warden fires from its chest; do the same, aimed so the line runs into the crosshair
        Vec3d eye = player.getEyePos();
        Vec3d look = player.getRotationVector().normalize();
        Vec3d origin = player.getPos().add(0, player.getHeight() * 0.65, 0);
        Vec3d dir = eye.add(look.multiply(RANGE)).subtract(origin).normalize();
        Vec3d end = origin.add(dir.multiply(RANGE));

        // One shockwave ring per block, exactly like SonicBoomTask
        int steps = MathHelper.floor(RANGE);
        for (int j = 1; j <= steps; j++) {
            Vec3d p = origin.add(dir.multiply(j));
            world.spawnParticles(ParticleTypes.SONIC_BOOM, p.x, p.y, p.z, 1, 0, 0, 0, 0);
        }
        world.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.PLAYERS, 3.0f, 1.0f);

        // Recoil
        player.addVelocity(-look.x * RECOIL_STRENGTH, -look.y * RECOIL_STRENGTH * 0.5, -look.z * RECOIL_STRENGTH);
        player.velocityModified = true;

        // Everything whose hitbox touches the line is hit - walls don't stop it
        Box area = new Box(origin, end).expand(HIT_RADIUS + 1.0);
        List<Entity> targets = world.getOtherEntities(player, area,
                e -> e instanceof LivingEntity && e.isAlive() && e.isAttackable() && !e.isTeammate(player)
                        && e.getBoundingBox().expand(HIT_RADIUS).raycast(origin, end).isPresent());
        for (Entity e : targets) {
            LivingEntity le = (LivingEntity) e;
            if (!le.damage(world.getDamageSources().sonicBoom(player), DAMAGE)) continue;
            double resist = 1.0 - le.getAttributeValue(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE);
            le.addVelocity(dir.x * KNOCKBACK_HORIZONTAL * resist,
                    dir.y * KNOCKBACK_VERTICAL * resist,
                    dir.z * KNOCKBACK_HORIZONTAL * resist);
            le.velocityModified = true;
            le.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 60, 0), player);
            Vec3d c = le.getBoundingBox().getCenter();
            world.spawnParticles(ParticleTypes.SCULK_SOUL, c.x, c.y, c.z, 8, 0.3, 0.4, 0.3, 0.05);
        }

        // The released core bursts at the muzzle
        Vec3d core = chargeCore(player, look);
        world.spawnParticles(ParticleTypes.SCULK_CHARGE_POP, core.x, core.y, core.z, 20, 0.25, 0.25, 0.25, 0.08);
    }

    private record Beam(UUID owner, long startTick) {}
}
