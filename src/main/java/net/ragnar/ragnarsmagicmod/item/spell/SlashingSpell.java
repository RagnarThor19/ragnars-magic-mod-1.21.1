package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.util.TempEntities;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A huge dark blade appears and cuts in a three-part combo, one move per cast: a diagonal cleave from over your
 * right shoulder, the same cleave mirrored from over your left, then a straight thrust where you're looking. The
 * cleaves hit everything in the arc and throw the crowd to the side of the swing; the thrust reaches further,
 * hits harder along a narrow line and drives straight back.
 */
public class SlashingSpell implements Spell {
    private static final int SWING_TICKS = 6;
    private static final int FADE_TICKS = 4;
    private static final double START_ANGLE = -0.35;       // radians before the upper-right diagonal
    private static final double END_ANGLE = Math.PI + 0.35; // past the lower-left diagonal
    private static final float SCALE = 3.6f;               // the sword item scaled up; the blade is ~5 blocks long

    private static final double REACH = 5.5;
    private static final double ARC_DEGREES = 75.0;        // half-angle of the area in front that the swing covers
    private static final float DAMAGE = 14.0f;
    private static final double KNOCKBACK = 1.5;

    // In ModelTransformationMode.NONE the sword sprite lies in the item's XY plane, tip toward (+1, +1)
    private static final Vector3f TIP_LOCAL = new Vector3f(1, 1, 0).normalize();
    private static final Vector3f SIDE_LOCAL = new Vector3f(-1, 1, 0).normalize();
    private static final Vector3f FACE_LOCAL = new Vector3f(0, 0, 1);
    // The item display renderer turns the model 180 degrees about Y before drawing it, so the blade's real
    // point is at (-1, +1) and its face toward -Z. The cleaves keep the frame above (their look is tuned to it);
    // the thrust needs the true point so the blade leads with its tip.
    private static final Vector3f STAB_TIP_LOCAL = new Vector3f(-1, 1, 0).normalize();
    private static final Vector3f STAB_SIDE_LOCAL = new Vector3f(1, 1, 0).normalize();
    private static final Vector3f STAB_FACE_LOCAL = new Vector3f(0, 0, -1);

    // The thrust
    private static final int STAB_TICKS = 5;
    private static final double STAB_REACH = 7.0;
    private static final double STAB_WIDTH = 1.1;          // how far off the line a creature can be and still get run through
    private static final float STAB_DAMAGE = 16.0f;
    private static final double STAB_KNOCKBACK = 2.0;
    private static final float STAB_DRAW = -0.9f;          // how far back the blade is drawn before the thrust
    private static final float STAB_LUNGE = 2.4f;          // how far forward it drives

    // Casts this far apart still continue the combo; after that it starts again with the first cleave
    private static final long COMBO_WINDOW = 20 * 9;

    private enum Move { CLEAVE, MIRRORED_CLEAVE, THRUST }

    private record Combo(int next, long lastCast) {}
    private static final Map<UUID, Combo> COMBOS = new HashMap<>();

    private static final DustParticleEffect SHADOW =new DustParticleEffect(new Vector3f(0.08f, 0.05f, 0.12f), 1.6f);
    private static final DustParticleEffect EDGE = new DustParticleEffect(new Vector3f(0.55f, 0.25f, 0.85f), 1.0f);

    private static final class Slash {
        final ServerWorld world;
        final UUID owner;
        final DisplayEntity.ItemDisplayEntity sword;
        final Move move;
        // The swing's frame, fixed when cast
        final Vec3d forward;
        final Vec3d diagonal;      // from pivot toward the shoulder the cleave starts over
        final Vec3d normal;        // the swing plane's normal
        final Vec3d aim;           // the thrust's direction (where you were looking)
        int age = 0;
        boolean struck = false;

        Slash(ServerWorld world, UUID owner, DisplayEntity.ItemDisplayEntity sword, Move move, Vec3d forward,
              Vec3d diagonal, Vec3d normal, Vec3d aim) {
            this.world = world;
            this.owner = owner;
            this.sword = sword;
            this.move = move;
            this.forward = forward;
            this.diagonal = diagonal;
            this.normal = normal;
            this.aim = aim;
        }
    }

