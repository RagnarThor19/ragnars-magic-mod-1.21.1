package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.registry.RegistryKey;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.server.world.ServerWorld;
import org.joml.Vector3f;

import java.util.*;

public class LightningCascadeSpell implements Spell {

    private static final double RANGE = 64.0;
    private static final int RADIUS = 7;

    // Timing (ticks @ 20 tps)
    private static final int WARN_DURATION = 60;   // 3.0s of goat screams + particles
    private static final int STRIKE_DURATION = 40; // 2.0s of lightning
    private static final int TOTAL_DURATION = WARN_DURATION + STRIKE_DURATION;

    // Effect pacing during warning
    private static final int SCREAM_EVERY_TICKS = 6;   // every 0.3s one scream somewhere
    private static final int PARTICLE_COLUMNS_PER_TICK = 6; // rising columns per tick

    // Lightning pacing
    private static final int STRIKES_PER_TICK_MIN = 1; // between 1–2 bolts per tick
    private static final int STRIKES_PER_TICK_MAX = 2;

    private static final Map<RegistryKey<World>, List<Cascade>> ACTIVE = new HashMap<>();
    private static boolean TICK_REGISTERED = false;

    /** Registers the per-world tick hook once (kept inside this class). */
    private static void ensureTickerRegistered() {
        if (TICK_REGISTERED) return;
        ServerTickEvents.END_WORLD_TICK.register(LightningCascadeSpell::tickWorld);
        TICK_REGISTERED = true;
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        ensureTickerRegistered();

        // Your exact horn logic
        var horn = SoundEvents.GOAT_HORN_SOUNDS.get(5).value();
        world.playSound(null, player.getBlockPos(), horn, SoundCategory.PLAYERS, 1.3f, 0.65f);

        // Raycast to find target center
        HitResult hit = player.raycast(RANGE, 0.0f, false);
        BlockPos center;
        if (hit.getType() == HitResult.Type.BLOCK) {
            BlockHitResult bhr = (BlockHitResult) hit;
            center = bhr.getBlockPos();
        } else {
            Vec3d ahead = player.getCameraPosVec(0.0f)
                    .add(player.getRotationVector().normalize().multiply(8.0));
            center = BlockPos.ofFloored(ahead);
        }

        // Track a new cascade for this world
        ServerWorld sw = (ServerWorld) world;
        ACTIVE.computeIfAbsent(sw.getRegistryKey(), k -> new ArrayList<>())
                .add(new Cascade(center, sw.getTime()));

        return true;
    }

    /** Tick handler: drives warning phase then strike phase per active cascade. */
    private static void tickWorld(ServerWorld world) {
        List<Cascade> cascades = ACTIVE.get(world.getRegistryKey());
        if (cascades == null || cascades.isEmpty()) return;

        Random rand = world.getRandom();
        long now = world.getTime();

        // remove finished cascades safely
        Iterator<Cascade> it = cascades.iterator();
        while (it.hasNext()) {
            Cascade c = it.next();
            int age = (int) (now - c.startTick);

            if (age < WARN_DURATION) {
                // ----- Warning phase: goat screams + rising particles -----
                // Screams: every SCREAM_EVERY_TICKS
                if (age % SCREAM_EVERY_TICKS == 0) {
                    BlockPos p = topSolidPos(world, randomInCircle(c.center, RADIUS, rand));
                    world.playSound(null, p, SoundEvents.ENTITY_GOAT_SCREAMING_AMBIENT,
                            SoundCategory.HOSTILE, 1.5f, 0.9f + rand.nextFloat() * 0.2f);
                }

                // Rising yellow particle columns each tick
                for (int i = 0; i < PARTICLE_COLUMNS_PER_TICK; i++) {
                    BlockPos base = topSolidPos(world, randomInCircle(c.center, RADIUS, rand));
                    double x = base.getX() + 0.5 + (rand.nextDouble() - 0.5) * 0.8;
                    double z = base.getZ() + 0.5 + (rand.nextDouble() - 0.5) * 0.8;

                    // 6-step vertical rise
                    for (int h = 0; h < 6; h++) {
                        double y = base.getY() + 0.1 + h * 0.2;
                        world.spawnParticles(
                                new DustParticleEffect(new Vector3f(1.0f, 0.95f, 0.1f), 1.0f),
                                x, y, z,
                                1, 0.0, 0.02, 0.0, 0.0
                        );
                    }
                }

            } else if (age < TOTAL_DURATION) {
                // ----- Strike phase: continuous lightning for ~2s -----
                int strikes = MathHelper.nextInt(rand, STRIKES_PER_TICK_MIN, STRIKES_PER_TICK_MAX);
                for (int s = 0; s < strikes; s++) {
                    BlockPos p = topSolidPos(world, randomInCircle(c.center, RADIUS, rand));
                    LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(world);
                    if (bolt != null) {
                        bolt.refreshPositionAfterTeleport(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
                        world.spawnEntity(bolt);
                    }
                }

            } else {
                // Done
                it.remove();
            }
        }
    }

    // -------- helpers --------

    /** Pick a random position inside a circle on XZ around center, keep Y from center. */
    private static BlockPos randomInCircle(BlockPos center, int radius, Random rand) {
        double r = radius * Math.sqrt(rand.nextDouble());
        double theta = rand.nextDouble() * MathHelper.TAU;
        int dx = MathHelper.floor(r * Math.cos(theta));
        int dz = MathHelper.floor(r * Math.sin(theta));
        return center.add(dx, 0, dz);
    }

    /** Snap to the top solid position to keep effects on the ground. */
    private static BlockPos topSolidPos(World world, BlockPos pos) {
        return world.getTopPosition(Heightmap.Type.MOTION_BLOCKING, pos);
    }

    /** Per-instance cascade state. */
    private record Cascade(BlockPos center, long startTick) {}
}
