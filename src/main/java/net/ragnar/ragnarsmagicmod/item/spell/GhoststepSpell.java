package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Become a ghost for a moment: step up to {@link #MAX_DISTANCE} blocks forward, straight through
 * walls, and drift gently onward before you solidify again. Unbreakable blocks (bedrock,
 * barriers) can't be passed.
 */
public class GhoststepSpell implements Spell {
    private static final double MAX_DISTANCE = 7.0;
    private static final double SEARCH_STEP = 0.25;
    private static final int DRIFT_TICKS = 12;
    private static final double DRIFT_SPEED = 0.42;

    private static final DustParticleEffect AFTERIMAGE = new DustParticleEffect(new Vector3f(0.75f, 0.9f, 1.0f), 1.1f);

    private record Drift(ServerWorld world, UUID player, Vec3d dir, int ticksLeft) {}

    private static final List<Drift> DRIFTING = new ArrayList<>();
    private static boolean registered = false;

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            List<Drift> next = new ArrayList<>();
            Iterator<Drift> it = DRIFTING.iterator();
            while (it.hasNext()) {
                Drift d = it.next();
                if (d.world() != world) continue;
                it.remove();
                PlayerEntity p = world.getPlayerByUuid(d.player());
                if (p == null || !p.isAlive()) continue;

                // Glide: keep carrying the player forward, easing out, with a ghostly wake
                double ease = d.ticksLeft() / (double) DRIFT_TICKS;
                Vec3d v = p.getVelocity();
                p.setVelocity(d.dir().x * DRIFT_SPEED * ease, Math.max(v.y, -0.02), d.dir().z * DRIFT_SPEED * ease);
                p.velocityModified = true;
                p.fallDistance = 0;
                world.spawnParticles(ParticleTypes.SOUL, p.getX(), p.getY() + 0.8, p.getZ(), 1, 0.2, 0.4, 0.2, 0.01);
                world.spawnParticles(AFTERIMAGE, p.getX() - d.dir().x * 0.6, p.getY() + 1.0, p.getZ() - d.dir().z * 0.6, 3, 0.2, 0.5, 0.2, 0);

                if (d.ticksLeft() > 1) next.add(new Drift(d.world(), d.player(), d.dir(), d.ticksLeft() - 1));
                else world.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM, SoundCategory.PLAYERS, 0.4f, 0.6f);
            }
            DRIFTING.addAll(next);
        });
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        if (!(world instanceof ServerWorld sw) || !(player instanceof ServerPlayerEntity sp)) return false;
        ensureRegistered();

        Vec3d look = player.getRotationVec(1.0F);
        Vec3d dir = new Vec3d(look.x, 0.0, look.z);
        if (dir.lengthSquared() < 1.0e-4) return false; // looking straight up/down
        dir = dir.normalize();

        Vec3d start = player.getPos();
        Vec3d target = findDestination(sw, player, start, dir);
        if (target == null) {
            player.sendMessage(Text.literal("There's nowhere to step to."), true);
            return false;
        }

        leaveAfterimage(sw, player, start, target);
        world.playSound(null, start.x, start.y, start.z, SoundEvents.ENTITY_VEX_CHARGE, SoundCategory.PLAYERS, 0.8f, 1.3f);
        world.playSound(null, start.x, start.y, start.z, SoundEvents.PARTICLE_SOUL_ESCAPE.value(), SoundCategory.PLAYERS, 1.5f, 1.0f);

        // Rotation is sent relative to the yaw/pitch passed here, so passing the current values keeps the
        // player's view exactly where it is
        sp.networkHandler.requestTeleport(target.x, target.y, target.z, player.getYaw(), player.getPitch(),
                EnumSet.of(PositionFlag.Y_ROT, PositionFlag.X_ROT));
        player.fallDistance = 0;
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, DRIFT_TICKS + 6, 0, false, false, false));
        DRIFTING.add(new Drift(sw, player.getUuid(), dir, DRIFT_TICKS));

        world.playSound(null, target.x, target.y, target.z, SoundEvents.ENTITY_ILLUSIONER_MIRROR_MOVE, SoundCategory.PLAYERS, 0.8f, 1.2f);
        sw.spawnParticles(ParticleTypes.SOUL, target.x, target.y + 1.0, target.z, 10, 0.3, 0.5, 0.3, 0.03);
        return true;
    }

    /**
     * The furthest spot along the step where the player fits, passing through any breakable blocks
     * on the way. Tries level ground first, then a block up or down to follow slopes.
     */
    private static Vec3d findDestination(ServerWorld world, PlayerEntity player, Vec3d start, Vec3d dir) {
        double limit = MAX_DISTANCE;
        // Can't ghost through unbreakable blocks
        for (double d = SEARCH_STEP; d <= MAX_DISTANCE; d += SEARCH_STEP) {
            Vec3d p = start.add(dir.multiply(d));
            if (isUnbreakable(world, BlockPos.ofFloored(p.x, p.y + 0.5, p.z)) || isUnbreakable(world, BlockPos.ofFloored(p.x, p.y + 1.5, p.z))) {
                limit = d - SEARCH_STEP;
                break;
            }
        }

        for (double d = limit; d >= 1.0; d -= SEARCH_STEP) {
            Vec3d p = start.add(dir.multiply(d));
            for (double dy : new double[]{0.0, 1.0, -1.0}) {
                Vec3d candidate = new Vec3d(p.x, Math.floor(p.y) + dy + 0.01, p.z);
                if (fits(world, player, candidate)) return candidate;
            }
        }
        return null;
    }

    private static boolean isUnbreakable(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.getHardness(world, pos) < 0;
    }

    private static boolean fits(ServerWorld world, PlayerEntity player, Vec3d feet) {
        Box box = player.getDimensions(player.getPose()).getBoxAt(feet);
        return world.isSpaceEmpty(player, box) && !world.containsFluid(box.contract(0.1));
    }

    /** A ghostly silhouette left behind and a wisp trail across the gap. */
    private static void leaveAfterimage(ServerWorld world, PlayerEntity player, Vec3d start, Vec3d end) {
        for (double y = 0.1; y < player.getHeight(); y += 0.2) {
            world.spawnParticles(AFTERIMAGE, start.x, start.y + y, start.z, 2, 0.18, 0.02, 0.18, 0);
        }
        Vec3d path = end.subtract(start);
        int steps = (int) (path.length() * 3);
        for (int i = 0; i <= steps; i++) {
            Vec3d p = start.add(path.multiply(i / (double) Math.max(1, steps)));
            world.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, p.x, p.y + 1.0, p.z, 1, 0.1, 0.3, 0.1, 0.005);
            if (i % 2 == 0) world.spawnParticles(AFTERIMAGE, p.x, p.y + 0.6, p.z, 1, 0.15, 0.4, 0.15, 0);
        }
    }
}
