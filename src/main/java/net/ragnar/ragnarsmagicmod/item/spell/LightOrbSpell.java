package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LightBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Vector3f;

import java.util.*;

/**
 * BEGINNER utility spell:
 * Spawns a light-yellow “fairy” orb that wanders around the player like an Allay,
 * emits REAL block light via the invisible LIGHT block, lasts 60s, and cleans up after itself.
 * Particles are minimal: a small halo + faint core, plus an occasional pulse ring (no trail).
 */
public class LightOrbSpell implements Spell {

    // --- Lifetime & motion ---
    private static final int LIFETIME_TICKS = 20 * 60; // 60s
    private static final int REPOSITION_INTERVAL = 40; // every 2s pick a new wander target
    private static final double MOVE_SPEED = 0.08;     // gentle float
    private static final double ORBIT_RADIUS = 2.5;    // wander distance around player
    private static final double ORBIT_HEIGHT = 1.4;    // around head level
    private static final double DRIFT_VARIANCE = 0.5;  // small randomness

    // --- Real light (LIGHT block) ---
    private static final int LIGHT_LEVEL = 14; // 0..15 (14 is bright without maxing)
    // (we place the LIGHT block exactly at the orb's floored position)

    // --- Visuals: subtle but visible orb (no trail) ---
    private static final DustParticleEffect LIGHT_YELLOW =
            new DustParticleEffect(new Vector3f(1.0f, 0.95f, 0.6f), 1.2f); // warm pale yellow

    private static final int ORB_SHELL_POINTS = 8;   // halo particles each tick
    private static final int ORB_CORE_POINTS  = 2;   // faint core sparkles
    private static final int PULSE_INTERVAL   = 10;  // every 10 ticks, draw a small ring
    private static final int PULSE_POINTS     = 12;  // points on that ring
    private static final double ORB_RADIUS    = 0.22; // halo radius
    private static final double PULSE_RADIUS  = 0.28; // pulse ring radius

    // --- State tracking per world ---
    private static final Map<RegistryKey<World>, List<Orb>> ACTIVE = new HashMap<>();
    private static boolean TICK_REGISTERED = false;

    private static void ensureTicker() {
        if (TICK_REGISTERED) return;
        ServerTickEvents.END_WORLD_TICK.register(LightOrbSpell::tickWorld);
        TICK_REGISTERED = true;
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        ensureTicker();

        // gentle spawn chime
        world.playSound(null, player.getBlockPos(),
                SoundEvents.BLOCK_AMETHYST_BLOCK_STEP, SoundCategory.PLAYERS,
                1.0f, 1.4f);

        // start near head height
        Vec3d start = player.getPos().add(0, ORBIT_HEIGHT, 0);

        ServerWorld sw = (ServerWorld) world;
        ACTIVE.computeIfAbsent(sw.getRegistryKey(), k -> new ArrayList<>())
                .add(new Orb(player.getUuid(), start, sw.getTime()));

        return true;
    }

