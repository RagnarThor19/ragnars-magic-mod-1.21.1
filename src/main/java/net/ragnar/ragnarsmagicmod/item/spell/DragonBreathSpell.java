package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.AreaEffectCloudEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.*;

public class DragonBreathSpell implements Spell {

    // --- tuning ---
    private static final int BREATH_TIME = 45;        // ticks the breath emitter runs (~2.25s)
    private static final double MAX_RANGE = 16.0;     // how far the cone extends
    private static final double START_RADIUS = 1.2;   // cone radius near mouth
    private static final double END_RADIUS = 3.5;     // cone radius at max range
    private static final int SAMPLES_ALONG = 12;      // points along the ray per tick
    private static final double DPT = 3.5;            // damage per tick if inside breath
    private static final int CLOUD_EVERY = 3;         // place lingering clouds every N ticks
    private static final float CLOUD_BASE_RADIUS = 1.2f;
    private static final int CLOUD_DURATION = 40;     // short clouds (~2s); we spawn many
    private static final double KNOCKBACK = 0.03;     // tiny push from the breath

    // visuals
    private static final int PARTICLE_PUFFS_PER_SAMPLE = 10;
    private static final double NOISE = 0.35;

    // safety: minimal distance from player (meters/blocks)
    private static final double MIN_START_OFFSET = 3.0;

    private static final Map<RegistryKey<World>, List<Breath>> ACTIVE = new HashMap<>();
    private static boolean TICK_REGISTERED = false;

    private static void ensureTicker() {
        if (TICK_REGISTERED) return;
        ServerTickEvents.END_WORLD_TICK.register(DragonBreathSpell::tickWorld);
        TICK_REGISTERED = true;
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        ensureTicker();

        // Big dragon vibe
        world.playSound(null, player.getBlockPos(),
                SoundEvents.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.PLAYERS, 1.2f, 1.0f);
        world.playSound(null, player.getBlockPos(),
                SoundEvents.BLOCK_BREWING_STAND_BREW, SoundCategory.PLAYERS, 1.0f, 0.6f);

        ServerWorld sw = (ServerWorld) world;
        ACTIVE.computeIfAbsent(sw.getRegistryKey(), k -> new ArrayList<>())
                .add(new Breath(player.getUuid(), sw.getTime()));

        return true;
    }

    private static void tickWorld(ServerWorld world) {
        List<Breath> list = ACTIVE.get(world.getRegistryKey());
        if (list == null || list.isEmpty()) return;

        long now = world.getTime();
        Iterator<Breath> it = list.iterator();

        while (it.hasNext()) {
            Breath b = it.next();
            PlayerEntity owner = world.getPlayerByUuid(b.owner);
            if (owner == null || now - b.startTick > BREATH_TIME) {
                it.remove();
                continue;
            }

            // Recompute origin/dir every tick so it follows aim,
            // and force the origin to be 3 blocks in front of the eyes.
            Vec3d eye = owner.getCameraPosVec(0.0f);
            Vec3d dir = owner.getRotationVector().normalize();
            Vec3d start = eye.add(dir.multiply(MIN_START_OFFSET));

            // figure out which sample index is the first >= MIN_START_OFFSET
            double step = MAX_RANGE / (SAMPLES_ALONG - 1);
            int startIdx = (int) Math.ceil(MIN_START_OFFSET / step);
            if (startIdx < 0) startIdx = 0;
            if (startIdx >= SAMPLES_ALONG) startIdx = SAMPLES_ALONG - 1;

            for (int i = startIdx; i < SAMPLES_ALONG; i++) {
                double t = ((double) i) / (SAMPLES_ALONG - 1);
                double distFromStart = (i - startIdx) * step; // distance FROM our safe-start
                Vec3d p = start.add(dir.multiply(distFromStart));

                double radius = MathHelper.lerp(t, START_RADIUS, END_RADIUS);

                // Particles
                for (int k = 0; k < PARTICLE_PUFFS_PER_SAMPLE; k++) {
                    double ox = (world.random.nextDouble() * 2 - 1) * NOISE * radius;
                    double oy = (world.random.nextDouble() * 2 - 1) * NOISE * 0.6 * radius;
                    double oz = (world.random.nextDouble() * 2 - 1) * NOISE * radius;
                    world.spawnParticles(ParticleTypes.DRAGON_BREATH, p.x + ox, p.y + oy, p.z + oz, 1, 0, 0, 0, 0.0);
                }

                // Damage entities in this slice (excludes owner via getOtherEntities)
                Box aabb = new Box(p.x - radius, p.y - radius * 0.7, p.z - radius,
                        p.x + radius, p.y + radius * 0.7, p.z + radius);
                List<Entity> hits = world.getOtherEntities(owner, aabb,
                        e -> e instanceof LivingEntity && e.isAlive() && !e.isTeammate(owner));

                for (Entity e : hits) {
                    LivingEntity le = (LivingEntity) e;
                    Vec3d pushDir = le.getPos().subtract(p).normalize();
                    if (!Double.isFinite(pushDir.lengthSquared()) || pushDir.lengthSquared() < 1.0e-6) pushDir = dir;
                    le.addVelocity(pushDir.x * KNOCKBACK, 0.01, pushDir.z * KNOCKBACK);
                    le.velocityDirty = true;

                    le.damage(world.getDamageSources().playerAttack(owner), (float) DPT);
                }

                // Clouds: spawn from these safe points (≥ 3 blocks out)
                if ((now - b.startTick) % CLOUD_EVERY == 0 && i % 3 == 0) {
                    AreaEffectCloudEntity cloud = new AreaEffectCloudEntity(world, p.x, p.y, p.z);
                    cloud.setOwner(owner);
                    cloud.setParticleType(ParticleTypes.DRAGON_BREATH);
                    float base = CLOUD_BASE_RADIUS + (float) (radius * 0.15);
                    cloud.setRadius(base);
                    cloud.setRadiusOnUse(-0.02f);
                    cloud.setRadiusGrowth(-0.01f);
                    cloud.setWaitTime(0);
                    cloud.setDuration(CLOUD_DURATION);
                    // Effects: Instant Damage II + Wither so undead aren't healed
                    cloud.addEffect(new StatusEffectInstance(StatusEffects.INSTANT_DAMAGE, 1, 1));
                    cloud.addEffect(new StatusEffectInstance(StatusEffects.WITHER, 40, 0));
                    world.spawnEntity(cloud);
                }
            }

            // looped breath sound at safe origin
            if (world.random.nextInt(4) == 0) {
                world.playSound(null, BlockPos.ofFloored(start),
                        SoundEvents.BLOCK_END_PORTAL_SPAWN, SoundCategory.PLAYERS, 0.6f, 1.8f);
            }
        }
    }

    private static class Breath {
        final UUID owner;
        final long startTick;
        Breath(UUID owner, long startTick) {
            this.owner = owner;
            this.startTick = startTick;
        }
    }
}
