package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.decoration.Brightness;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.util.TempEntities;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Charges a sphere of compressed sound in front of the staff, then lets it fly. It steers toward wherever you keep
 * looking and detonates on the first thing it touches: a sonic boom that tears through everything nearby and hurls
 * it away, followed a moment later by a wider aftershock.
 */
public class BoomingSpell implements Spell {
    private static final double CAST_RANGE = 120.0;
    private static final int CHARGE_TICKS = 12;
    private static final double SPEED = 1.1;
    private static final double TURN = 0.30;
    private static final int LIFE_TICKS = 90;
    private static final double HIT_RADIUS = 0.6;

    private static final double BOOM_RADIUS = 7.0;
    private static final float MAX_DAMAGE = 28.0f;
    private static final float EDGE_FRACTION = 0.35f;     // damage left at the very edge of the blast
    private static final int RING_TICKS = 7;
    private static final int AFTERSHOCK_DELAY = 8;
    private static final double AFTERSHOCK_RADIUS = 10.0;
    private static final float AFTERSHOCK_DAMAGE = 8.0f;

    private static final float SHELL_SCALE = 0.75f;
    private static final float CORE_SCALE = 0.4f;

    private static final class Orb {
        final ServerWorld world;
        final UUID owner;
        final DisplayEntity.BlockDisplayEntity shell;
        final DisplayEntity.BlockDisplayEntity core;
        Vec3d pos;
        Vec3d vel = Vec3d.ZERO;
        Vec3d launchDir;
        int age = 0;

        Orb(ServerWorld world, UUID owner, DisplayEntity.BlockDisplayEntity shell, DisplayEntity.BlockDisplayEntity core, Vec3d pos) {
            this.world = world;
            this.owner = owner;
            this.shell = shell;
            this.core = core;
            this.pos = pos;
        }
    }

    private static final class Blast {
        final ServerWorld world;
        final UUID owner;
        final Vec3d pos;
        int age = 0;

        Blast(ServerWorld world, UUID owner, Vec3d pos) {
            this.world = world;
            this.owner = owner;
            this.pos = pos;
        }
    }

