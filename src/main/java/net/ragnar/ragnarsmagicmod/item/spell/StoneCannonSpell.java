package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.PointedDripstoneBlock;
import net.minecraft.block.enums.Thickness;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.util.TempEntities;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Rudeus's Stone Cannon. A small rock spike forms beside the staff (off to the side, so it never blocks your view)
 * and spins up faster and faster for {@link #CHARGE_TICKS}, then fires dead straight down your line of sight at
 * great speed, drilling through several creatures in a row. It rewards precision: a clean hit does
 * {@link #DIRECT_DAMAGE}, but a near miss only grazes for a fraction of that.
 */
public class StoneCannonSpell implements Spell {
    private static final int CHARGE_TICKS = 30;            // 1.5 seconds
    private static final double SPEED = 4.0;               // blocks per tick
    private static final double RANGE = 90.0;
    private static final int PIERCE_LIMIT = 6;
    private static final float DIRECT_DAMAGE = 20.0f;
    // Near misses: how far the spike's path can pass from a hitbox and still clip it, and for how much
    private static final double DIRECT_MARGIN = 0.15;     // a little forgiveness around the hitbox still counts as clean
    private static final double GRAZE_CLOSE = 0.45;
    private static final float GRAZE_CLOSE_DAMAGE = 4.0f;
    private static final double GRAZE_FAR = 0.75;
    private static final float GRAZE_FAR_DAMAGE = 1.0f;
    private static final int STUCK_TICKS = 20;

    // Where the spike forms while charging: beside and below the view, pointing at the crosshair
    private static final double HOLD_FORWARD = 1.3;
    private static final double HOLD_SIDE = 0.5;
    private static final double HOLD_DOWN = 0.4;
    private static final float MAX_SPIN = 1.5f;            // radians per tick when fully charged

    private static final BlockState ROCK = Blocks.DRIPSTONE_BLOCK.getDefaultState();
    private static final BlockState TIP = Blocks.POINTED_DRIPSTONE.getDefaultState()
            .with(PointedDripstoneBlock.VERTICAL_DIRECTION, Direction.UP)
            .with(PointedDripstoneBlock.THICKNESS, Thickness.TIP);

    /** One piece of the spike: width, length, where it starts along the spike's axis, and its twist. */
    private record Part(BlockState state, float width, float length, float start, float twist) {}

    private static final Part[] PARTS = {
            new Part(ROCK, 0.30f, 0.34f, -0.65f, 0f),
            new Part(ROCK, 0.24f, 0.34f, -0.33f, (float) Math.toRadians(45)),
            new Part(TIP, 0.55f, 0.75f, -0.02f, 0f),
    };

    private enum Phase { CHARGING, FLYING, STUCK }

    private static final class Shot {
        final ServerWorld world;
        final UUID owner;
        final DisplayEntity.BlockDisplayEntity[] parts = new DisplayEntity.BlockDisplayEntity[PARTS.length];
        final Set<UUID> hit = new HashSet<>();
        Phase phase = Phase.CHARGING;
        Vec3d pos;
        Vec3d dir;
        float spin = 0;
        double travelled = 0;
        int age = 0;
        int stuck = 0;

        Shot(ServerWorld world, UUID owner, Vec3d pos, Vec3d dir) {
            this.world = world;
            this.owner = owner;
            this.pos = pos;
            this.dir = dir;
        }
    }

    private static final List<Shot> ACTIVE = new ArrayList<>();
    private static boolean registered = false;

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            Iterator<Shot> it = ACTIVE.iterator();
            while (it.hasNext()) {
                Shot s = it.next();
                if (s.world == world && !tick(s)) {
                    for (var d : s.parts) if (d != null) TempEntities.discard(d);
                    it.remove();
                }
            }
        });
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        ensureRegistered();
        ServerWorld sw = (ServerWorld) world;
        // One cannon at a time
        for (Shot s : ACTIVE) if (s.owner.equals(player.getUuid()) && s.phase == Phase.CHARGING) return false;

        Vec3d pos = holdPoint(player);
        Shot shot = new Shot(sw, player.getUuid(), pos, aimFrom(sw, player, pos));
        for (int i = 0; i < PARTS.length; i++) {
            DisplayEntity.BlockDisplayEntity d = EntityType.BLOCK_DISPLAY.create(sw);
            if (d == null) continue;
            d.setBlockState(PARTS[i].state());
            d.setTeleportDuration(1);
            d.setViewRange(3.0f);
            d.refreshPositionAndAngles(pos.x, pos.y, pos.z, 0f, 0f);
            d.setTransformation(partTransform(PARTS[i], shot.dir, 0, 0.05f));
            TempEntities.track(d);
            sw.spawnEntity(d);
            shot.parts[i] = d;
        }
        ACTIVE.add(shot);
        sw.playSound(null, pos.x, pos.y, pos.z, SoundEvents.BLOCK_POINTED_DRIPSTONE_PLACE, SoundCategory.PLAYERS, 1.0f, 0.8f);
        return true;
    }

    // ---------------------------------------------------------------------
    // Timeline
    // ---------------------------------------------------------------------

    /** Returns false once the shot is finished. */
    private static boolean tick(Shot s) {
        ServerWorld world = s.world;
        PlayerEntity owner = world.getPlayerByUuid(s.owner);
        s.age++;

        switch (s.phase) {
            case CHARGING -> {
                if (owner == null || !owner.isAlive()) return false;
                float t = s.age / (float) CHARGE_TICKS;
                // Spins up with an ease-in so the last half second really screams
                s.spin += MAX_SPIN * (0.1f + 0.9f * t * t);
                s.pos = holdPoint(owner);
                s.dir = aimFrom(world, owner, s.pos);
                float size = 0.35f + 0.65f * Math.min(1f, t * 1.4f);
                pose(s, size);

                // Grit spiralling in to build the spike
                if (s.age % 2 == 0) {
                    Vec3d off = new Vec3d(world.random.nextGaussian(), world.random.nextGaussian(), world.random.nextGaussian()).normalize().multiply(0.7);
                    Vec3d from = s.pos.add(off);
                    world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, ROCK), from.x, from.y, from.z, 0, -off.x, -off.y, -off.z, 0.15);
                }
                if (t > 0.5f) {
                    world.spawnParticles(ParticleTypes.CRIT, s.pos.x, s.pos.y, s.pos.z, 1, 0.08, 0.08, 0.08, 0.05);
                }
                if (s.age % 4 == 0) {
                    world.playSound(null, s.pos.x, s.pos.y, s.pos.z, SoundEvents.BLOCK_GRINDSTONE_USE, SoundCategory.PLAYERS, 0.35f + 0.4f * t, 0.6f + 1.2f * t);
                }
                if (s.age >= CHARGE_TICKS) fire(s, owner);
                return true;
            }
            case FLYING -> {
                return fly(s, owner);
            }
            default -> {
                s.spin += MAX_SPIN * 0.3f * (1f - s.stuck / (float) STUCK_TICKS);
                pose(s, 1f);
                if (++s.stuck >= STUCK_TICKS) {
                    world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, ROCK), s.pos.x, s.pos.y, s.pos.z, 20, 0.2, 0.2, 0.2, 0.1);
                    world.playSound(null, s.pos.x, s.pos.y, s.pos.z, SoundEvents.BLOCK_DRIPSTONE_BLOCK_BREAK, SoundCategory.PLAYERS, 1.0f, 1.2f);
                    return false;
                }
                return true;
            }
        }
    }

    private static void fire(Shot s, PlayerEntity owner) {
        ServerWorld world = s.world;
        // The shot itself leaves from the eye, straight along the crosshair, so what you aim at is what you hit
        s.dir = owner.getRotationVector().normalize();
        s.pos = owner.getEyePos().add(s.dir.multiply(0.8));
        s.phase = Phase.FLYING;
        for (var d : s.parts) if (d != null) d.refreshPositionAndAngles(s.pos.x, s.pos.y, s.pos.z, 0f, 0f);

        world.spawnParticles(ParticleTypes.EXPLOSION, s.pos.x, s.pos.y, s.pos.z, 1, 0, 0, 0, 0);
        world.spawnParticles(ParticleTypes.CLOUD, s.pos.x, s.pos.y, s.pos.z, 8, 0.1, 0.1, 0.1, 0.15);
        world.playSound(null, s.pos.x, s.pos.y, s.pos.z, SoundEvents.ENTITY_BREEZE_SHOOT, SoundCategory.PLAYERS, 1.5f, 0.6f);
        world.playSound(null, s.pos.x, s.pos.y, s.pos.z, SoundEvents.ITEM_TRIDENT_RIPTIDE_1.value(), SoundCategory.PLAYERS, 1.2f, 1.5f);
        world.playSound(null, s.pos.x, s.pos.y, s.pos.z, SoundEvents.BLOCK_STONE_BREAK, SoundCategory.PLAYERS, 1.2f, 0.6f);
    }

    /** Returns false once the spike has shattered or run out of range. */
    private static boolean fly(Shot s, PlayerEntity owner) {
        ServerWorld world = s.world;
        s.spin += MAX_SPIN;
        Vec3d from = s.pos;
        double step = Math.min(SPEED, RANGE - s.travelled);
        Vec3d to = from.add(s.dir.multiply(step));

        // Walls stop it; only creatures before the wall can be hit
        BlockHitResult wall = world.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, net.minecraft.block.ShapeContext.absent()));
        boolean blocked = wall.getType() == HitResult.Type.BLOCK;
        Vec3d end = blocked ? wall.getPos() : to;

        List<LivingEntity> near = world.getEntitiesByClass(LivingEntity.class, new Box(from, end).expand(GRAZE_FAR + 0.5),
                e -> e.isAlive() && !e.isSpectator() && !e.getUuid().equals(s.owner) && !s.hit.contains(e.getUuid()));
        // Nearest first, so it drills through them in order
        near.sort((a, b) -> Double.compare(a.squaredDistanceTo(from), b.squaredDistanceTo(from)));
        for (LivingEntity e : near) {
            if (s.hit.size() >= PIERCE_LIMIT) break;
            Box box = e.getBoundingBox();
            float damage;
            if (box.expand(DIRECT_MARGIN).raycast(from, end).isPresent()) damage = DIRECT_DAMAGE;
            else if (box.expand(GRAZE_CLOSE).raycast(from, end).isPresent()) damage = GRAZE_CLOSE_DAMAGE;
            else if (box.expand(GRAZE_FAR).raycast(from, end).isPresent()) damage = GRAZE_FAR_DAMAGE;
            else continue;
            s.hit.add(e.getUuid());
            strike(world, owner, e, s.dir, damage);
        }

        s.pos = end;
        s.travelled += from.distanceTo(end);
        pose(s, 1f);

        // A thin, spinning trail of grit
        for (int i = 0; i < 4; i++) {
            Vec3d p = from.lerp(end, i / 4.0);
            world.spawnParticles(i % 2 == 0 ? ParticleTypes.CRIT : ParticleTypes.CLOUD, p.x, p.y, p.z, 1, 0.02, 0.02, 0.02, 0.0);
        }
        world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, ROCK), end.x, end.y, end.z, 2, 0.05, 0.05, 0.05, 0.05);

        if (blocked) {
            s.pos = wall.getPos().subtract(s.dir.multiply(0.3));
            s.phase = Phase.STUCK;
            pose(s, 1f);
            var debris = new BlockStateParticleEffect(ParticleTypes.BLOCK, world.getBlockState(wall.getBlockPos()));
            world.spawnParticles(debris, end.x, end.y, end.z, 25, 0.2, 0.2, 0.2, 0.15);
            world.playSound(null, end.x, end.y, end.z, SoundEvents.BLOCK_POINTED_DRIPSTONE_LAND, SoundCategory.PLAYERS, 1.5f, 1.2f);
            world.playSound(null, end.x, end.y, end.z, SoundEvents.BLOCK_STONE_HIT, SoundCategory.PLAYERS, 1.5f, 0.6f);
            return true;
        }
        return s.travelled < RANGE - 1.0e-6;
    }

    private static void strike(ServerWorld world, PlayerEntity owner, LivingEntity e, Vec3d dir, float damage) {
        // A stone projectile: armour applies
        e.damage(owner != null ? world.getDamageSources().thrown(owner, owner) : world.getDamageSources().generic(), damage);
        double push = damage >= DIRECT_DAMAGE ? 0.6 : 0.15;
        e.addVelocity(dir.x * push, 0.1, dir.z * push);
        e.velocityModified = true;

        Vec3d c = e.getBoundingBox().getCenter();
        world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, ROCK), c.x, c.y, c.z, damage >= DIRECT_DAMAGE ? 20 : 6, 0.2, 0.3, 0.2, 0.15);
        if (damage >= DIRECT_DAMAGE) {
            world.spawnParticles(ParticleTypes.CRIT, c.x, c.y, c.z, 15, 0.2, 0.3, 0.2, 0.4);
            world.playSound(null, c.x, c.y, c.z, SoundEvents.ITEM_TRIDENT_HIT, SoundCategory.PLAYERS, 1.3f, 0.7f);
            world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, SoundCategory.PLAYERS, 1.2f, 0.8f);
        } else {
            world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_STONE_HIT, SoundCategory.PLAYERS, 1.0f, 1.4f);
        }
    }

    // ---------------------------------------------------------------------
    // Posing the spike
    // ---------------------------------------------------------------------

    private static Vec3d holdPoint(PlayerEntity player) {
        double yaw = Math.toRadians(player.getYaw());
        Vec3d forward = player.getRotationVector();
        Vec3d right = new Vec3d(-Math.cos(yaw), 0, -Math.sin(yaw));
        return player.getEyePos().add(forward.multiply(HOLD_FORWARD)).add(right.multiply(HOLD_SIDE)).add(0, -HOLD_DOWN, 0);
    }

    /** From the hold point toward whatever the crosshair is on, so the spike visibly aims where it will go. */
    private static Vec3d aimFrom(ServerWorld world, PlayerEntity player, Vec3d from) {
        Vec3d eye = player.getEyePos();
        Vec3d look = player.getRotationVector();
        BlockHitResult hit = world.raycast(new RaycastContext(eye, eye.add(look.multiply(RANGE)),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
        Vec3d target = hit.getType() == HitResult.Type.BLOCK ? hit.getPos() : eye.add(look.multiply(RANGE));
        Vec3d d = target.subtract(from);
        return d.lengthSquared() > 1.0e-4 ? d.normalize() : look;
    }

    private static void pose(Shot s, float size) {
        for (int i = 0; i < PARTS.length; i++) {
            DisplayEntity.BlockDisplayEntity d = s.parts[i];
            if (d == null) continue;
            d.setPosition(s.pos.x, s.pos.y, s.pos.z);
            d.setTransformation(partTransform(PARTS[i], s.dir, s.spin, size));
            d.setStartInterpolation(0);
            d.setInterpolationDuration(1);
        }
    }

    /** Lays a part along {@code dir} (the block's up axis becomes the spike's axis) and spins it about that axis. */
    private static AffineTransformation partTransform(Part p, Vec3d dir, float spin, float size) {
        Vector3f axis = dir.toVector3f().normalize();
        float angle = (spin + p.twist()) % MathHelper.TAU;
        return new AffineTransformation(new Matrix4f()
                .rotate(new Quaternionf().rotationTo(new Vector3f(0, 1, 0), axis))
                .scale(size)
                .rotateY(angle)
                .translate(0, p.start(), 0)
                .scale(p.width(), p.length(), p.width())
                .translate(-0.5f, 0, -0.5f));
    }
}
