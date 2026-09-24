package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.ElderGuardianEntity;
import net.minecraft.entity.mob.WardenEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.network.TelekinesisPayloads;
import net.ragnar.ragnarsmagicmod.util.Aim;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * A gravity gun. Cast at a creature or object to grab it; it floats in front of you wherever you look,
 * and the mouse wheel pushes it further out or reels it in. Swing your view and let go to throw it.
 * Cast again to let go early; otherwise it drops after {@link #HOLD_TICKS}. The cooldown starts when
 * you let go. Players and bosses can't be grabbed.
 */
public class TelekinesisSpell implements Spell {
    private static final double RANGE = 24.0;
    private static final double AIM_CONE = 5.0;
    private static final int HOLD_TICKS = 20 * 5;
    private static final double MIN_DIST = 2.0;
    private static final double MAX_DIST = 24.0;
    private static final double SCROLL_STEP = 1.5;
    private static final double FOLLOW = 0.4;       // how hard the object is pulled to the hold point
    private static final double MAX_SPEED = 2.0;    // also the fastest you can fling something

    private static final DustParticleEffect BEAM = new DustParticleEffect(new Vector3f(1.0f, 0.6f, 0.15f), 0.8f);
    private static final DustParticleEffect BEAM_CORE = new DustParticleEffect(new Vector3f(1.0f, 0.9f, 0.6f), 0.5f);

    private static final class Grab {
        final ServerWorld world;
        final UUID playerId;
        final Entity target;
        double dist;
        int age = 0;

        Grab(ServerWorld world, UUID playerId, Entity target, double dist) {
            this.world = world;
            this.playerId = playerId;
            this.target = target;
            this.dist = dist;
        }
    }

    private static final Map<UUID, Grab> HOLDS = new HashMap<>();
    private static boolean registered = false;

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            Iterator<Grab> it = HOLDS.values().iterator();
            while (it.hasNext()) {
                Grab g = it.next();
                if (g.world != world) continue;
                Boolean timedOut = tick(g);
                if (timedOut != null) {
                    it.remove();
                    letGo(g, timedOut);
                }
            }
        });
    }

    // The XP is paid on the grab; letting go is free
    @Override
    public int xpCost(PlayerEntity player, int tomeCost) {
        return HOLDS.containsKey(player.getUuid()) ? 0 : tomeCost;
    }

    // No cooldown while holding (so you can cast again to let go); the full cooldown starts on release
    @Override
    public int cooldownAfterCast(PlayerEntity player, int tomeCooldown) {
        return HOLDS.containsKey(player.getUuid()) ? 0 : tomeCooldown;
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        ensureRegistered();
        ServerWorld sw = (ServerWorld) world;

        Grab held = HOLDS.remove(player.getUuid());
        if (held != null) {
            letGo(held, false);
            return true;
        }

        Entity target = Aim.target(sw, player, RANGE, AIM_CONE, TelekinesisSpell::isGrabbable);
        if (target == null) {
            Entity boss = Aim.target(sw, player, RANGE, AIM_CONE, TelekinesisSpell::isBoss);
            player.sendMessage(Text.literal(boss != null ? "That is too powerful to control." : "Nothing to grab."), true);
            return false;
        }

        target.stopRiding();
        target.removeAllPassengers();
        double dist = MathHelper.clamp(player.getEyePos().distanceTo(target.getBoundingBox().getCenter()), MIN_DIST, MAX_DIST);
        HOLDS.put(player.getUuid(), new Grab(sw, player.getUuid(), target, dist));
        if (target instanceof LivingEntity le) {
            le.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, HOLD_TICKS + 10, 0, false, false));
        } else {
            target.setGlowing(true);
        }
        if (player instanceof ServerPlayerEntity sp) TelekinesisPayloads.sendHolding(sp, true);

        Vec3d c = target.getBoundingBox().getCenter();
        sw.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, 20, target.getWidth() * 0.5, target.getHeight() * 0.5, target.getWidth() * 0.5, 0.2);
        world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BLOCK_BEACON_POWER_SELECT, SoundCategory.PLAYERS, 1.0f, 1.6f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_ILLUSIONER_PREPARE_MIRROR, SoundCategory.PLAYERS, 1.0f, 1.4f);
        return true;
    }

    /** Called from the scroll packet: positive steps push the held object away. */
    public static void onScroll(ServerPlayerEntity player, int steps) {
        Grab g = HOLDS.get(player.getUuid());
        if (g == null || steps == 0) return;
        g.dist = MathHelper.clamp(g.dist + steps * SCROLL_STEP, MIN_DIST, MAX_DIST);
    }

    /** Advances one hold. Returns null to keep holding, otherwise whether it ended by running out of time. */
    private static Boolean tick(Grab g) {
        ServerWorld world = g.world;
        PlayerEntity player = world.getPlayerByUuid(g.playerId);
        Entity target = g.target;
        if (player == null || !player.isAlive() || target.isRemoved() || !target.isAlive() || target.getWorld() != world) return false;
        if (++g.age > HOLD_TICKS) return true;

        // Pull toward the point on the crosshair ray; whatever speed it has when released is its throw
        Vec3d hold = player.getEyePos().add(player.getRotationVector().multiply(g.dist));
        Vec3d center = target.getBoundingBox().getCenter();
        Vec3d vel = hold.subtract(center).multiply(FOLLOW);
        if (vel.length() > MAX_SPEED) vel = vel.normalize().multiply(MAX_SPEED);
        target.setVelocity(vel);
        target.velocityModified = true;
        target.fallDistance = 0;

        renderBeam(world, player, target, g.age);
        if (g.age % 20 == 1) {
            world.playSound(null, center.x, center.y, center.z, SoundEvents.BLOCK_BEACON_AMBIENT, SoundCategory.PLAYERS, 0.8f, 1.9f);
        }
        return null;
    }

    private static void letGo(Grab g, boolean timedOut) {
        ServerWorld world = g.world;
        Entity target = g.target;
        if (target instanceof LivingEntity le) le.removeStatusEffect(StatusEffects.GLOWING);
        else target.setGlowing(false);

        PlayerEntity player = world.getPlayerByUuid(g.playerId);
        if (player instanceof ServerPlayerEntity sp) {
            TelekinesisPayloads.sendHolding(sp, false);
            if (timedOut) {
                // Not a cast, so start the cooldown here
                var tome = ModItems.TOME_OF_TELEKINESIS;
                ItemStack staff = StaffItem.findStaffWith(sp, tome);
                if (!staff.isEmpty()) StaffItem.applyCooldown(world, sp, staff, tome, tome.getCooldown());
                else sp.getItemCooldownManager().set(tome, tome.getCooldown());
            }
        }

        Vec3d c = target.getBoundingBox().getCenter();
        world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, 12, target.getWidth() * 0.4, target.getHeight() * 0.4, target.getWidth() * 0.4, 0.15);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_BEACON_DEACTIVATE, SoundCategory.PLAYERS, 0.8f, 1.8f);
    }

    /** A wobbling orange tether from the staff to the held object. */
    private static void renderBeam(ServerWorld world, PlayerEntity player, Entity target, int age) {
        Vec3d look = player.getRotationVector();
        Vec3d from = player.getEyePos().add(look.multiply(0.8)).add(0, -0.3, 0);
        Vec3d to = target.getBoundingBox().getCenter();
        Vec3d path = to.subtract(from);
        double len = path.length();
        if (len < 1.0e-3) return;
        Vec3d dir = path.multiply(1.0 / len);
        Vec3d helper = Math.abs(dir.y) > 0.9 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
        Vec3d u = dir.crossProduct(helper).normalize();
        Vec3d v = dir.crossProduct(u).normalize();

        int steps = (int) Math.ceil(len * 2.5);
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            // The tether sags into a gentle spiral that pinches at both ends
            double wobble = Math.sin(Math.PI * t) * 0.18;
            double a = age * 0.6 + t * 9.0;
            Vec3d p = from.add(path.multiply(t)).add(u.multiply(Math.cos(a) * wobble)).add(v.multiply(Math.sin(a) * wobble));
            world.spawnParticles(i % 2 == 0 ? BEAM : BEAM_CORE, p.x, p.y, p.z, 1, 0, 0, 0, 0);
        }
        if (age % 3 == 0) {
            world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, to.x, to.y, to.z, 2, target.getWidth() * 0.4, target.getHeight() * 0.4, target.getWidth() * 0.4, 0.05);
        }
    }

    private static boolean isBoss(Entity e) {
        return e instanceof EnderDragonEntity || e instanceof WitherEntity || e instanceof ElderGuardianEntity || e instanceof WardenEntity;
    }

    private static boolean isGrabbable(Entity e) {
        if (e instanceof PlayerEntity || isBoss(e)) return false;
        return e instanceof LivingEntity || e instanceof ItemEntity || e instanceof AbstractMinecartEntity
                || e instanceof BoatEntity || e instanceof TntEntity || e instanceof FallingBlockEntity;
    }
}
