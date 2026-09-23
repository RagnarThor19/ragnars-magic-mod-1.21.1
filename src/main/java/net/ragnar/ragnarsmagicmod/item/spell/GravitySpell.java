package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.util.SkyDrop;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Plants a beam of reversed gravity that reaches high into the sky. Anything standing in the beam,
 * caster and players included, is carried upward; everything outside stays on the ground.
 * Use it to climb cliffs and mountains (step out sideways onto a ledge, sneak to sink back down),
 * or to haul mobs up and let them drop when the beam fades.
 * Players still inside when it fades are given Slow Falling; mobs are not.
 */
public class GravitySpell implements Spell {
    private static final double RANGE = 48.0;
    private static final double RADIUS = 1.5;
    private static final double MAX_HEIGHT = 96.0;
    private static final int DURATION_TICKS = 20 * 12;
    private static final double RISE_SPEED = 0.6;       // blocks/tick at full lift
    private static final double LIFT_ACCEL = 0.12;
    private static final double SINK_SPEED = 0.18;       // while sneaking
    private static final double MOB_CENTERING = 0.04;   // keeps mobs from drifting out on the way up
    private static final int SAFE_FALL_TICKS = 20 * 10;
    private static final double VIEW_RANGE = 160.0;

    private static final List<Beam> BEAMS = new ArrayList<>();
    private static boolean registered = false;

    private static final class Beam {
        final ServerWorld world;
        final Vec3d floor;
        final double top;
        final Set<Integer> lifted = new HashSet<>();
        int age = 0;