    private static final List<Slash> ACTIVE = new ArrayList<>();
    private static boolean registered = false;

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            Iterator<Slash> it = ACTIVE.iterator();
            while (it.hasNext()) {
                Slash s = it.next();
                if (s.world == world && !tick(s)) {
                    TempEntities.discard(s.sword);
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

        // Which move of the combo this is
        long now = sw.getTime();
        Combo combo = COMBOS.get(player.getUuid());
        int index = combo != null && now - combo.lastCast() <= COMBO_WINDOW ? combo.next() : 0;
        Move move = Move.values()[index];
        COMBOS.put(player.getUuid(), new Combo((index + 1) % Move.values().length, now));

        double yaw = Math.toRadians(player.getYaw());
        Vec3d forward = new Vec3d(-Math.sin(yaw), 0, Math.cos(yaw));
        Vec3d right = new Vec3d(-forward.z, 0, forward.x);
        // The mirrored cleave starts over the left shoulder instead
        Vec3d shoulder = move == Move.MIRRORED_CLEAVE ? right.negate() : right;
        Vec3d diagonal = shoulder.add(0, 1, 0).normalize();
        Vec3d normal = diagonal.crossProduct(forward).normalize();
        Vec3d aim = player.getRotationVector().normalize();

        DisplayEntity.ItemDisplayEntity sword = EntityType.ITEM_DISPLAY.create(sw);
        if (sword == null) return false;
        sword.setItemStack(new ItemStack(Items.NETHERITE_SWORD));
        sword.setTransformationMode(ModelTransformationMode.NONE);
        sword.setTeleportDuration(1);
        sword.setViewRange(3.0f);
        Vec3d pivot = pivot(player);
        sword.refreshPositionAndAngles(pivot.x, pivot.y, pivot.z, 0f, 0f);
        Slash slash = new Slash(sw, player.getUuid(), sword, move, forward, diagonal, normal, aim);
        sword.setTransformation(move == Move.THRUST ? stabTransform(slash, STAB_DRAW, 0.3f) : swordTransform(slash, START_ANGLE, 0.3f));
        TempEntities.track(sword);
        sw.spawnEntity(sword);
        ACTIVE.add(slash);

        Vec3d gather = move == Move.THRUST ? pivot.add(aim.multiply(1.5)) : pivot.add(diagonal.multiply(2));
        sw.spawnParticles(SHADOW, gather.x, gather.y, gather.z, 20, 0.4, 0.4, 0.4, 0);
        sw.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ITEM_ARMOR_EQUIP_NETHERITE.value(), SoundCategory.PLAYERS, 1.2f, 0.6f);
        sw.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENTITY_WITHER_SHOOT, SoundCategory.PLAYERS, 0.4f, 0.5f);
        return true;
    }

    /** Returns false once the sword has faded. */
    private static boolean tick(Slash s) {
        PlayerEntity player = s.world.getPlayerByUuid(s.owner);
        if (player == null || !player.isAlive()) return false;
        s.age++;
        Vec3d pivot = pivot(player);
        s.sword.setPosition(pivot.x, pivot.y, pivot.z);
        if (s.move == Move.THRUST) return tickThrust(s, player, pivot);

        if (s.age <= SWING_TICKS) {
            double t = s.age / (double) SWING_TICKS;
            // Eases in fast and slows at the end of the cut
            double eased = 1 - (1 - t) * (1 - t);
            double angle = MathHelper.lerp(eased, START_ANGLE, END_ANGLE);
            double prevT = (s.age - 1) / (double) SWING_TICKS;
            double prevAngle = MathHelper.lerp(1 - (1 - prevT) * (1 - prevT), START_ANGLE, END_ANGLE);
            s.sword.setTransformation(swordTransform(s, angle, 1f));
            s.sword.setStartInterpolation(0);
            s.sword.setInterpolationDuration(1);
            trail(s, pivot, prevAngle, angle);

            if (s.age == 1) {
                s.world.playSound(null, pivot.x, pivot.y, pivot.z, SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 1.5f, 0.5f);
                s.world.playSound(null, pivot.x, pivot.y, pivot.z, SoundEvents.ITEM_MACE_SMASH_AIR, SoundCategory.PLAYERS, 1.0f, 0.7f);
            }
            // Land the hit as the blade passes straight ahead
            if (!s.struck && angle >= Math.PI / 2) {
                s.struck = true;
                strike(s, player, pivot);
            }
            return true;
        }

        int fade = s.age - SWING_TICKS;
        if (fade <= FADE_TICKS) {
            float size = 1f - fade / (float) FADE_TICKS;
            s.sword.setTransformation(swordTransform(s, END_ANGLE, Math.max(size, 0.01f)));
            s.sword.setStartInterpolation(0);
            s.sword.setInterpolationDuration(1);
            if (fade == 1) {
                Vec3d tip = pivot.add(dir(s, END_ANGLE).multiply(2.5));
                s.world.spawnParticles(SHADOW, tip.x, tip.y, tip.z, 15, 0.6, 0.6, 0.6, 0);
            }
            return true;
        }
        return false;
    }

    /** The thrust: drawn back, driven forward hard, a moment's hold, then gone. */
    private static boolean tickThrust(Slash s, PlayerEntity player, Vec3d pivot) {
        if (s.age <= STAB_TICKS) {
            double t = s.age / (double) STAB_TICKS;
            // Snaps forward almost at once, then settles at full reach
            double eased = 1 - Math.pow(1 - Math.min(1.0, t * 1.6), 3);
            float reach = (float) MathHelper.lerp(eased, STAB_DRAW, STAB_LUNGE);
            s.sword.setTransformation(stabTransform(s, reach, 1f));
            s.sword.setStartInterpolation(0);
            s.sword.setInterpolationDuration(1);

            if (s.age == 1) {
                s.world.playSound(null, pivot.x, pivot.y, pivot.z, SoundEvents.ITEM_TRIDENT_THROW.value(), SoundCategory.PLAYERS, 1.4f, 0.6f);
                s.world.playSound(null, pivot.x, pivot.y, pivot.z, SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 1.0f, 1.4f);
            }
            // A dark streak along the line of the thrust
            for (double d = 1.5; d <= reach + 4.5; d += 0.5) {
                Vec3d p = pivot.add(s.aim.multiply(d));
                s.world.spawnParticles(d > reach + 3.5 ? EDGE : SHADOW, p.x, p.y, p.z, 1, 0.04, 0.04, 0.04, 0);
            }
            if (!s.struck && s.age >= 2) {
                s.struck = true;
                thrust(s, player, pivot);
            }
            return true;
        }
        int fade = s.age - STAB_TICKS;
        if (fade <= FADE_TICKS) {
            float size = 1f - fade / (float) FADE_TICKS;
            s.sword.setTransformation(stabTransform(s, STAB_LUNGE, Math.max(size, 0.01f)));
            s.sword.setStartInterpolation(0);
            s.sword.setInterpolationDuration(1);
            return true;
        }
        return false;
    }

    /** The blade pointing where you looked, edge leading, pushed {@code reach} blocks along that line. */
    private static AffineTransformation stabTransform(Slash s, float reach, float size) {
        Vector3f tip = s.aim.toVector3f().normalize();
        Vector3f up = Math.abs(tip.y) > 0.95f ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
        Vector3f face = new Vector3f(tip).cross(up).normalize(); // flat of the blade faces sideways
        Vector3f side = new Vector3f(face).cross(tip).normalize();
        Matrix3f target = new Matrix3f(tip, side, face);
        Matrix3f local = new Matrix3f(STAB_TIP_LOCAL, STAB_SIDE_LOCAL, STAB_FACE_LOCAL);
        Matrix3f rotation = target.mul(local.transpose());
        float scale = SCALE * size;
        Vector3f out = new Vector3f(tip).mul(scale * 0.62f + reach);
        return new AffineTransformation(new Matrix4f()
                .translate(out)
                .mul(new Matrix4f(rotation))
                .scale(scale));
    }

    /** Runs through everything along the line in front of you. */
    private static void thrust(Slash s, PlayerEntity player, Vec3d pivot) {
        ServerWorld world = s.world;
        Vec3d end = pivot.add(s.aim.multiply(STAB_REACH));
        boolean hitAny = false;
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, new Box(pivot, end).expand(STAB_WIDTH + 1.0),
                e -> e != player && e.isAlive() && !e.isSpectator())) {
            Vec3d c = e.getBoundingBox().getCenter();
            Vec3d rel = c.subtract(pivot);
            double along = rel.dotProduct(s.aim);
            if (along < 0 || along > STAB_REACH + e.getWidth() * 0.5) continue;
            double off = rel.subtract(s.aim.multiply(along)).length();
            if (off > STAB_WIDTH + e.getWidth() * 0.5) continue;

            e.damage(world.getDamageSources().playerAttack(player), STAB_DAMAGE);
            e.addVelocity(s.aim.x * STAB_KNOCKBACK, 0.3 + Math.max(0, s.aim.y) * STAB_KNOCKBACK, s.aim.z * STAB_KNOCKBACK);
            e.velocityModified = true;
            world.spawnParticles(ParticleTypes.CRIT, c.x, c.y, c.z, 16, 0.2, 0.3, 0.2, 0.5);
            world.spawnParticles(ParticleTypes.ENCHANTED_HIT, c.x, c.y, c.z, 8, 0.2, 0.3, 0.2, 0.3);
            world.spawnParticles(SHADOW, c.x, c.y, c.z, 10, 0.25, 0.35, 0.25, 0);
            hitAny = true;
        }
        if (hitAny) {
            world.playSound(null, pivot.x, pivot.y, pivot.z, SoundEvents.ITEM_TRIDENT_HIT, SoundCategory.PLAYERS, 1.3f, 0.6f);
            world.playSound(null, pivot.x, pivot.y, pivot.z, SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.PLAYERS, 1.3f, 0.7f);
        }
    }

    private static Vec3d pivot(PlayerEntity player) {
        return player.getPos().add(0, player.getHeight() * 0.6, 0);
    }

    /** Blade direction at {@code angle}: 0 is the upper-right diagonal, pi/2 straight ahead, pi the lower left. */
    private static Vec3d dir(Slash s, double angle) {
        return s.diagonal.multiply(Math.cos(angle)).add(s.forward.multiply(Math.sin(angle)));
    }

    /** Lays the sword flat in the swing plane, hilt at the pivot, blade pointing along {@code dir}. */
    private static AffineTransformation swordTransform(Slash s, double angle, float size) {
        Vector3f tip = dir(s, angle).toVector3f().normalize();
        Vector3f face = s.normal.toVector3f();
        Vector3f side = new Vector3f(face).cross(tip).normalize();
        // Rotation taking the sprite's own axes (tip, side, face) onto the world's
        Matrix3f target = new Matrix3f(tip, side, face);
        Matrix3f local = new Matrix3f(TIP_LOCAL, SIDE_LOCAL, FACE_LOCAL);
        Matrix3f rotation = target.mul(local.transpose());
        float scale = SCALE * size;
        // Push the model out so its hilt, not its middle, sits on the pivot
        Vector3f out = new Vector3f(tip).mul(scale * 0.62f);
        return new AffineTransformation(new Matrix4f()
                .translate(out)
                .mul(new Matrix4f(rotation))
                .scale(scale));
    }

    /** A dark crescent left behind by the edge of the blade. */
    private static void trail(Slash s, Vec3d pivot, double from, double to) {
        int steps = 6;
        for (int i = 0; i <= steps; i++) {
            double a = MathHelper.lerp(i / (double) steps, from, to);
            Vec3d d = dir(s, a);
            for (double r = 2.0; r <= 4.8; r += 0.7) {
                Vec3d p = pivot.add(d.multiply(r));
                s.world.spawnParticles(r > 4.0 ? EDGE : SHADOW, p.x, p.y, p.z, 1, 0.05, 0.05, 0.05, 0);
            }
        }
        Vec3d mid = pivot.add(dir(s, to).multiply(3.2));
        s.world.spawnParticles(ParticleTypes.SWEEP_ATTACK, mid.x, mid.y, mid.z, 1, 0, 0, 0, 0);
    }

    private static void strike(Slash s, PlayerEntity player, Vec3d pivot) {
        ServerWorld world = s.world;
        double minCos = Math.cos(Math.toRadians(ARC_DEGREES));
        // The crowd is thrown back and toward the side the cut travels to: your left, or your right when mirrored
        Vec3d left = new Vec3d(s.forward.z, 0, -s.forward.x);
        Vec3d away = s.move == Move.MIRRORED_CLEAVE ? left.negate() : left;
        Vec3d throwDir = s.forward.multiply(0.8).add(away.multiply(0.6)).normalize();
        boolean hitAny = false;

        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, new Box(pivot, pivot).expand(REACH),
                e -> e != player && e.isAlive() && !e.isSpectator())) {
            Vec3d to = e.getBoundingBox().getCenter().subtract(pivot);
            double dy = to.y;
            Vec3d flat = new Vec3d(to.x, 0, to.z);
            double dist = flat.length();
            if (dist > REACH + e.getWidth() * 0.5 || dy < -2.0 || dy > 3.5) continue;
            if (dist > 0.8 && flat.multiply(1.0 / dist).dotProduct(s.forward) < minCos) continue;

            e.damage(world.getDamageSources().playerAttack(player), DAMAGE);
            e.addVelocity(throwDir.x * KNOCKBACK, 0.45, throwDir.z * KNOCKBACK);
            e.velocityModified = true;
            Vec3d c = e.getBoundingBox().getCenter();
            world.spawnParticles(ParticleTypes.SWEEP_ATTACK, c.x, c.y, c.z, 1, 0, 0, 0, 0);
            world.spawnParticles(ParticleTypes.CRIT, c.x, c.y, c.z, 12, 0.3, 0.4, 0.3, 0.3);
            world.spawnParticles(SHADOW, c.x, c.y, c.z, 8, 0.3, 0.4, 0.3, 0);
            hitAny = true;
        }
        if (hitAny) {
            world.playSound(null, pivot.x, pivot.y, pivot.z, SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.PLAYERS, 1.3f, 0.6f);
            world.playSound(null, pivot.x, pivot.y, pivot.z, SoundEvents.ITEM_MACE_SMASH_GROUND, SoundCategory.PLAYERS, 1.0f, 0.8f);
        }
    }
}
