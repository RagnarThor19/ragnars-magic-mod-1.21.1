package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
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

/**
 * Tears open a swirling vortex where you aim. For a few seconds it drags every creature within
 * {@link #RADIUS} blocks toward its core and grinds at them, sucking up debris from the ground.
 * A dark singularity sits at the centre inside a spinning accretion disc.
 */
public class VortexSpell implements Spell {

    private static final double RANGE = 48.0;
    private static final double RADIUS = 7.0;
    private static final int DURATION_TICKS = 60;
    private static final float DAMAGE_PER_TICK = 0.5f;
    private static final double PULL_STRENGTH = 0.60;

    private static final DustParticleEffect SINGULARITY = new DustParticleEffect(new Vector3f(0.05f, 0.0f, 0.1f), 2.5f);
    private static final DustParticleEffect RIM = new DustParticleEffect(new Vector3f(0.6f, 0.3f, 1.0f), 1.0f);

    private static final Map<RegistryKey<World>, List<Vortex>> ACTIVE = new HashMap<>();
    private static boolean TICK_REGISTERED = false;

    private static void ensureTicker() {
        if (TICK_REGISTERED) return;
        ServerTickEvents.END_WORLD_TICK.register(VortexSpell::tickWorld);
        TICK_REGISTERED = true;
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        ensureTicker();

        Vec3d pos = getTargetPosition(world, player);

        world.playSound(null, BlockPos.ofFloored(pos), SoundEvents.ENTITY_ENDER_DRAGON_FLAP, SoundCategory.PLAYERS, 1.2f, 0.7f);
        world.playSound(null, BlockPos.ofFloored(pos), SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 0.9f, 1.6f);
        world.playSound(null, BlockPos.ofFloored(pos), SoundEvents.ENTITY_WIND_CHARGE_WIND_BURST.value(), SoundCategory.PLAYERS, 1.0f, 0.5f);

        ServerWorld sw = (ServerWorld) world;
        sw.spawnParticles(ParticleTypes.FLASH, pos.x, pos.y, pos.z, 1, 0, 0, 0, 0);
        ACTIVE.computeIfAbsent(sw.getRegistryKey(), k -> new ArrayList<>())
                .add(new Vortex(pos, sw.getTime(), player.getUuid(), groundBelow(sw, pos)));
        return true;
    }

    private static Vec3d getTargetPosition(World world, PlayerEntity player) {
        HitResult hit = player.raycast(RANGE, 0.0f, false);
        if (hit.getType() == HitResult.Type.BLOCK) {
            return ((BlockHitResult) hit).getPos();
        } else {
            return player.getCameraPosVec(0).add(player.getRotationVector().normalize().multiply(6.0));
        }
    }

    /** The block under the vortex, used for the debris it tears up (null if it's hanging in the air). */
    private static BlockState groundBelow(ServerWorld world, Vec3d pos) {
        BlockHitResult down = world.raycast(new RaycastContext(pos.add(0, 0.5, 0), pos.add(0, -4, 0),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, net.minecraft.block.ShapeContext.absent()));
        if (down.getType() != HitResult.Type.BLOCK) return null;
        BlockState state = world.getBlockState(down.getBlockPos());
        return state.isAir() ? null : state;
    }

    private static void tickWorld(ServerWorld world) {
        List<Vortex> list = ACTIVE.get(world.getRegistryKey());
        if (list == null || list.isEmpty()) return;

        long now = world.getTime();
        Iterator<Vortex> it = list.iterator();
        while (it.hasNext()) {
            Vortex v = it.next();
            int age = (int) (now - v.spawnTick);
            if (age > DURATION_TICKS) {
                collapse(world, v);
                it.remove();
                continue;
            }

            spawnParticles(world, v, age);

            // Pull + damage (never the caster)
            Box box = new Box(v.pos.x - RADIUS, v.pos.y - RADIUS, v.pos.z - RADIUS,
                    v.pos.x + RADIUS, v.pos.y + RADIUS, v.pos.z + RADIUS);
            List<Entity> targets = world.getOtherEntities(null, box,
                    e -> e instanceof LivingEntity le && le.isAlive() && !e.getUuid().equals(v.casterId));
            for (Entity e : targets) {
                LivingEntity le = (LivingEntity) e;

                Vec3d toCenter = v.pos.subtract(le.getPos());
                double dist = toCenter.length();
                if (dist < 0.3) continue;

                Vec3d pull = toCenter.normalize().multiply(PULL_STRENGTH * (1.0 - dist / RADIUS));
                le.addVelocity(pull.x, 0.05 + pull.y * 0.3, pull.z);
                le.velocityModified = true;
                le.damage(world.getDamageSources().magic(), DAMAGE_PER_TICK);

                // Caught creatures shed a streak of energy into the core
                if (age % 2 == 0) {
                    Vec3d c = le.getBoundingBox().getCenter();
                    Vec3d vel = v.pos.subtract(c).normalize().multiply(0.35);
                    world.spawnParticles(ParticleTypes.REVERSE_PORTAL, c.x, c.y, c.z, 0, vel.x, vel.y, vel.z, 1.0);
                }
                if (world.random.nextInt(35) == 0) {
                    world.playSound(null, le.getBlockPos(), SoundEvents.ENTITY_PHANTOM_FLAP, SoundCategory.PLAYERS, 0.6f, 1.8f);
                }
            }

            if (age % 12 == 0) {
                world.playSound(null, BlockPos.ofFloored(v.pos), SoundEvents.BLOCK_BEACON_AMBIENT, SoundCategory.PLAYERS, 0.6f, 1.5f);
            }
            if (age % 16 == 4) {
                world.playSound(null, BlockPos.ofFloored(v.pos), SoundEvents.ENTITY_BREEZE_WHIRL, SoundCategory.PLAYERS, 1.2f, 0.6f);
            }
        }
    }