        Beam(ServerWorld world, Vec3d floor, double top) {
            this.world = world;
            this.floor = floor;
            this.top = top;
        }
    }

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            Iterator<Beam> it = BEAMS.iterator();
            while (it.hasNext()) {
                Beam b = it.next();
                if (b.world == world && !tick(b)) it.remove();
            }
        });
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        ensureRegistered();
        ServerWorld sw = (ServerWorld) world;

        Vec3d floor = SkyDrop.aimedGround(sw, player, RANGE);
        // The beam reaches up until the sky limit, or a ceiling if there is one
        double limit = Math.min(floor.y + MAX_HEIGHT, world.getTopY());
        BlockHitResult ceiling = world.raycast(new RaycastContext(floor.add(0, 0.5, 0), new Vec3d(floor.x, limit, floor.z),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, ShapeContext.absent()));
        double top = ceiling.getType() == HitResult.Type.BLOCK ? ceiling.getPos().y : limit;
        BEAMS.add(new Beam(sw, floor, top));

        world.playSound(null, floor.x, floor.y, floor.z, SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 1.5f, 1.3f);
        world.playSound(null, floor.x, floor.y, floor.z, SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.PLAYERS, 1.0f, 1.5f);
        world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_EVOKER_CAST_SPELL, SoundCategory.PLAYERS, 0.7f, 1.6f);
        return true;
    }

    /** Returns false once the beam has faded. */
    private static boolean tick(Beam b) {
        b.age++;
        if (b.age > DURATION_TICKS) {
            fade(b);
            return false;
        }

        Set<Integer> inside = new HashSet<>();
        for (LivingEntity e : inBeam(b)) {
            inside.add(e.getId());
            if (b.lifted.add(e.getId())) {
                b.world.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.ENTITY_BREEZE_JUMP, SoundCategory.PLAYERS, 0.8f, 1.2f);
            }
            lift(b, e);
        }
        b.lifted.retainAll(inside);

        render(b);
        if (b.age % 40 == 1) {
            b.world.playSound(null, b.floor.x, b.floor.y, b.floor.z, SoundEvents.BLOCK_BEACON_AMBIENT, SoundCategory.PLAYERS, 1.5f, 1.5f);
        }
        return true;
    }

    private static List<LivingEntity> inBeam(Beam b) {
        Box column = new Box(b.floor.x - RADIUS, b.floor.y - 0.5, b.floor.z - RADIUS, b.floor.x + RADIUS, b.top, b.floor.z + RADIUS);
        return b.world.getEntitiesByClass(LivingEntity.class, column, e -> {
            if (!e.isAlive() || e.isSpectator()) return false;
            if (e instanceof PlayerEntity p && p.getAbilities().flying) return false;
            double dx = e.getX() - b.floor.x, dz = e.getZ() - b.floor.z;
            return dx * dx + dz * dz <= RADIUS * RADIUS;
        });
    }

    private static void lift(Beam b, LivingEntity e) {
        Vec3d v = e.getVelocity();
        double vy;
        if (e.isSneaking()) {
            vy = -SINK_SPEED;
        } else {
            vy = Math.min(RISE_SPEED, Math.max(v.y, 0) + LIFT_ACCEL);
            // Ease to a stop just under the top of the beam
            double headroom = b.top - (e.getY() + e.getHeight());
            if (headroom < 3.0) vy = Math.min(vy, MathHelper.clamp(headroom * 0.2, -0.2, RISE_SPEED));
        }

        double vx = v.x, vz = v.z;
        if (!(e instanceof PlayerEntity)) {
            // Players steer freely (to step off onto a ledge); mobs are kept in the middle
            vx = vx * 0.8 + (b.floor.x - e.getX()) * MOB_CENTERING;
            vz = vz * 0.8 + (b.floor.z - e.getZ()) * MOB_CENTERING;
        }
        e.setVelocity(vx, vy, vz);
        e.velocityModified = true;
        e.fallDistance = 0;   // stepping off at the top of a climb shouldn't hurt

        if (b.age % 3 == 0) {
            b.world.spawnParticles(ParticleTypes.REVERSE_PORTAL, e.getX(), e.getY(), e.getZ(), 2, e.getWidth() * 0.4, 0.05, e.getWidth() * 0.4, 0.02);
        }
    }

    private static void fade(Beam b) {
        b.world.playSound(null, b.floor.x, b.floor.y, b.floor.z, SoundEvents.BLOCK_BEACON_DEACTIVATE, SoundCategory.PLAYERS, 1.5f, 1.2f);
        for (LivingEntity e : inBeam(b)) {
            if (e instanceof PlayerEntity) {
                e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, SAFE_FALL_TICKS, 0, false, true, true));
            }
        }
    }

    private static void render(Beam b) {
        ServerWorld world = b.world;
        var rand = world.random;
        Vec3d c = b.floor;
        double height = b.top - c.y;

        // Base ring
        if (b.age % 2 == 0) {
            int points = 20;
            double spin = b.age * 0.1;
            for (int i = 0; i < points; i++) {
                double a = spin + i * Math.PI * 2.0 / points;
                emit(world, ParticleTypes.END_ROD, c.add(Math.cos(a) * RADIUS, 0.1, Math.sin(a) * RADIUS), 0, 0, 0.05, 0);
            }
        }

        // Twin spirals climbing the column
        double spin = b.age * 0.25;
        for (int strand = 0; strand < 2; strand++) {
            for (int i = 0; i < 6; i++) {
                double y = rand.nextDouble() * height;
                double a = spin + strand * Math.PI + y * 0.35;
                emit(world, ParticleTypes.END_ROD, c.add(Math.cos(a) * RADIUS * 0.9, y, Math.sin(a) * RADIUS * 0.9), 0, 0, 0.08, 0);
            }
        }

        // Motes rising through the core of the beam
        for (int i = 0; i < 18; i++) {
            double a = rand.nextDouble() * Math.PI * 2.0;
            double r = Math.sqrt(rand.nextDouble()) * RADIUS * 0.8;
            double y = rand.nextDouble() * height;
            ParticleEffect type = i % 3 == 0 ? ParticleTypes.REVERSE_PORTAL : ParticleTypes.ENCHANT;
            emit(world, type, c.add(Math.cos(a) * r, y, Math.sin(a) * r), 0, 0, 0.25, 0);
        }
        if (rand.nextInt(3) == 0) {
            emit(world, ParticleTypes.CLOUD, c.add((rand.nextDouble() - 0.5) * RADIUS, 0.2, (rand.nextDouble() - 0.5) * RADIUS), 0, 0, 0.15, 0);
        }
    }

    /** Single particle with the given velocity, sent to everyone within {@link #VIEW_RANGE} so the whole beam can be seen. */
    private static void emit(ServerWorld world, ParticleEffect type, Vec3d p, int count, double vx, double vy, double vz) {
        for (ServerPlayerEntity viewer : world.getPlayers()) {
            if (viewer.squaredDistanceTo(p) <= VIEW_RANGE * VIEW_RANGE) {
                world.spawnParticles(viewer, type, true, p.x, p.y, p.z, count, vx, vy, vz, 1.0);
            }
        }
    }
}