    private static void tickWorld(ServerWorld world) {
        List<Orb> list = ACTIVE.get(world.getRegistryKey());
        if (list == null || list.isEmpty()) return;

        long now = world.getTime();
        Iterator<Orb> it = list.iterator();

        while (it.hasNext()) {
            Orb orb = it.next();
            PlayerEntity p = world.getPlayerByUuid(orb.owner);
            if (p == null) {
                clearLight(world, orb.lightPos);
                it.remove();
                continue;
            }

            // end of life -> soft fade + cleanup
            if (now - orb.spawnTick > LIFETIME_TICKS) {
                for (int i = 0; i < 10; i++) {
                    world.spawnParticles(LIGHT_YELLOW,
                            orb.pos.x, orb.pos.y, orb.pos.z, 1, 0.1, 0.1, 0.1, 0.0);
                }
                clearLight(world, orb.lightPos);
                it.remove();
                continue;
            }

            // pick a new wander target every few seconds
            if (now - orb.lastTargetChange > REPOSITION_INTERVAL) {
                orb.target = randomTargetAround(p, world.getRandom());
                orb.lastTargetChange = now;
            }

            // smooth float toward target
            Vec3d delta = orb.target.subtract(orb.pos);
            orb.pos = orb.pos.add(delta.multiply(MOVE_SPEED));

            // move/refresh the real light block with the orb
            BlockPos newLight = BlockPos.ofFloored(orb.pos);
            if (orb.lightPos == null || !orb.lightPos.equals(newLight)) {
                clearLight(world, orb.lightPos);
                placeLight(world, newLight);
                orb.lightPos = newLight;
            }

            // --- Visible but clean orb: halo + faint core, no trailing line ---

            // halo around orb
            for (int i = 0; i < ORB_SHELL_POINTS; i++) {
                double theta = (2 * Math.PI * i) / ORB_SHELL_POINTS;
                double xOff = Math.cos(theta) * ORB_RADIUS;
                double zOff = Math.sin(theta) * ORB_RADIUS;
                double yOff = (world.getRandom().nextDouble() - 0.5) * 0.06;
                world.spawnParticles(LIGHT_YELLOW,
                        orb.pos.x + xOff, orb.pos.y + yOff, orb.pos.z + zOff,
                        1, 0, 0, 0, 0);
            }

            // faint core sparkles
            for (int i = 0; i < ORB_CORE_POINTS; i++) {
                double s = 0.04;
                world.spawnParticles(LIGHT_YELLOW,
                        orb.pos.x + (world.getRandom().nextDouble() - 0.5) * s,
                        orb.pos.y + (world.getRandom().nextDouble() - 0.5) * s,
                        orb.pos.z + (world.getRandom().nextDouble() - 0.5) * s,
                        1, 0, 0, 0, 0);
            }

            // subtle pulse ring sometimes (adds motion readability; still no trail)
            if ((now % PULSE_INTERVAL) == 0) {
                for (int i = 0; i < PULSE_POINTS; i++) {
                    double ang = (2 * Math.PI * i) / PULSE_POINTS;
                    double xOff = Math.cos(ang) * PULSE_RADIUS;
                    double zOff = Math.sin(ang) * PULSE_RADIUS;
                    world.spawnParticles(LIGHT_YELLOW,
                            orb.pos.x + xOff, orb.pos.y, orb.pos.z + zOff,
                            1, 0, 0, 0, 0);
                }
            }
        }
    }

    // pick a random point around the player to wander toward
    private static Vec3d randomTargetAround(PlayerEntity p, net.minecraft.util.math.random.Random r) {
        double angle = r.nextDouble() * Math.PI * 2;
        double radius = ORBIT_RADIUS + (r.nextDouble() - 0.5) * DRIFT_VARIANCE;
        double height = ORBIT_HEIGHT + (r.nextDouble() - 0.5) * DRIFT_VARIANCE;
        double x = p.getX() + Math.cos(angle) * radius;
        double y = p.getY() + height;
        double z = p.getZ() + Math.sin(angle) * radius;
        return new Vec3d(x, y, z);
    }

    private static void placeLight(ServerWorld world, BlockPos pos) {
        if (!world.isAir(pos)) return;
        BlockState state = Blocks.LIGHT.getDefaultState().with(LightBlock.LEVEL_15, LIGHT_LEVEL);
        world.setBlockState(pos, state, 3);
    }

    private static void clearLight(ServerWorld world, BlockPos pos) {
        if (pos != null && world.getBlockState(pos).isOf(Blocks.LIGHT)) {
            world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
        }
    }

    private static class Orb {
        final UUID owner;
        Vec3d pos;
        Vec3d target;
        BlockPos lightPos;
        final long spawnTick;
        long lastTargetChange;

        Orb(UUID owner, Vec3d pos, long spawnTick) {
            this.owner = owner;
            this.pos = pos;
            this.spawnTick = spawnTick;
            this.lastTargetChange = spawnTick;
            this.target = pos;
        }
    }
}
