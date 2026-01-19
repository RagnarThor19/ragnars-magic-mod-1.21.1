package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import org.joml.Vector3f;

import java.util.*;

public class SonicBoomSpell implements Spell {

    // --- tuning ---
    private static final double RANGE = 25.0;
    private static final float DAMAGE = 20.0f;
    private static final double WIDTH = 0.3;
    private static final int CHARGE_TIME = 40; // 2s
    private static final int COOLDOWN = 60;    // total lifetime
    private static final int RING_COUNT = 8;   // number of sonic rings
    private static final double RING_SPACING = 3.0; // distance between rings
    private static final int POINTS_PER_RING = 36;  // how many particles per ring
    private static final double RING_GROWTH = 0.05; // ring size growth per unit distance
    private static final double RECOIL_STRENGTH = 1.2; // How hard it pushes you back

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
        long now = sw.getTime();

        ACTIVE.computeIfAbsent(sw.getRegistryKey(), k -> new ArrayList<>())
                .add(new Beam(player.getUuid(), now, Beam.State.CHARGING));

        world.playSound(null, player.getBlockPos(),
                SoundEvents.ENTITY_WARDEN_SONIC_CHARGE,
                SoundCategory.PLAYERS, 2.0f, 1.0f);
        return true;
    }

    private static void tickWorld(ServerWorld world) {
        List<Beam> beams = ACTIVE.get(world.getRegistryKey());
        if (beams == null || beams.isEmpty()) return;

        long now = world.getTime();
        Random rand = world.getRandom();

        Iterator<Beam> it = beams.iterator();
        while (it.hasNext()) {
            Beam b = it.next();
            PlayerEntity owner = world.getPlayerByUuid(b.owner);
            if (owner == null) { it.remove(); continue; }

            long age = now - b.startTick;
            if (age > COOLDOWN) { it.remove(); continue; }

            if (b.state == Beam.State.CHARGING) {
                // --- improved sparse multi-color charge-up ---
                Vec3d center = owner.getCameraPosVec(0);
                for (int i = 0; i < 8; i++) { // fewer, slower particles
                    double angle = rand.nextDouble() * 2 * Math.PI;
                    double dist = 0.7 + rand.nextDouble() * 0.8;
                    double yOffset = (rand.nextDouble() - 0.5) * 1.0;
                    Vec3d pos = center.add(Math.cos(angle) * dist, yOffset, Math.sin(angle) * dist);

                    // Pick a random blue tone
                    Vector3f color = switch (rand.nextInt(3)) {
                        case 0 -> new Vector3f(0.1f, 0.3f, 0.9f); // deep blue
                        case 1 -> new Vector3f(0.2f, 0.8f, 1.0f); // cyan
                        default -> new Vector3f(0.4f, 0.9f, 1.0f); // pale blue
                    };
                    world.spawnParticles(new DustParticleEffect(color, 1.3f),
                            pos.x, pos.y, pos.z, 1, 0, 0, 0, 0);
                }

                if (age >= CHARGE_TIME) {
                    b.state = Beam.State.FIRING;
                    world.playSound(null, owner.getBlockPos(),
                            SoundEvents.ENTITY_WARDEN_SONIC_BOOM,
                            SoundCategory.PLAYERS, 3.0f, 1.0f);

                    // --- APPLY RECOIL ---
                    Vec3d look = owner.getRotationVector().normalize();
                    // Push opposite to look direction
                    // If looking slightly down, this will launch you slightly up + back
                    owner.addVelocity(-look.x * RECOIL_STRENGTH, -look.y * RECOIL_STRENGTH * 0.5, -look.z * RECOIL_STRENGTH);
                    owner.velocityModified = true;

                    fireRings(world, owner);
                }
            }
        }
    }

    // --- visual + damage for beam ---
    private static void fireRings(ServerWorld world, PlayerEntity player) {
        Vec3d origin = player.getCameraPosVec(0);
        Vec3d dir = player.getRotationVector().normalize();

        // Create multiple expanding rings along direction
        for (int r = 0; r < RING_COUNT; r++) {
            double dist = r * RING_SPACING;
            Vec3d ringCenter = origin.add(dir.multiply(dist));
            double ringRadius = 0.2 + dist * RING_GROWTH; // rings get slightly larger
            spawnRing(world, ringCenter, dir, ringRadius);
        }

        // Damage all entities along beam corridor
        Vec3d end = origin.add(dir.multiply(RING_COUNT * RING_SPACING));
        Box box = new Box(origin, end).expand(WIDTH);
        List<Entity> targets = world.getOtherEntities(player, box,
                e -> e instanceof LivingEntity && e.isAttackable() && !e.isTeammate(player));
        for (Entity e : targets) {
            if (e instanceof LivingEntity le) {
                le.damage(world.getDamageSources().sonicBoom(player), DAMAGE);
                Vec3d push = e.getPos().subtract(origin).normalize().multiply(1.0);
                le.addVelocity(push.x, 0.3, push.z);
                le.velocityDirty = true;
            }
        }
    }

    private static void spawnRing(ServerWorld world, Vec3d center, Vec3d forward, double radius) {
        Random rand = world.getRandom();
        // pick two perpendicular vectors to forward for ring plane
        Vec3d up = new Vec3d(0, 1, 0);
        Vec3d right = forward.crossProduct(up).normalize();
        Vec3d upVec = right.crossProduct(forward).normalize();

        for (int i = 0; i < POINTS_PER_RING; i++) {
            double theta = (2 * Math.PI * i) / POINTS_PER_RING;
            Vec3d offset = right.multiply(Math.cos(theta)).add(upVec.multiply(Math.sin(theta))).multiply(radius);
            Vec3d pos = center.add(offset);
            // soft light-blue gradient
            float r = 0.2f + rand.nextFloat() * 0.2f;
            float g = 0.8f + rand.nextFloat() * 0.15f;
            float b = 1.0f;
            world.spawnParticles(new DustParticleEffect(new Vector3f(r, g, b), 1.5f),
                    pos.x, pos.y, pos.z, 1, 0, 0, 0, 0);
        }
    }

    // --- helpers ---
    private static class Beam {
        final UUID owner;
        final long startTick;
        State state;
        Beam(UUID owner, long startTick, State state) {
            this.owner = owner;
            this.startTick = startTick;
            this.state = state;
        }
        enum State { CHARGING, FIRING }
    }
}