    private static void spawnParticles(ServerWorld world, Vortex v, int age) {
        Random rand = world.getRandom();
        Vec3d c = v.pos;
        double spin = age * 0.3;
        // Grows open over the first half-second
        double open = Math.min(1.0, age / 10.0);

        // Spiral arms: particles placed along three log-spirals, flung tangentially inward
        for (int arm = 0; arm < 3; arm++) {
            for (int i = 0; i < 18; i++) {
                double t = i / 18.0;
                double r = RADIUS * open * (0.15 + 0.85 * t);
                double a = spin + arm * (MathHelper.TAU / 3.0) + t * 4.0;
                double y = c.y + (rand.nextDouble() - 0.5) * 0.6 * t;
                Vec3d p = new Vec3d(c.x + Math.cos(a) * r, y, c.z + Math.sin(a) * r);
                Vec3d tangent = new Vec3d(-Math.sin(a), 0, Math.cos(a)).multiply(0.25 + 0.2 * t);
                Vec3d inward = c.subtract(p).normalize().multiply(0.12);
                Vec3d vel = tangent.add(inward);
                world.spawnParticles(i % 3 == 0 ? ParticleTypes.REVERSE_PORTAL : ParticleTypes.PORTAL, p.x, p.y, p.z, 0, vel.x, vel.y, vel.z, 1.0);
            }
        }

        // Accretion disc: a tight bright ring racing around the core
        int ring = 24;
        for (int i = 0; i < ring; i++) {
            double a = -spin * 2.0 + i * MathHelper.TAU / ring;
            double r = 1.2 * open;
            world.spawnParticles(RIM, c.x + Math.cos(a) * r, c.y, c.z + Math.sin(a) * r, 1, 0, 0.02, 0, 0);
        }

        // The singularity: an ink-black core that swallows light
        world.spawnParticles(SINGULARITY, c.x, c.y, c.z, 6, 0.18, 0.18, 0.18, 0);
        if (age % 3 == 0) world.spawnParticles(ParticleTypes.SQUID_INK, c.x, c.y, c.z, 1, 0.1, 0.1, 0.1, 0.0);

        // A thin funnel of wind above and below the core
        for (int i = 0; i < 6; i++) {
            double h = (rand.nextDouble() - 0.5) * 5.0;
            double r = 0.3 + Math.abs(h) * 0.35;
            double a = spin * 1.5 + rand.nextDouble() * MathHelper.TAU;
            Vec3d p = new Vec3d(c.x + Math.cos(a) * r, c.y + h, c.z + Math.sin(a) * r);
            Vec3d vel = new Vec3d(-Math.sin(a) * 0.2, -Math.signum(h) * 0.1, Math.cos(a) * 0.2);
            world.spawnParticles(ParticleTypes.CLOUD, p.x, p.y, p.z, 0, vel.x * 0.5, vel.y * 0.5, vel.z * 0.5, 1.0);
        }

        // Debris torn up from the ground and sucked in
        if (v.ground != null && age % 2 == 0) {
            BlockStateParticleEffect debris = new BlockStateParticleEffect(ParticleTypes.BLOCK, v.ground);
            for (int i = 0; i < 3; i++) {
                double a = rand.nextDouble() * MathHelper.TAU;
                double r = RADIUS * open * (0.5 + rand.nextDouble() * 0.5);
                Vec3d p = new Vec3d(c.x + Math.cos(a) * r, c.y - 0.3, c.z + Math.sin(a) * r);
                Vec3d vel = c.subtract(p).normalize().multiply(0.5).add(0, 0.25, 0);
                world.spawnParticles(debris, p.x, p.y, p.z, 0, vel.x, vel.y, vel.z, 1.0);
            }
        }
    }

    /** The vortex implodes with a flash and a ring of energy blown outward. */
    private static void collapse(ServerWorld world, Vortex v) {
        Vec3d c = v.pos;
        world.spawnParticles(ParticleTypes.FLASH, c.x, c.y, c.z, 1, 0, 0, 0, 0);
        for (int i = 0; i < 40; i++) {
            double a = i * MathHelper.TAU / 40;
            world.spawnParticles(ParticleTypes.REVERSE_PORTAL, c.x, c.y, c.z, 0, Math.cos(a) * 0.6, 0.05, Math.sin(a) * 0.6, 1.0);
        }
        world.spawnParticles(ParticleTypes.PORTAL, c.x, c.y, c.z, 40, 0.5, 0.5, 0.5, 1.0);
        world.playSound(null, BlockPos.ofFloored(c), SoundEvents.BLOCK_BEACON_DEACTIVATE, SoundCategory.PLAYERS, 0.9f, 1.0f);
        world.playSound(null, BlockPos.ofFloored(c), SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 1.0f, 0.6f);
    }

    private static class Vortex {
        final Vec3d pos;
        final long spawnTick;
        final UUID casterId;
        final BlockState ground;

        Vortex(Vec3d pos, long spawnTick, UUID casterId, BlockState ground) {
            this.pos = pos;
            this.spawnTick = spawnTick;
            this.casterId = casterId;
            this.ground = ground;
        }
    }
}
