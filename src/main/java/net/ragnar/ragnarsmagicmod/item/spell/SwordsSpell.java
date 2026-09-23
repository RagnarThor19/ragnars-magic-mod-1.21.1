package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.util.TempEntities;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * First cast: five enchanted swords materialise in a fan behind the caster, all pointing where they
 * look. Each further cast locks onto the creature nearest the crosshair and fires one sword at it:
 * a fast, lightly homing blade that deals {@link #DAMAGE} (20 hearts). Swords stop at walls and
 * can be dodged by breaking line of sight quickly. The long cooldown starts after the fifth sword.
 */
public class SwordsSpell implements Spell {
    private static final int SWORD_COUNT = 5;
    private static final double RANGE = 50.0;
    private static final double AIM_CONE_DEGREES = 8.0;
    private static final float DAMAGE = 40.0f;
    private static final double SPEED = 2.0;
    private static final double MAX_TURN_DEGREES = 4.0;   // homing per tick; strafing hard can shake it
    private static final int FLIGHT_TICKS = 40;
    private static final int STUCK_TICKS = 20;
    private static final int HALO_TIMEOUT = 20 * 60;     // unused swords fade after a minute
    private static final int SHOT_GAP = 5;               // cooldown between shots
    private static final float SCALE = 1.3f;
    // In ModelTransformationMode.NONE the sword sprite's tip points up-right in item space
    private static final Vector3f TIP_LOCAL = new Vector3f(1, 1, 0).normalize();

    private static final class Halo {
        final ServerWorld world;
        final UUID owner;
        final DisplayEntity.ItemDisplayEntity[] slots = new DisplayEntity.ItemDisplayEntity[SWORD_COUNT];
        int age = 0;

        Halo(ServerWorld world, UUID owner) {
            this.world = world;
            this.owner = owner;
        }

        int remaining() {
            int n = 0;
            for (var s : slots) if (s != null) n++;
            return n;
        }
    }

    private static final class Flying {
        final ServerWorld world;
        final DisplayEntity.ItemDisplayEntity display;
        final UUID owner;
        final LivingEntity target;
        Vec3d pos;
        Vec3d vel;
        int age = 0;
        int stuck = -1;

        Flying(ServerWorld world, DisplayEntity.ItemDisplayEntity display, UUID owner, LivingEntity target, Vec3d pos, Vec3d vel) {
            this.world = world;
            this.display = display;
            this.owner = owner;
            this.target = target;
            this.pos = pos;
            this.vel = vel;
        }
    }

    private static final Map<UUID, Halo> HALOS = new HashMap<>();
    private static final List<Flying> FLYING = new ArrayList<>();
    private static boolean registered = false;

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            Iterator<Halo> halos = HALOS.values().iterator();
            while (halos.hasNext()) {
                Halo h = halos.next();
                if (h.world == world && !tickHalo(h)) {
                    for (var s : h.slots) if (s != null) fade(world, s);
                    halos.remove();
                }
            }
            Iterator<Flying> flying = FLYING.iterator();
            while (flying.hasNext()) {
                Flying f = flying.next();
                if (f.world == world && !tickFlying(f)) {
                    TempEntities.discard(f.display);
                    flying.remove();
                }
            }
        });
    }

    private static Halo activeHalo(PlayerEntity player) {
        Halo h = HALOS.get(player.getUuid());
        return h != null && h.world == player.getWorld() && h.remaining() > 0 ? h : null;
    }

    // Summoning costs the tome's XP; the shots themselves are free
    @Override
    public int xpCost(PlayerEntity player, int tomeCost) {
        return activeHalo(player) != null ? 0 : tomeCost;
    }

    // Quick follow-up shots; the full cooldown only once the last sword is gone
    @Override
    public int cooldownAfterCast(PlayerEntity player, int tomeCooldown) {
        return activeHalo(player) != null ? SHOT_GAP : tomeCooldown;
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        ensureRegistered();
        ServerWorld sw = (ServerWorld) world;

        Halo halo = activeHalo(player);
        if (halo == null) {
            summon(sw, player);
            return true;
        }

        LivingEntity target = findTarget(sw, player);
        if (target == null) {
            player.sendMessage(Text.literal("No target in sight."), true);
            return false;
        }
        fire(halo, player, target);
        if (halo.remaining() == 0) HALOS.remove(player.getUuid());
        return true;
    }

    // ---------------------------------------------------------------------
    // Halo
    // ---------------------------------------------------------------------

    private static void summon(ServerWorld world, PlayerEntity player) {
        Halo old = HALOS.remove(player.getUuid());
        if (old != null) for (var s : old.slots) if (s != null) fade(world, s);

        Halo halo = new Halo(world, player.getUuid());
        ItemStack blade = new ItemStack(Items.NETHERITE_SWORD);
        blade.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        for (int i = 0; i < SWORD_COUNT; i++) {
            DisplayEntity.ItemDisplayEntity d = EntityType.ITEM_DISPLAY.create(world);
            if (d == null) continue;
            d.setItemStack(blade.copy());
            d.setTransformationMode(ModelTransformationMode.NONE);
            d.setTeleportDuration(1);
            d.setViewRange(2.0f);
            Vec3d p = slotPosition(player, i, 0);
            d.refreshPositionAndAngles(p.x, p.y, p.z, 0f, 0f);
            d.setTransformation(transformFor(aimPoint(player).subtract(p)));
            TempEntities.track(d);
            world.spawnEntity(d);
            halo.slots[i] = d;
            world.spawnParticles(ParticleTypes.ENCHANT, p.x, p.y, p.z, 20, 0.2, 0.3, 0.2, 0.6);
            world.spawnParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 4, 0.1, 0.2, 0.1, 0.03);
        }
        HALOS.put(player.getUuid(), halo);

        Vec3d c = player.getPos();
        world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_ENCHANTMENT_TABLE_USE, SoundCategory.PLAYERS, 1.2f, 0.6f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ITEM_ARMOR_EQUIP_NETHERITE.value(), SoundCategory.PLAYERS, 1.2f, 0.7f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_VAULT_OPEN_SHUTTER, SoundCategory.PLAYERS, 1.0f, 0.8f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.5f, 0.6f);
    }

    /** Returns false when the halo should be dismissed. */
    private static boolean tickHalo(Halo h) {
        PlayerEntity player = h.world.getPlayerByUuid(h.owner);
        if (player == null || !player.isAlive() || h.remaining() == 0 || ++h.age > HALO_TIMEOUT) return false;

        Vec3d aim = aimPoint(player);
        for (int i = 0; i < SWORD_COUNT; i++) {
            DisplayEntity.ItemDisplayEntity d = h.slots[i];
            if (d == null) continue;
            Vec3d p = slotPosition(player, i, h.age);
            d.setPosition(p.x, p.y, p.z);
            d.setTransformation(transformFor(aim.subtract(p)));
            d.setStartInterpolation(0);
            d.setInterpolationDuration(1);
            if (h.world.random.nextInt(8) == 0) {
                h.world.spawnParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 1, 0.15, 0.15, 0.15, 0.0);
            }
        }
        return true;
    }

    /** A fan of five slots arched behind the caster's shoulders, gently bobbing. */
    private static Vec3d slotPosition(PlayerEntity player, int slot, int age) {
        double yaw = Math.toRadians(player.getHeadYaw());
        Vec3d forward = new Vec3d(-Math.sin(yaw), 0, Math.cos(yaw));
        Vec3d right = new Vec3d(-forward.z, 0, forward.x);
        double theta = Math.toRadians(-64 + slot * 32.0);
        double bob = Math.sin(age * 0.12 + slot * 1.3) * 0.06;
        return player.getPos()
                .add(0, 1.75 + Math.cos(theta) * 0.55 + bob, 0)
                .add(right.multiply(Math.sin(theta) * 1.35))
                .add(forward.multiply(-0.55));
    }

    private static Vec3d aimPoint(PlayerEntity player) {
        return player.getEyePos().add(player.getRotationVector().multiply(RANGE));
    }

    private static AffineTransformation transformFor(Vec3d direction) {
        Vector3f dir = direction.lengthSquared() > 1.0e-6 ? direction.normalize().toVector3f() : new Vector3f(0, 0, 1);
        Quaternionf rotation = new Quaternionf().rotationTo(TIP_LOCAL, dir);
        return new AffineTransformation(new Vector3f(), rotation, new Vector3f(SCALE, SCALE, SCALE), new Quaternionf());
    }

    // ---------------------------------------------------------------------
    // Targeting & firing
    // ---------------------------------------------------------------------

    /** The visible creature closest to the crosshair, within a narrow cone and {@link #RANGE}. */
    private static LivingEntity findTarget(ServerWorld world, PlayerEntity player) {
        Vec3d eye = player.getEyePos();
        Vec3d look = player.getRotationVector().normalize();
        double minCos = Math.cos(Math.toRadians(AIM_CONE_DEGREES));
        Box area = new Box(eye, eye.add(look.multiply(RANGE))).expand(RANGE * Math.tan(Math.toRadians(AIM_CONE_DEGREES)) + 2.0);

        LivingEntity best = null;
        double bestCos = minCos;
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, area, e -> e.isAlive() && e != player && !e.isSpectator())) {
            Vec3d to = e.getBoundingBox().getCenter().subtract(eye);
            double dist = to.length();
            if (dist > RANGE || dist < 1.0e-3) continue;
            double cos = to.multiply(1.0 / dist).dotProduct(look);
            if (cos < bestCos) continue;
            if (!canSee(world, player, eye, e)) continue;
            bestCos = cos;
            best = e;
        }
        return best;
    }

    private static boolean canSee(ServerWorld world, PlayerEntity player, Vec3d from, LivingEntity e) {
        for (Vec3d point : new Vec3d[]{e.getEyePos(), e.getBoundingBox().getCenter()}) {
            HitResult hit = world.raycast(new RaycastContext(from, point, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
            if (hit.getType() == HitResult.Type.MISS) return true;
        }
        return false;
    }

    private static void fire(Halo halo, PlayerEntity player, LivingEntity target) {
        // Launch the sword closest to the target
        int pick = -1;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < SWORD_COUNT; i++) {
            if (halo.slots[i] == null) continue;
            double d = halo.slots[i].getPos().squaredDistanceTo(target.getPos());
            if (d < bestDist) {
                bestDist = d;
                pick = i;
            }
        }
        if (pick < 0) return;

        DisplayEntity.ItemDisplayEntity d = halo.slots[pick];
        halo.slots[pick] = null;
        Vec3d pos = d.getPos();
        Vec3d vel = target.getBoundingBox().getCenter().subtract(pos).normalize().multiply(SPEED);
        FLYING.add(new Flying(halo.world, d, player.getUuid(), target, pos, vel));

        ServerWorld world = halo.world;
        world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ITEM_TRIDENT_THROW.value(), SoundCategory.PLAYERS, 1.4f, 0.8f);
        world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 1.0f, 0.6f);
        world.spawnParticles(ParticleTypes.SWEEP_ATTACK, pos.x, pos.y, pos.z, 1, 0, 0, 0, 0);
    }

    // ---------------------------------------------------------------------
    // Flight
    // ---------------------------------------------------------------------

    /** Returns false once the sword is finished. */
    private static boolean tickFlying(Flying f) {
        ServerWorld world = f.world;
        f.age++;

        if (f.stuck >= 0) {
            // Quivering in the wall, then shattering
            if (++f.stuck >= STUCK_TICKS) {
                world.spawnParticles(ParticleTypes.ENCHANTED_HIT, f.pos.x, f.pos.y, f.pos.z, 12, 0.2, 0.2, 0.2, 0.2);
                world.playSound(null, f.pos.x, f.pos.y, f.pos.z, SoundEvents.ITEM_SHIELD_BREAK, SoundCategory.PLAYERS, 0.6f, 1.4f);
                return false;
            }
            return true;
        }
        if (f.age > FLIGHT_TICKS) {
            fade(world, f.display);
            return false;
        }

        // Light homing toward the target's current position
        if (f.target.isAlive()) {
            Vec3d desired = f.target.getBoundingBox().getCenter().subtract(f.pos).normalize();
            f.vel = turnToward(f.vel.normalize(), desired, Math.toRadians(MAX_TURN_DEGREES)).multiply(SPEED);
        }

        Vec3d from = f.pos;
        Vec3d to = from.add(f.vel);
        PlayerEntity owner = world.getPlayerByUuid(f.owner);

        // First creature along the path takes the blade
        Box sweep = new Box(from, to).expand(0.6);
        LivingEntity hitEntity = null;
        double hitDist = Double.MAX_VALUE;
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, sweep, e -> e.isAlive() && !e.getUuid().equals(f.owner) && !e.isSpectator())) {
            var clip = e.getBoundingBox().expand(0.3).raycast(from, to);
            if (clip.isEmpty()) continue;
            double d = from.squaredDistanceTo(clip.get());
            if (d < hitDist) {
                hitDist = d;
                hitEntity = e;
            }
        }

        BlockHitResult wall = world.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, net.minecraft.block.ShapeContext.absent()));
        boolean wallFirst = wall.getType() == HitResult.Type.BLOCK && (hitEntity == null || from.squaredDistanceTo(wall.getPos()) < hitDist);

        if (wallFirst) {
            f.pos = wall.getPos().subtract(f.vel.normalize().multiply(0.35));
            moveDisplay(f);
            f.stuck = 0;
            world.playSound(null, f.pos.x, f.pos.y, f.pos.z, SoundEvents.ITEM_TRIDENT_HIT_GROUND, SoundCategory.PLAYERS, 1.2f, 0.8f);
            world.spawnParticles(ParticleTypes.CRIT, f.pos.x, f.pos.y, f.pos.z, 10, 0.1, 0.1, 0.1, 0.2);
            return true;
        }
        if (hitEntity != null) {
            strike(world, owner, hitEntity, f.vel);
            return false;
        }

        f.pos = to;
        moveDisplay(f);
        // Glinting trail
        for (int i = 0; i < 3; i++) {
            Vec3d p = from.lerp(to, i / 3.0);
            world.spawnParticles(i == 0 ? ParticleTypes.END_ROD : ParticleTypes.ELECTRIC_SPARK, p.x, p.y, p.z, 1, 0.02, 0.02, 0.02, 0.0);
        }
        return true;
    }

    private static void moveDisplay(Flying f) {
        f.display.setPosition(f.pos.x, f.pos.y, f.pos.z);
        f.display.setTransformation(transformFor(f.vel));
        f.display.setStartInterpolation(0);
        f.display.setInterpolationDuration(1);
    }

    private static void strike(ServerWorld world, PlayerEntity owner, LivingEntity target, Vec3d vel) {
        target.damage(owner != null ? world.getDamageSources().indirectMagic(owner, owner) : world.getDamageSources().magic(), DAMAGE);
        Vec3d push = vel.normalize().multiply(0.6);
        target.addVelocity(push.x, 0.2, push.z);
        target.velocityModified = true;

        Vec3d c = target.getBoundingBox().getCenter();
        world.spawnParticles(ParticleTypes.SWEEP_ATTACK, c.x, c.y, c.z, 1, 0, 0, 0, 0);
        world.spawnParticles(ParticleTypes.ENCHANTED_HIT, c.x, c.y, c.z, 25, 0.3, 0.4, 0.3, 0.4);
        world.spawnParticles(ParticleTypes.CRIT, c.x, c.y, c.z, 20, 0.3, 0.4, 0.3, 0.4);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ITEM_TRIDENT_HIT, SoundCategory.PLAYERS, 1.5f, 0.7f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, SoundCategory.PLAYERS, 1.2f, 0.8f);
    }

    private static void fade(ServerWorld world, DisplayEntity.ItemDisplayEntity d) {
        Vec3d p = d.getPos();
        world.spawnParticles(ParticleTypes.ENCHANT, p.x, p.y, p.z, 15, 0.2, 0.3, 0.2, 0.4);
        world.spawnParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 3, 0.1, 0.1, 0.1, 0.02);
        TempEntities.discard(d);
    }

    /** Rotates unit vector {@code from} toward {@code to} by at most {@code maxAngle} radians. */
    private static Vec3d turnToward(Vec3d from, Vec3d to, double maxAngle) {
        double cos = MathHelper.clamp(from.dotProduct(to), -1.0, 1.0);
        double angle = Math.acos(cos);
        if (angle <= maxAngle || angle < 1.0e-6) return to;
        double t = maxAngle / angle;
        // Spherical interpolation
        double sin = Math.sin(angle);
        double a = Math.sin((1 - t) * angle) / sin;
        double b = Math.sin(t * angle) / sin;
        return from.multiply(a).add(to.multiply(b)).normalize();
    }
}
