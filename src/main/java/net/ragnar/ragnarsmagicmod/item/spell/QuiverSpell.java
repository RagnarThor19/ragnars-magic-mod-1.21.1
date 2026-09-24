package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.Box;
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
 * First cast: seven spectral arrows appear and circle just above the caster, all pointing where they look. Each
 * further cast fires one arrow in a dead-straight line at exactly the point under the crosshair (no
 * lock-on, no homing) for {@link #DAMAGE}. Shots have no delay between them, so they can be spammed.
 * The full cooldown starts after the seventh arrow.
 */
public class QuiverSpell implements Spell {
    private static final int ARROW_COUNT = 7;
    private static final double RANGE = 80.0;
    private static final float DAMAGE = 10.0f;
    private static final double SPEED = 3.0;
    private static final int FLIGHT_TICKS = 30;
    private static final int STUCK_TICKS = 20;
    private static final int RING_TIMEOUT = 20 * 60;     // unused arrows fade after a minute
    private static final double RING_RADIUS = 1.2;
    private static final double RING_SPIN = 0.08;        // radians per tick
    private static final float SCALE = 0.75f;
    // In ModelTransformationMode.NONE the arrow sprite's tip points up-right in item space
    private static final Vector3f TIP_LOCAL = new Vector3f(1, 1, 0).normalize();

    private static final class Ring {
        final ServerWorld world;
        final UUID owner;
        final DisplayEntity.ItemDisplayEntity[] slots = new DisplayEntity.ItemDisplayEntity[ARROW_COUNT];
        int age = 0;

        Ring(ServerWorld world, UUID owner) {
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
        final Vec3d vel;
        Vec3d pos;
        int age = 0;
        int stuck = -1;

        Flying(ServerWorld world, DisplayEntity.ItemDisplayEntity display, UUID owner, Vec3d pos, Vec3d vel) {
            this.world = world;
            this.display = display;
            this.owner = owner;
            this.pos = pos;
            this.vel = vel;
        }
    }

    private static final Map<UUID, Ring> RINGS = new HashMap<>();
    private static final List<Flying> FLYING = new ArrayList<>();
    private static boolean registered = false;

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            Iterator<Ring> rings = RINGS.values().iterator();
            while (rings.hasNext()) {
                Ring r = rings.next();
                if (r.world == world && !tickRing(r)) {
                    for (var s : r.slots) if (s != null) fade(world, s);
                    rings.remove();
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

    private static Ring activeRing(PlayerEntity player) {
        Ring r = RINGS.get(player.getUuid());
        return r != null && r.world == player.getWorld() && r.remaining() > 0 ? r : null;
    }

    // Summoning costs the tome's XP; the shots themselves are free
    @Override
    public int xpCost(PlayerEntity player, int tomeCost) {
        return activeRing(player) != null ? 0 : tomeCost;
    }

    // No delay between shots; the full cooldown only once the last arrow is gone
    @Override
    public int cooldownAfterCast(PlayerEntity player, int tomeCooldown) {
        return activeRing(player) != null ? 0 : tomeCooldown;
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        ensureRegistered();
        ServerWorld sw = (ServerWorld) world;

        Ring ring = activeRing(player);
        if (ring == null) {
            summon(sw, player);
            return true;
        }
        fire(ring, player);
        if (ring.remaining() == 0) RINGS.remove(player.getUuid());
        return true;
    }

    // ---------------------------------------------------------------------
    // Ring
    // ---------------------------------------------------------------------

    private static void summon(ServerWorld world, PlayerEntity player) {
        Ring old = RINGS.remove(player.getUuid());
        if (old != null) for (var s : old.slots) if (s != null) fade(world, s);

        Ring ring = new Ring(world, player.getUuid());
        ItemStack arrow = new ItemStack(Items.SPECTRAL_ARROW);
        arrow.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        Vec3d aim = aimPoint(world, player);
        for (int i = 0; i < ARROW_COUNT; i++) {
            DisplayEntity.ItemDisplayEntity d = EntityType.ITEM_DISPLAY.create(world);
            if (d == null) continue;
            d.setItemStack(arrow.copy());
            d.setTransformationMode(ModelTransformationMode.NONE);
            d.setTeleportDuration(1);
            d.setViewRange(2.0f);
            Vec3d p = slotPosition(player, i, 0);
            d.refreshPositionAndAngles(p.x, p.y, p.z, 0f, 0f);
            d.setTransformation(transformFor(aim.subtract(p)));
            TempEntities.track(d);
            world.spawnEntity(d);
            ring.slots[i] = d;
            world.spawnParticles(ParticleTypes.ENCHANT, p.x, p.y, p.z, 10, 0.15, 0.15, 0.15, 0.5);
            world.spawnParticles(ParticleTypes.WAX_OFF, p.x, p.y, p.z, 2, 0.1, 0.1, 0.1, 0.02);
        }
        RINGS.put(player.getUuid(), ring);

        Vec3d c = player.getPos();
        world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_ENCHANTMENT_TABLE_USE, SoundCategory.PLAYERS, 1.0f, 1.2f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ITEM_CROSSBOW_LOADING_END.value(), SoundCategory.PLAYERS, 1.2f, 0.8f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.5f, 1.2f);
    }

    /** Returns false when the ring should be dismissed. */
    private static boolean tickRing(Ring r) {
        PlayerEntity player = r.world.getPlayerByUuid(r.owner);
        if (player == null || !player.isAlive() || r.remaining() == 0 || ++r.age > RING_TIMEOUT) return false;

        Vec3d aim = aimPoint(r.world, player);
        for (int i = 0; i < ARROW_COUNT; i++) {
            DisplayEntity.ItemDisplayEntity d = r.slots[i];
            if (d == null) continue;
            Vec3d p = slotPosition(player, i, r.age);
            d.setPosition(p.x, p.y, p.z);
            d.setTransformation(transformFor(aim.subtract(p)));
            d.setStartInterpolation(0);
            d.setInterpolationDuration(1);
            if (r.world.random.nextInt(10) == 0) {
                r.world.spawnParticles(ParticleTypes.WAX_OFF, p.x, p.y, p.z, 1, 0.1, 0.1, 0.1, 0.0);
            }
        }
        return true;
    }

    /** Seven evenly spaced slots on a ring just above the caster's head, slowly spinning and bobbing. */
    private static Vec3d slotPosition(PlayerEntity player, int slot, int age) {
        double angle = age * RING_SPIN + slot * (Math.PI * 2 / ARROW_COUNT);
        double bob = Math.sin(age * 0.15 + slot * 0.9) * 0.08;
        return player.getPos().add(Math.cos(angle) * RING_RADIUS, 2.4 + bob, Math.sin(angle) * RING_RADIUS);
    }

    /** Exactly what the crosshair is on: the first creature or block along the look ray, or far away. */
    private static Vec3d aimPoint(ServerWorld world, PlayerEntity player) {
        Vec3d eye = player.getEyePos();
        Vec3d end = eye.add(player.getRotationVector().multiply(RANGE));
        BlockHitResult block = world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
        if (block.getType() != HitResult.Type.MISS) end = block.getPos();
        EntityHitResult entity = ProjectileUtil.raycast(player, eye, end, new Box(eye, end).expand(1.0),
                e -> e.isAlive() && !e.isSpectator() && e.canHit(), eye.squaredDistanceTo(end));
        return entity != null ? entity.getPos() : end;
    }

    private static AffineTransformation transformFor(Vec3d direction) {
        Vector3f dir = direction.lengthSquared() > 1.0e-6 ? direction.normalize().toVector3f() : new Vector3f(0, 0, 1);
        Quaternionf rotation = new Quaternionf().rotationTo(TIP_LOCAL, dir);
        return new AffineTransformation(new Vector3f(), rotation, new Vector3f(SCALE, SCALE, SCALE), new Quaternionf());
    }

    // ---------------------------------------------------------------------
    // Firing
    // ---------------------------------------------------------------------

    private static void fire(Ring ring, PlayerEntity player) {
        ServerWorld world = ring.world;
        Vec3d aim = aimPoint(world, player);

        // Launch the arrow closest to where you're looking, so shots leave from the front of the ring
        Vec3d look = player.getRotationVector();
        int pick = -1;
        double best = -Double.MAX_VALUE;
        for (int i = 0; i < ARROW_COUNT; i++) {
            if (ring.slots[i] == null) continue;
            double score = ring.slots[i].getPos().subtract(player.getPos()).normalize().dotProduct(look);
            if (score > best) {
                best = score;
                pick = i;
            }
        }
        if (pick < 0) return;

        DisplayEntity.ItemDisplayEntity d = ring.slots[pick];
        ring.slots[pick] = null;
        Vec3d pos = d.getPos();
        Vec3d dir = aim.subtract(pos);
        if (dir.lengthSquared() < 1.0e-4) dir = look;
        Vec3d vel = dir.normalize().multiply(SPEED);
        d.setTransformation(transformFor(vel));
        FLYING.add(new Flying(world, d, player.getUuid(), pos, vel));

        world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ENTITY_ARROW_SHOOT, SoundCategory.PLAYERS, 1.0f, 1.2f + world.random.nextFloat() * 0.3f);
        world.playSound(null, pos.x, pos.y, pos.z, SoundEvents.BLOCK_AMETHYST_BLOCK_HIT, SoundCategory.PLAYERS, 0.8f, 1.6f);
    }

    // ---------------------------------------------------------------------
    // Flight
    // ---------------------------------------------------------------------

    /** Returns false once the arrow is finished. */
    private static boolean tickFlying(Flying f) {
        ServerWorld world = f.world;
        f.age++;

        if (f.stuck >= 0) {
            if (++f.stuck >= STUCK_TICKS) {
                fade(world, f.display);
                return false;
            }
            return true;
        }
        if (f.age > FLIGHT_TICKS) {
            fade(world, f.display);
            return false;
        }

        Vec3d from = f.pos;
        Vec3d to = from.add(f.vel);
        PlayerEntity owner = world.getPlayerByUuid(f.owner);

        // First creature along the path takes the arrow
        Box sweep = new Box(from, to).expand(0.5);
        LivingEntity hitEntity = null;
        double hitDist = Double.MAX_VALUE;
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, sweep, e -> e.isAlive() && !e.getUuid().equals(f.owner) && !e.isSpectator())) {
            var clip = e.getBoundingBox().expand(0.2).raycast(from, to);
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
            f.pos = wall.getPos().subtract(f.vel.normalize().multiply(0.25));
            moveDisplay(f);
            f.stuck = 0;
            world.playSound(null, f.pos.x, f.pos.y, f.pos.z, SoundEvents.ENTITY_ARROW_HIT, SoundCategory.PLAYERS, 1.0f, 1.2f);
            world.spawnParticles(ParticleTypes.CRIT, f.pos.x, f.pos.y, f.pos.z, 6, 0.1, 0.1, 0.1, 0.15);
            return true;
        }
        if (hitEntity != null) {
            strike(world, owner, hitEntity, f.vel);
            return false;
        }

        f.pos = to;
        moveDisplay(f);
        for (int i = 0; i < 3; i++) {
            Vec3d p = from.lerp(to, i / 3.0);
            world.spawnParticles(i == 0 ? ParticleTypes.WAX_OFF : ParticleTypes.CRIT, p.x, p.y, p.z, 1, 0.02, 0.02, 0.02, 0.0);
        }
        return true;
    }

    private static void moveDisplay(Flying f) {
        f.display.setPosition(f.pos.x, f.pos.y, f.pos.z);
        f.display.setStartInterpolation(0);
        f.display.setInterpolationDuration(1);
    }

    private static void strike(ServerWorld world, PlayerEntity owner, LivingEntity target, Vec3d vel) {
        // Spammed arrows would otherwise bounce off the target's brief invulnerability after each hit
        target.timeUntilRegen = 0;
        target.damage(owner != null ? world.getDamageSources().indirectMagic(owner, owner) : world.getDamageSources().magic(), DAMAGE);
        Vec3d push = vel.normalize().multiply(0.25);
        target.addVelocity(push.x, 0.08, push.z);
        target.velocityModified = true;

        Vec3d c = target.getBoundingBox().getCenter();
        world.spawnParticles(ParticleTypes.CRIT, c.x, c.y, c.z, 12, 0.25, 0.3, 0.25, 0.3);
        world.spawnParticles(ParticleTypes.ENCHANTED_HIT, c.x, c.y, c.z, 8, 0.25, 0.3, 0.25, 0.3);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_ARROW_HIT, SoundCategory.PLAYERS, 1.0f, 1.0f);
        if (owner != null) {
            // The familiar "ding" when your arrow lands
            world.playSound(null, owner.getX(), owner.getY(), owner.getZ(), SoundEvents.ENTITY_ARROW_HIT_PLAYER, SoundCategory.PLAYERS, 0.5f, 1.2f);
        }
    }

    private static void fade(ServerWorld world, DisplayEntity.ItemDisplayEntity d) {
        Vec3d p = d.getPos();
        world.spawnParticles(ParticleTypes.ENCHANT, p.x, p.y, p.z, 8, 0.15, 0.15, 0.15, 0.3);
        world.spawnParticles(ParticleTypes.WAX_OFF, p.x, p.y, p.z, 2, 0.1, 0.1, 0.1, 0.02);
        TempEntities.discard(d);
    }
}
