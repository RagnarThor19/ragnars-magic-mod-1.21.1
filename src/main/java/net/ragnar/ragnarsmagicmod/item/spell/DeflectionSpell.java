package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ProjectileDeflection;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.network.DeflectionPayload;
import net.ragnar.ragnarsmagicmod.util.Deflectable;
import net.ragnar.ragnarsmagicmod.util.DeflectableShots;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A pane of clear magic glass stands in front of you for {@link #ACTIVE_TICKS} (about 0.6s). Anything thrown or shot
 * that actually strikes the pane is sent back wherever you are looking, a little faster than it came in: arrows,
 * fireballs, tridents and the like, and other casters' projectile spells - ice shards, rocks, wither skulls, ball
 * lightning, ricochet arrows, boulders, impulse cubes (see {@link Deflectable}), and sharp leaves, air cuts, stone
 * cannons, quiver arrows, swords, spraying arrows and booming orbs (see {@link DeflectableShots}). It's yours once it bounces, so it can
 * hit whoever sent it. Aim at them and it flies true: within {@link #AIM_ASSIST_DEGREES} of your crosshair, the pane
 * aims at them for you, allowing for the drop of things that fall. Miss the timing, or let it pass beside the pane,
 * and it hits you as normal. Your own projectiles pass straight through.
 * <p>
 * The pane itself is drawn by each client (DeflectionClient), fixed in front of the caster's eyes every frame, so it
 * holds perfectly still on screen; the server only tells them when it goes up, gets hit and comes down.
 */
public class DeflectionSpell implements Spell {
    public static final int ACTIVE_TICKS = 12;
    public static final int FADE_TICKS = 3;
    public static final double DISTANCE = 1.5;        // how far in front of the eyes the pane stands
    public static final double DROP = 0.2;            // and how far below eye level its middle is
    public static final double HALF_WIDTH = 1.5;      // 3 blocks wide
    public static final double HALF_HEIGHT = 1.3;     // 2.6 blocks tall
    private static final double SPEED_BOOST = 1.25;    // deflected projectiles come back this much faster
    private static final double MIN_SPEED = 0.8;
    public static final double AIM_ASSIST_DEGREES = 7.0;
    private static final double AIM_ASSIST_RANGE = 48.0;

    private static final class Barrier {
        final ServerWorld world;
        final UUID owner;
        final Set<UUID> deflected = new HashSet<>();
        final Set<DeflectableShots.Shot> deflectedShots = new HashSet<>();
        int age = 0;

        Barrier(ServerWorld world, UUID owner) {
            this.world = world;
            this.owner = owner;
        }
    }

    private static final List<Barrier> ACTIVE = new ArrayList<>();
    private static boolean registered = false;

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            Iterator<Barrier> it = ACTIVE.iterator();
            while (it.hasNext()) {
                Barrier b = it.next();
                if (b.world == world && !tick(b)) it.remove();
            }
        });
    }

    /** True while {@code player}'s pane can still turn things back. */
    public static boolean isActive(PlayerEntity player) {
        for (Barrier b : ACTIVE) {
            if (b.owner.equals(player.getUuid()) && b.age < ACTIVE_TICKS) return true;
        }
        return false;
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        ensureRegistered();
        ServerWorld sw = (ServerWorld) world;
        ACTIVE.removeIf(b -> b.owner.equals(player.getUuid()));
        ACTIVE.add(new Barrier(sw, player.getUuid()));
        if (player instanceof ServerPlayerEntity sp) {
            DeflectionPayload.broadcast(sp, new DeflectionPayload(sp.getId(), ACTIVE_TICKS + FADE_TICKS, false, 0f, 0f));
        }

        sw.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BLOCK_GLASS_PLACE, SoundCategory.PLAYERS, 1.0f, 1.5f);
        sw.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.0f, 1.8f);
        return true;
    }

    /** Returns false once the pane has gone. */
    private static boolean tick(Barrier b) {
        PlayerEntity player = b.world.getPlayerByUuid(b.owner);
        if (player == null || !player.isAlive()) return false;
        b.age++;
        Vec3d look = player.getRotationVector().normalize();
        Vec3d c = center(player);
        if (b.age <= ACTIVE_TICKS) {
            catchProjectiles(b, player, c, look);
            return true;
        }
        if (b.age == ACTIVE_TICKS + 1) {
            b.world.spawnParticles(ParticleTypes.WAX_OFF, c.x, c.y, c.z, 10, HALF_WIDTH * 0.6, HALF_HEIGHT * 0.6, 0.3, 0);
        }
        return b.age <= ACTIVE_TICKS + FADE_TICKS;
    }

    public static Vec3d center(PlayerEntity player) {
        return player.getEyePos().add(player.getRotationVector().normalize().multiply(DISTANCE)).add(0, -DROP, 0);
    }

    /** Horizontal axis across the pane (falls back to east when looking straight up or down). */
    public static Vec3d paneRight(Vec3d look) {
        Vec3d r = new Vec3d(0, 1, 0).crossProduct(look);
        return r.lengthSquared() < 1.0e-4 ? new Vec3d(1, 0, 0) : r.normalize();
    }

    /** Anything whose path this tick crosses the pane (not beside it) is sent back. */
    private static void catchProjectiles(Barrier b, PlayerEntity player, Vec3d c, Vec3d look) {
        Vec3d right = paneRight(look);
        Vec3d up = right.crossProduct(look).normalize();
        Box area = new Box(c, c).expand(HALF_WIDTH + 6, HALF_HEIGHT + 6, HALF_WIDTH + 6);

        for (Entity e : b.world.getOtherEntities(player, area, e -> e.isAlive() && (e instanceof ProjectileEntity || e instanceof Deflectable))) {
            if (b.deflected.contains(e.getUuid())) continue;
            Vec3d vel;
            double reach = 1;
            if (e instanceof Deflectable d) {
                if (player.getUuid().equals(d.deflectOwner())) continue;
                vel = d.deflectVelocity();
                reach = 2; // they move themselves, before the pane gets a look in, so look a little further ahead
            } else {
                if (((ProjectileEntity) e).getOwner() == player) continue;
                vel = e.getVelocity();
            }
            // Anything big (a boulder) is stopped if any of it meets the glass, not just its middle
            double[] at = strikePoint(c, right, up, look, e.getBoundingBox().getCenter(), vel, reach, Math.max(e.getWidth(), e.getHeight()) * 0.5);
            if (at == null) continue;
            Vec3d hit = c.add(right.multiply(at[0])).add(up.multiply(at[1]));
            deflect(b, player, e, hit, look, vel.length());
            struck(b, player, hit, at);
        }

        // Spells' own shots that aren't entities (sharp leaves, stone cannons, swords...)
        for (DeflectableShots.Shot shot : DeflectableShots.in(b.world)) {
            if (b.deflectedShots.contains(shot) || player.getUuid().equals(shot.deflectOwner())) continue;
            Vec3d vel = shot.deflectVelocity();
            if (shot.pos().squaredDistanceTo(c) > 100) continue;
            double[] at = strikePoint(c, right, up, look, shot.pos(), vel, 2, shot.radius());
            if (at == null) continue;
            Vec3d hit = c.add(right.multiply(at[0])).add(up.multiply(at[1]));
            double speed = Math.max(MIN_SPEED, vel.length() * SPEED_BOOST);
            LivingEntity target = assistTarget(b.world, player, look);
            Vec3d dir = aimAt(target, hit, look, speed, shot.deflectGravity());
            shot.deflect(player, hit.add(dir.multiply(0.4 + shot.radius())), dir, speed, target);
            b.deflectedShots.add(shot);
            struck(b, player, hit, at);
        }
    }

    /**
     * Where on the pane something at {@code pos} moving by {@code vel} strikes it - its path from a tick ago to
     * {@code reach} ticks ahead crossing the glass, within {@code margin} of its edges - as {u, v} across and up from
     * the middle, or null if it doesn't.
     */
    @Nullable
    private static double[] strikePoint(Vec3d c, Vec3d right, Vec3d up, Vec3d look, Vec3d pos, Vec3d vel, double reach, double margin) {
        if (vel.lengthSquared() < 1e-6 || vel.dotProduct(look) >= 0) return null; // only things coming at you
        Vec3d from = pos.subtract(vel);
        Vec3d to = pos.add(vel.multiply(reach));
        double da = from.subtract(c).dotProduct(look);
        double db = to.subtract(c).dotProduct(look);
        if (da < 0 || db > 0) return null;
        Vec3d local = from.add(to.subtract(from).multiply(da / (da - db))).subtract(c);
        double u = local.dotProduct(right), v = local.dotProduct(up);
        if (Math.abs(u) > HALF_WIDTH + margin || Math.abs(v) > HALF_HEIGHT + margin) return null;
        return new double[]{MathHelper.clamp(u, -HALF_WIDTH, HALF_WIDTH), MathHelper.clamp(v, -HALF_HEIGHT, HALF_HEIGHT)};
    }

    private static void deflect(Barrier b, PlayerEntity player, Entity e, Vec3d hit, Vec3d look, double speed) {
        double newSpeed = Math.max(MIN_SPEED, speed * SPEED_BOOST);
        double gravity = e instanceof Deflectable d ? d.deflectGravity() : e.getFinalGravity();
        Vec3d dir = aimAt(assistTarget(b.world, player, look), hit, look, newSpeed, gravity);

        // Out the far side of the glass, clear of it
        Vec3d half = e.getBoundingBox().getCenter().subtract(e.getPos());
        double size = Math.max(e.getWidth(), e.getHeight()) * 0.5;
        Vec3d start = hit.add(dir.multiply(0.4 + size)).subtract(half);
        e.setPosition(start.x, start.y, start.z);

        if (e instanceof Deflectable d) {
            d.deflect(player, dir, newSpeed);
        } else {
            ProjectileEntity p = (ProjectileEntity) e;
            // It's yours now, so it can't hit you and counts as your shot. An ender pearl stays its thrower's, though:
            // you'd only teleport yourself to wherever it lands.
            p.deflect(ProjectileDeflection.SIMPLE, player, p instanceof EnderPearlEntity ? p.getOwner() : player, true);
            Vec3d out = dir.multiply(newSpeed);
            p.setVelocity(out);
            // Face the new direction right away so arrows and tridents don't fly sideways for a tick
            p.setYaw((float) (MathHelper.atan2(out.x, out.z) * MathHelper.DEGREES_PER_RADIAN));
            p.setPitch((float) (MathHelper.atan2(out.y, out.horizontalLength()) * MathHelper.DEGREES_PER_RADIAN));
            p.prevYaw = p.getYaw();
            p.prevPitch = p.getPitch();
        }
        e.velocityModified = true;
        b.deflected.add(e.getUuid());
    }

    /** The glass rings: sparks and sound where it was struck, and a ripple on everyone's screen. */
    private static void struck(Barrier b, PlayerEntity player, Vec3d hit, double[] at) {
        ServerWorld w = b.world;
        w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, hit.x, hit.y, hit.z, 20, 0.2, 0.2, 0.2, 0.4);
        w.spawnParticles(ParticleTypes.END_ROD, hit.x, hit.y, hit.z, 6, 0.1, 0.1, 0.1, 0.1);
        w.playSound(null, hit.x, hit.y, hit.z, SoundEvents.BLOCK_GLASS_HIT, SoundCategory.PLAYERS, 1.5f, 1.6f);
        w.playSound(null, hit.x, hit.y, hit.z, SoundEvents.ITEM_SHIELD_BLOCK, SoundCategory.PLAYERS, 1.0f, 1.4f);
        w.playSound(null, hit.x, hit.y, hit.z, SoundEvents.BLOCK_AMETHYST_BLOCK_HIT, SoundCategory.PLAYERS, 1.5f, 1.2f);
        if (player instanceof ServerPlayerEntity sp) {
            DeflectionPayload.broadcast(sp, new DeflectionPayload(sp.getId(), ACTIVE_TICKS + FADE_TICKS - b.age, true, (float) at[0], (float) at[1]));
        }
    }

    /**
     * Which way to send it back from {@code from}: along your look, or - if {@code target} (whoever is within
     * {@link #AIM_ASSIST_DEGREES} of your crosshair and in plain sight) - straight at them, aimed a little high for
     * things that fall.
     */
    private static Vec3d aimAt(@Nullable LivingEntity target, Vec3d from, Vec3d look, double speed, double gravity) {
        if (target == null) return look;
        Vec3d at = target.getBoundingBox().getCenter();
        double dist = at.distanceTo(from);
        double ticks = dist / speed;
        Vec3d d = at.add(0, 0.5 * gravity * ticks * ticks, 0).subtract(from);
        return d.lengthSquared() < 1e-6 ? look : d.normalize();
    }

    @Nullable
    private static LivingEntity assistTarget(ServerWorld world, PlayerEntity player, Vec3d look) {
        Vec3d eye = player.getEyePos();
        double cos = Math.cos(Math.toRadians(AIM_ASSIST_DEGREES));
        Box area = player.getBoundingBox().stretch(look.multiply(AIM_ASSIST_RANGE)).expand(AIM_ASSIST_RANGE * 0.15);
        LivingEntity best = null;
        double bestCos = cos;
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, area, e -> e != player && e.isAlive() && !e.isSpectator()
                && e.canHit() && !(e instanceof ArmorStandEntity) && !e.isTeammate(player))) {
            Vec3d to = e.getBoundingBox().getCenter().subtract(eye);
            double dist = to.length();
            if (dist < 1e-3 || dist > AIM_ASSIST_RANGE) continue;
            double k = to.dotProduct(look) / dist;
            if (k <= bestCos) continue;
            if (world.raycast(new RaycastContext(eye, e.getBoundingBox().getCenter(), RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE, player)).getType() != HitResult.Type.MISS) continue;
            best = e;
            bestCos = k;
        }
        return best;
    }
}
