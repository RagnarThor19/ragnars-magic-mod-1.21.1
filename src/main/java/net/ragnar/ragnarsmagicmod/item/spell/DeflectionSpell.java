package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ProjectileDeflection;
import net.minecraft.entity.decoration.Brightness;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.ItemStack;
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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A pane of magic glass flashes up in front of you for {@link #ACTIVE_TICKS} (about 0.6s). Any projectile that
 * actually strikes the pane is sent back wherever you are looking, a little faster than it came in. Miss the
 * timing, or let it pass beside the pane, and it hits you as normal.
 */
public class DeflectionSpell implements Spell {
    private static final int ACTIVE_TICKS = 12;
    private static final int FADE_TICKS = 3;
    private static final double DISTANCE = 1.5;        // how far in front of the eyes the pane stands
    private static final double HALF_WIDTH = 1.5;      // 3 blocks wide
    private static final double HALF_HEIGHT = 1.3;     // 2.6 blocks tall
    private static final float THICKNESS = 0.08f;
    private static final double SPEED_BOOST = 1.25;    // deflected projectiles come back this much faster
    private static final double MIN_SPEED = 0.8;

    private static final class Barrier {
        final ServerWorld world;
        final UUID owner;
        final DisplayEntity.BlockDisplayEntity pane;
        final Set<UUID> deflected = new HashSet<>();
        int age = 0;
        int flash = 0;

        Barrier(ServerWorld world, UUID owner, DisplayEntity.BlockDisplayEntity pane) {
            this.world = world;
            this.owner = owner;
            this.pane = pane;
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
                if (b.world == world && !tick(b)) {
                    TempEntities.discard(b.pane);
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
        ACTIVE.removeIf(b -> {
            if (!b.owner.equals(player.getUuid())) return false;
            TempEntities.discard(b.pane);
            return true;
        });

        DisplayEntity.BlockDisplayEntity pane = EntityType.BLOCK_DISPLAY.create(sw);
        if (pane == null) return false;
        pane.setBlockState(Blocks.LIGHT_BLUE_STAINED_GLASS.getDefaultState());
        pane.setBrightness(Brightness.FULL);
        pane.setTeleportDuration(1);
        pane.setViewRange(2.0f);
        Vec3d c = center(player);
        pane.refreshPositionAndAngles(c.x, c.y, c.z, 0f, 0f);
        pane.setTransformation(paneTransform(player.getRotationVector(), 0.3f, 0f));
        TempEntities.track(pane);
        sw.spawnEntity(pane);
        ACTIVE.add(new Barrier(sw, player.getUuid(), pane));

        sw.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BLOCK_GLASS_PLACE, SoundCategory.PLAYERS, 1.0f, 1.5f);
        sw.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.0f, 1.8f);
        return true;
    }

    /** Returns false once the pane has faded. */
    private static boolean tick(Barrier b) {
        PlayerEntity player = b.world.getPlayerByUuid(b.owner);
        if (player == null || !player.isAlive()) return false;
        b.age++;
        if (b.flash > 0) b.flash--;

        // The pane stays in front of you and turns with your view
        Vec3d look = player.getRotationVector().normalize();
        Vec3d c = center(player);
        b.pane.setPosition(c.x, c.y, c.z);

        if (b.age <= ACTIVE_TICKS) {
            float size = Math.min(1f, 0.3f + b.age * 0.35f);
            b.pane.setTransformation(paneTransform(look, size, b.flash > 0 ? 0.08f : 0f));
            b.pane.setStartInterpolation(0);
            b.pane.setInterpolationDuration(1);
            catchProjectiles(b, player, c, look);
            if (b.age % 3 == 0) edgeSparkle(b.world, c, look);
            return true;
        }
        int fade = b.age - ACTIVE_TICKS;
        if (fade <= FADE_TICKS) {
            b.pane.setTransformation(paneTransform(look, Math.max(0.01f, 1f - fade / (float) FADE_TICKS), 0f));
            b.pane.setStartInterpolation(0);
            b.pane.setInterpolationDuration(1);
            if (fade == 1) b.world.spawnParticles(ParticleTypes.WAX_OFF, c.x, c.y, c.z, 10, HALF_WIDTH * 0.6, HALF_HEIGHT * 0.6, 0.3, 0);
            return true;
        }
        return false;
    }

    private static Vec3d center(PlayerEntity player) {
        return player.getEyePos().add(player.getRotationVector().normalize().multiply(DISTANCE)).add(0, -0.2, 0);
    }

    /** Any projectile whose path this tick crosses the pane (not beside it) is sent back along your look. */
    private static void catchProjectiles(Barrier b, PlayerEntity player, Vec3d c, Vec3d look) {
        Vec3d right = paneRight(look);
        Vec3d up = right.crossProduct(look).normalize();
        Box area = new Box(c, c).expand(HALF_WIDTH + 5, HALF_HEIGHT + 5, HALF_WIDTH + 5);

        for (ProjectileEntity p : b.world.getEntitiesByClass(ProjectileEntity.class, area, p -> p.isAlive())) {
            if (b.deflected.contains(p.getUuid()) || p.getOwner() == player) continue;
            Vec3d vel = p.getVelocity();
            if (vel.dotProduct(look) >= 0) continue;          // only things coming at you

            // Its path from a tick ago to a tick ahead: did it cross the plane of the pane, and where?
            Vec3d from = p.getPos().subtract(vel);
            Vec3d to = p.getPos().add(vel);
            double da = from.subtract(c).dotProduct(look);
            double db = to.subtract(c).dotProduct(look);
            if (da < 0 || db > 0) continue;                    // doesn't pass through the plane
            double t = da / (da - db);
            Vec3d hit = from.add(to.subtract(from).multiply(t));
            Vec3d local = hit.subtract(c);
            if (Math.abs(local.dotProduct(right)) > HALF_WIDTH || Math.abs(local.dotProduct(up)) > HALF_HEIGHT) continue;

            deflect(b, player, p, hit, look, vel.length());
        }
    }

    private static void deflect(Barrier b, PlayerEntity player, ProjectileEntity p, Vec3d hit, Vec3d look, double speed) {
        // Take ownership (so it can't hit you and counts as your shot), then aim it down your view
        p.deflect(ProjectileDeflection.SIMPLE, player, player, true);
        double newSpeed = Math.max(MIN_SPEED, speed * SPEED_BOOST);
        Vec3d out = look.multiply(newSpeed);
        Vec3d start = hit.add(look.multiply(0.4));
        p.setPosition(start.x, start.y, start.z);
        p.setVelocity(out);
        // Face the new direction right away so arrows and tridents don't fly sideways for a tick
        double horizontal = Math.sqrt(out.x * out.x + out.z * out.z);
        p.setYaw((float) (MathHelper.atan2(out.x, out.z) * MathHelper.DEGREES_PER_RADIAN));
        p.setPitch((float) (MathHelper.atan2(out.y, horizontal) * MathHelper.DEGREES_PER_RADIAN));
        p.velocityModified = true;
        b.deflected.add(p.getUuid());
        b.flash = 3;

        ServerWorld w = b.world;
        w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, hit.x, hit.y, hit.z, 20, 0.2, 0.2, 0.2, 0.4);
        w.spawnParticles(ParticleTypes.END_ROD, hit.x, hit.y, hit.z, 6, 0.1, 0.1, 0.1, 0.1);
        w.spawnParticles(ParticleTypes.FLASH, hit.x, hit.y, hit.z, 1, 0, 0, 0, 0);
        w.playSound(null, hit.x, hit.y, hit.z, SoundEvents.BLOCK_GLASS_HIT, SoundCategory.PLAYERS, 1.5f, 1.6f);
        w.playSound(null, hit.x, hit.y, hit.z, SoundEvents.ITEM_SHIELD_BLOCK, SoundCategory.PLAYERS, 1.0f, 1.4f);
        w.playSound(null, hit.x, hit.y, hit.z, SoundEvents.BLOCK_AMETHYST_BLOCK_HIT, SoundCategory.PLAYERS, 1.5f, 1.2f);
    }

    /** Horizontal axis across the pane (falls back to east when looking straight up or down). */
    private static Vec3d paneRight(Vec3d look) {
        Vec3d r = new Vec3d(0, 1, 0).crossProduct(look);
        return r.lengthSquared() < 1.0e-4 ? new Vec3d(1, 0, 0) : r.normalize();
    }

    /** A thin glass slab centred on the display, facing along {@code look}, bulging slightly when hit. */
    private static AffineTransformation paneTransform(Vec3d look, float size, float bulge) {
        Vector3f n = look.toVector3f().normalize();
        Vector3f r = paneRight(look).toVector3f();
        Vector3f u = new Vector3f(n).cross(r).normalize();   // keeps the basis right-handed (a real rotation)
        Matrix3f rotation = new Matrix3f(r, u, n);   // block X -> across, Y -> up, Z -> facing
        float grow = 1f + bulge;
        return new AffineTransformation(new Matrix4f()
                .mul(new Matrix4f(rotation))
                .scale((float) (HALF_WIDTH * 2) * size * grow, (float) (HALF_HEIGHT * 2) * size * grow, THICKNESS)
                .translate(-0.5f, -0.5f, -0.5f));
    }

    private static void edgeSparkle(ServerWorld w, Vec3d c, Vec3d look) {
        Vec3d right = paneRight(look);
        Vec3d up = right.crossProduct(look).normalize();
        for (int i = 0; i < 6; i++) {
            double sx = (w.random.nextBoolean() ? 1 : -1) * HALF_WIDTH;
            double sy = (w.random.nextDouble() * 2 - 1) * HALF_HEIGHT;
            if (w.random.nextBoolean()) {
                sx = (w.random.nextDouble() * 2 - 1) * HALF_WIDTH;
                sy = (w.random.nextBoolean() ? 1 : -1) * HALF_HEIGHT;
            }
            Vec3d p = c.add(right.multiply(sx)).add(up.multiply(sy));
            w.spawnParticles(ParticleTypes.WAX_OFF, p.x, p.y, p.z, 1, 0, 0, 0, 0);
        }
    }
}