    private static final List<Orb> ORBS = new ArrayList<>();
    private static final List<Blast> BLASTS = new ArrayList<>();
    private static boolean registered = false;

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            Iterator<Orb> orbs = ORBS.iterator();
            while (orbs.hasNext()) {
                Orb o = orbs.next();
                if (o.world == world && !tickOrb(o)) {
                    TempEntities.discard(o.shell);
                    TempEntities.discard(o.core);
                    orbs.remove();
                }
            }
            Iterator<Blast> blasts = BLASTS.iterator();
            while (blasts.hasNext()) {
                Blast b = blasts.next();
                if (b.world == world && !tickBlast(b)) blasts.remove();
            }
        });
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        ensureRegistered();
        ServerWorld sw = (ServerWorld) world;

        Vec3d start = chargePoint(player);
        DisplayEntity.BlockDisplayEntity shell = display(sw, Blocks.WHITE_STAINED_GLASS, start);
        DisplayEntity.BlockDisplayEntity core = display(sw, Blocks.SEA_LANTERN, start);
        if (shell == null || core == null) return false;
        ORBS.add(new Orb(sw, player.getUuid(), shell, core, start));

        sw.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENTITY_WARDEN_SONIC_CHARGE, SoundCategory.PLAYERS, 1.0f, 1.5f);
        return true;
    }

    private static DisplayEntity.BlockDisplayEntity display(ServerWorld world, net.minecraft.block.Block block, Vec3d at) {
        DisplayEntity.BlockDisplayEntity d = EntityType.BLOCK_DISPLAY.create(world);
        if (d == null) return null;
        d.setBlockState(block.getDefaultState());
        d.setBrightness(Brightness.FULL);
        d.setTeleportDuration(1);
        d.setViewRange(4.0f);
        d.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
        d.setTransformation(cube(0, 0f, 1));
        TempEntities.track(d);
        world.spawnEntity(d);
        return d;
    }

    private static Vec3d chargePoint(PlayerEntity player) {
        return player.getEyePos().add(player.getRotationVector().multiply(1.6)).add(0, -0.2, 0);
    }

    /** A centred cube of size {@code scale}, tumbling at {@code dir} times the base spin. */
    private static AffineTransformation cube(int age, float scale, int dir) {
        return new AffineTransformation(new Matrix4f()
                .rotateXYZ(dir * age * 0.25f, dir * age * 0.18f, age * 0.1f)
                .scale(Math.max(scale, 0.001f))
                .translate(-0.5f, -0.5f, -0.5f));
    }

    // ---------------------------------------------------------------------
    // The orb
    // ---------------------------------------------------------------------

    /** Returns false once the orb is spent. */
    private static boolean tickOrb(Orb o) {
        ServerWorld world = o.world;
        PlayerEntity owner = world.getPlayerByUuid(o.owner);
        if (owner == null || !owner.isAlive()) return false;
        o.age++;

        if (o.age <= CHARGE_TICKS) {
            // Gathering: held in front of the staff, growing as sound is drawn into it
            float t = o.age / (float) CHARGE_TICKS;
            o.pos = chargePoint(owner);
            place(o, SHELL_SCALE * t, CORE_SCALE * t);
            for (int i = 0; i < 6; i++) {
                Vec3d dir = new Vec3d(world.random.nextGaussian(), world.random.nextGaussian(), world.random.nextGaussian()).normalize();
                Vec3d from = o.pos.add(dir.multiply(1.6));
                world.spawnParticles(ParticleTypes.END_ROD, from.x, from.y, from.z, 0, -dir.x, -dir.y, -dir.z, 0.12);
            }
            if (o.age == CHARGE_TICKS) {
                o.launchDir = owner.getRotationVector().normalize();
                o.vel = o.launchDir.multiply(SPEED);
                world.spawnParticles(ParticleTypes.SONIC_BOOM, o.pos.x, o.pos.y, o.pos.z, 1, 0, 0, 0, 0);
                world.playSound(null, o.pos.x, o.pos.y, o.pos.z, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.PLAYERS, 0.8f, 1.8f);
            }
            return true;
        }
        if (o.age > CHARGE_TICKS + LIFE_TICKS) {
            detonate(o, owner, o.pos);
            return false;
        }

        // Steer toward whatever the caster is looking at, but never turn back on them
        Vec3d eye = owner.getEyePos();
        Vec3d look = owner.getRotationVector().normalize();
        BlockHitResult aim = world.raycast(new RaycastContext(eye, eye.add(look.multiply(CAST_RANGE)),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, owner));
        Vec3d aimPos = aim.getType() == HitResult.Type.BLOCK ? aim.getPos() : eye.add(look.multiply(CAST_RANGE));
        Vec3d desired = aimPos.subtract(o.pos);
        if (desired.lengthSquared() > 1.0e-4) {
            Vec3d steered = o.vel.multiply(1.0 - TURN).add(desired.normalize().multiply(SPEED * TURN));
            if (steered.dotProduct(o.launchDir) < 0) steered = o.launchDir.multiply(SPEED * 0.1);
            o.vel = steered.lengthSquared() > 1.0e-6 ? steered.normalize().multiply(SPEED) : o.launchDir.multiply(SPEED);
        }

        Vec3d from = o.pos;
        Vec3d to = from.add(o.vel);
        BlockHitResult wall = world.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, owner));
        if (wall.getType() == HitResult.Type.BLOCK) {
            detonate(o, owner, wall.getPos());
            return false;
        }
        List<Entity> hit = world.getOtherEntities(owner, new Box(from, to).expand(HIT_RADIUS),
                e -> e instanceof LivingEntity && e.isAlive() && !e.isSpectator());
        if (!hit.isEmpty()) {
            detonate(o, owner, hit.get(0).getBoundingBox().getCenter());
            return false;
        }

        o.pos = to;
        place(o, SHELL_SCALE, CORE_SCALE);

        // Trail: a bright wake, a ring of sparks spinning around the flight path, and a ripple now and then
        world.spawnParticles(ParticleTypes.END_ROD, to.x, to.y, to.z, 3, 0.08, 0.08, 0.08, 0.01);
        world.spawnParticles(ParticleTypes.ENCHANTED_HIT, to.x, to.y, to.z, 2, 0.2, 0.2, 0.2, 0.0);
        Vec3d dir = o.vel.normalize();
        Vec3d helper = Math.abs(dir.y) > 0.9 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
        Vec3d u = dir.crossProduct(helper).normalize();
        Vec3d v = dir.crossProduct(u).normalize();
        for (int i = 0; i < 4; i++) {
            double a = o.age * 0.6 + i * Math.PI / 2;
            Vec3d p = from.add(u.multiply(Math.cos(a) * 0.6)).add(v.multiply(Math.sin(a) * 0.6));
            world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, p.x, p.y, p.z, 1, 0, 0, 0, 0);
        }
        if (o.age % 5 == 0) world.spawnParticles(ParticleTypes.SONIC_BOOM, from.x, from.y, from.z, 1, 0, 0, 0, 0);
        if (o.age % 4 == 0) {
            world.playSound(null, to.x, to.y, to.z, SoundEvents.BLOCK_BEACON_AMBIENT, SoundCategory.PLAYERS, 2.0f, 2.0f);
        }
        return true;
    }

    private static void place(Orb o, float shell, float core) {
        for (var d : new DisplayEntity.BlockDisplayEntity[]{o.shell, o.core}) {
            d.setPosition(o.pos.x, o.pos.y, o.pos.z);
            d.setStartInterpolation(0);
            d.setInterpolationDuration(1);
        }
        // Pulse slightly so it looks alive
        float pulse = 1f + MathHelper.sin(o.age * 0.8f) * 0.08f;
        o.shell.setTransformation(cube(o.age, shell * pulse, 1));
        o.core.setTransformation(cube(o.age, core, -1));
    }

    // ---------------------------------------------------------------------
    // The boom
    // ---------------------------------------------------------------------

    private static void detonate(Orb o, PlayerEntity owner, Vec3d pos) {
        ServerWorld world = o.world;
        hurt(world, owner, pos, BOOM_RADIUS, true);

        world.spawnParticles(ParticleTypes.SONIC_BOOM, pos.x, pos.y, pos.z, 1, 0, 0, 0, 0);
        world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, pos.x, pos.y, pos.z, 1, 0, 0, 0, 0);
        world.spawnParticles(ParticleTypes.FLASH, pos.x, pos.y, pos.z, 3, 0, 0, 0, 0);
        world.spawnParticles(ParticleTypes.END_ROD, pos.x, pos.y, pos.z, 150, 0.2, 0.2, 0.2, 0.6);
        world.spawnParticles(ParticleTypes.ENCHANTED_HIT, pos.x, pos.y, pos.z, 60, 1.0, 1.0, 1.0, 0.8);
        world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.PLAYERS, 4.0f, 1.0f);
        world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 2.0f, 0.5f);
        world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ITEM_TRIDENT_THUNDER, SoundCategory.PLAYERS, 1.5f, 1.4f);

        BLASTS.add(new Blast(world, o.owner, pos));
    }

    /** Returns false when the blast's rings and aftershock are done. */
    private static boolean tickBlast(Blast b) {
        b.age++;
        ServerWorld world = b.world;
        Vec3d c = b.pos;

        if (b.age <= RING_TICKS) {
            // Rings of sonic ripples racing outward, one flat and one standing upright
            double r = BOOM_RADIUS * b.age / RING_TICKS;
            int points = 8 + (int) (r * 2);
            for (int i = 0; i < points; i++) {
                double a = i * MathHelper.TAU / points;
                world.spawnParticles(ParticleTypes.SONIC_BOOM, c.x + Math.cos(a) * r, c.y, c.z + Math.sin(a) * r, 1, 0, 0, 0, 0);
                if (i % 2 == 0) {
                    world.spawnParticles(ParticleTypes.END_ROD, c.x + Math.cos(a) * r, c.y + Math.sin(a) * r, c.z, 1, 0, 0, 0, 0);
                }
            }
        }

        if (b.age == AFTERSHOCK_DELAY) {
            PlayerEntity owner = world.getPlayerByUuid(b.owner);
            hurt(world, owner, c, AFTERSHOCK_RADIUS, false);
            for (int i = 0; i < 48; i++) {
                double a = i * MathHelper.TAU / 48;
                world.spawnParticles(ParticleTypes.CLOUD, c.x, c.y, c.z, 0, Math.cos(a), 0.05, Math.sin(a), 1.2);
            }
            world.spawnParticles(ParticleTypes.SONIC_BOOM, c.x, c.y, c.z, 1, 0, 0, 0, 0);
            world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.PLAYERS, 3.0f, 0.6f);
        }
        return b.age < Math.max(RING_TICKS, AFTERSHOCK_DELAY);
    }

    /** The main blast falls off from full damage at the centre; the aftershock hits evenly and pushes harder. */
    private static void hurt(ServerWorld world, PlayerEntity owner, Vec3d pos, double radius, boolean main) {
        DamageSource source = world.getDamageSources().explosion(null, owner);
        List<Entity> victims = world.getOtherEntities(owner, new Box(pos, pos).expand(radius),
                e -> e instanceof LivingEntity && e.isAlive() && (owner == null || !e.isTeammate(owner)));
        for (Entity e : victims) {
            double dist = e.getBoundingBox().getCenter().distanceTo(pos);
            if (dist > radius) continue;
            double closeness = 1.0 - dist / radius;
            float damage = main ? MAX_DAMAGE * (float) (EDGE_FRACTION + (1 - EDGE_FRACTION) * closeness) : AFTERSHOCK_DAMAGE;
            if (!main) ((LivingEntity) e).timeUntilRegen = 0;
            e.damage(source, damage);

            Vec3d away = e.getPos().subtract(pos);
            away = away.lengthSquared() > 1.0e-4 ? away.normalize() : new Vec3d(0, 1, 0);
            double push = main ? 1.2 + 1.0 * closeness : 0.9;
            e.addVelocity(away.x * push, 0.35 + 0.45 * closeness, away.z * push);
            e.velocityModified = true;
        }
    }
}
