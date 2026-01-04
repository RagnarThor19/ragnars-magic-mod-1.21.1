package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ItemStackParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.*;

public class IceBeamSpell implements Spell {

    // timings
    private static final int CHARGE_TIME = 20;   // 1.0s
    private static final int FIRE_TIME   = 60;   // 3.0s

    // beam feel
    private static final double START_OFFSET = 3.0; // spawn in front of eyes
    private static final double MAX_RANGE    = 24.0;
    private static final double THICKNESS    = 0.20;  // collision radius
    private static final float  DPT          = 12.0f;  // damage per tick on hit

    // debuffs
    private static final StatusEffectInstance SLOW   =
            new StatusEffectInstance(StatusEffects.SLOWNESS, 10, 3, false, false, true); // Slowness IV (short, reapplied)
    private static final StatusEffectInstance FATIGUE =
            new StatusEffectInstance(StatusEffects.MINING_FATIGUE, 20, 1, false, false, true);

    private static final Map<RegistryKey<World>, List<Beam>> ACTIVE = new HashMap<>();
    private static boolean TICK_REGISTERED = false;

    private static void ensureTicker() {
        if (TICK_REGISTERED) return;
        ServerTickEvents.END_WORLD_TICK.register(IceBeamSpell::tickWorld);
        TICK_REGISTERED = true;
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        ensureTicker();

        // Cast / charge SFX
        world.playSound(null, player.getBlockPos(),
                SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 1.0f, 1.4f);
        world.playSound(null, player.getBlockPos(),
                SoundEvents.BLOCK_BEACON_POWER_SELECT, SoundCategory.PLAYERS, 0.8f, 1.8f);

        ServerWorld sw = (ServerWorld) world;
        ACTIVE.computeIfAbsent(sw.getRegistryKey(), k -> new ArrayList<>())
                .add(new Beam(player.getUuid(), sw.getTime()));

        return true;
    }

    private static void tickWorld(ServerWorld world) {
        List<Beam> list = ACTIVE.get(world.getRegistryKey());
        if (list == null || list.isEmpty()) return;

        long now = world.getTime();
        Iterator<Beam> it = list.iterator();

        while (it.hasNext()) {
            Beam b = it.next();
            PlayerEntity owner = world.getPlayerByUuid(b.owner);
            if (owner == null) { it.remove(); continue; }

            int age = (int) (now - b.startTick);
            if (age >= CHARGE_TIME + FIRE_TIME) { it.remove(); continue; }

            // Compute origin/dir every tick so it tracks aim.
            Vec3d eye = owner.getCameraPosVec(0.0f);
            Vec3d dir = owner.getRotationVector().normalize();
            Vec3d origin = eye.add(dir.multiply(START_OFFSET));

            // Charge visuals UNDER PLAYER (ring + rising ice), not at cursor
            if (age < CHARGE_TIME) {
                spawnChargeCircle(world, owner, age);
                if (age % 6 == 0) {
                    world.playSound(null, owner.getBlockPos(),
                            SoundEvents.BLOCK_AMETHYST_BLOCK_STEP, SoundCategory.PLAYERS, 0.5f, 1.9f);
                }
                continue;
            }

            // Beam active
            if ((age - CHARGE_TIME) == 0) {
                world.playSound(
                        null,
                        BlockPos.ofFloored(origin),
                        net.ragnar.ragnarsmagicmod.sound.ModSoundEvents.ICE_BEAM,
                        SoundCategory.PLAYERS,
                        2.0f,
                        1.0f
                );
            }

            // Trace blocks
            HitResult blockHit = world.raycast(new RaycastContext(
                    origin,
                    origin.add(dir.multiply(MAX_RANGE)),
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE,
                    owner
            ));

            Vec3d end;
            if (blockHit.getType() == HitResult.Type.BLOCK) {
                end = ((BlockHitResult) blockHit).getPos();
            } else {
                end = origin.add(dir.multiply(MAX_RANGE));
            }

            // Render beam (thin, dense, icy)
            renderBeam(world, origin, end);

            // Impact particles + sound at end
            spawnImpact(world, end);

            // Entity hits: sweep capsule along segment
            hitEntities(world, owner, origin, end);

            // subtle loop sound
            if (world.random.nextInt(6) == 0) {
                world.playSound(null, BlockPos.ofFloored(end),
                        SoundEvents.BLOCK_GLASS_HIT, SoundCategory.PLAYERS, 0.4f, 1.9f);
            }
        }
    }

    // === NEW CHARGE VISUALS: ice ring under player + rising shards ===
    private static void spawnChargeCircle(ServerWorld w, PlayerEntity owner, int age) {
        final double progress = MathHelper.clamp(age / (double) CHARGE_TIME, 0.0, 1.0);
        final double baseY = owner.getY() + 0.05; // feet level
        final Vec3d center = owner.getPos().add(0, 0.0, 0);

        // ring slowly grows & rises a tiny bit
        double radius = 1.2 + 0.6 * progress;
        double ringY  = baseY + 0.05 + 0.05 * progress;

        // crisp circle of packed ice chips + snowflakes
        int ringPts = 48;
        for (int i = 0; i < ringPts; i++) {
            double a = (MathHelper.TAU * i) / ringPts;
            double x = center.x + Math.cos(a) * radius;
            double z = center.z + Math.sin(a) * radius;

            // tight chips
            w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.PACKED_ICE.getDefaultState()),
                    x, ringY, z, 1, 0.0, 0.0, 0.0, 0.0);

            // faint snow shimmer drifting up
            w.spawnParticles(ParticleTypes.SNOWFLAKE,
                    x, ringY, z, 1, 0.0, 0.02, 0.0, 0.0);
        }

        // rising ice shards inside the circle
        net.minecraft.util.math.random.Random rand = w.getRandom();
        int columns = 6;
        for (int c = 0; c < columns; c++) {
            double theta = rand.nextDouble() * Math.PI * 2.0;
            double r = radius * (0.3 + 0.6 * rand.nextDouble()); // inner to mid ring
            double cx = center.x + Math.cos(theta) * r;
            double cz = center.z + Math.sin(theta) * r;

            int steps = 6;
            for (int h = 0; h < steps; h++) {
                double y = baseY + h * 0.18 + progress * 0.8; // rises as it charges
                // icy chunk
                w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.ICE.getDefaultState()),
                        cx, y, cz, 1, 0.02, 0.02, 0.02, 0.0);
                // cold mist
                if (h % 2 == 0) {
                    w.spawnParticles(ParticleTypes.SNOWFLAKE,
                            cx, y + 0.02, cz, 1, 0.0, 0.01, 0.0, 0.0);
                }
            }
        }

        // soft frosty whoosh each quarter of the charge
        if (age == 5 || age == 10 || age == 15) {
            w.playSound(null, owner.getBlockPos(),
                    SoundEvents.BLOCK_SNOW_PLACE, SoundCategory.PLAYERS, 0.7f, 1.6f);
        }
    }

    private static void renderBeam(ServerWorld w, Vec3d a, Vec3d b) {
        Vec3d ab = b.subtract(a);
        double len = ab.length();
        if (len < 1e-4) return;
        Vec3d step = ab.normalize().multiply(0.25); // particle spacing
        int points = (int) Math.ceil(len / 0.25);
        Vec3d p = a;

        for (int i = 0; i < points; i++) {
            // tight core
            w.spawnParticles(ParticleTypes.SNOWFLAKE, p.x, p.y, p.z, 2, 0, 0, 0, 0.0);
            // shimmering outer
            w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.PACKED_ICE.getDefaultState()),
                    p.x, p.y, p.z, 1, 0.02, 0.02, 0.02, 0.0);
            p = p.add(step);
        }
    }

    private static void spawnImpact(ServerWorld w, Vec3d pos) {
        // dense icy burst + subtle snowball crumbs
        w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.ICE.getDefaultState()),
                pos.x, pos.y, pos.z, 18, 0.15, 0.15, 0.15, 0.0);
        w.spawnParticles(ParticleTypes.SNOWFLAKE, pos.x, pos.y, pos.z, 10, 0.1, 0.1, 0.1, 0.0);
        w.spawnParticles(new ItemStackParticleEffect(ParticleTypes.ITEM, new ItemStack(net.minecraft.item.Items.SNOWBALL)),
                pos.x, pos.y, pos.z, 3, 0.05, 0.05, 0.05, 0.0);

        w.playSound(null, BlockPos.ofFloored(pos), SoundEvents.BLOCK_GLASS_PLACE, SoundCategory.PLAYERS, 0.6f, 1.9f);
    }

    private static void hitEntities(ServerWorld world, PlayerEntity owner, Vec3d a, Vec3d b) {
        // Build sweep AABB
        Box box = new Box(a, b).expand(THICKNESS, THICKNESS, THICKNESS);

        List<Entity> candidates = world.getOtherEntities(owner, box,
                e -> e instanceof LivingEntity && e.isAlive() && !e.isTeammate(owner));

        for (Entity e : candidates) {
            LivingEntity le = (LivingEntity) e;

            // Distance from beam line
            double dist = distancePointToSegment(le.getBoundingBox().getCenter(), a, b);
            double entityRadius = Math.max(le.getWidth(), le.getHeight()) * 0.35;
            if (dist <= (THICKNESS + entityRadius)) {
                // damage + chill
                le.damage(world.getDamageSources().playerAttack(owner), DPT);
                le.addStatusEffect(new StatusEffectInstance(SLOW));
                le.addStatusEffect(new StatusEffectInstance(FATIGUE));
                // little freeze-y sound sometimes
                if (world.random.nextInt(8) == 0) {
                    world.playSound(null, le.getBlockPos(), SoundEvents.BLOCK_GLASS_STEP, SoundCategory.PLAYERS, 0.5f, 2.0f);
                }
            }
        }
    }

    // helpers
    private static double distancePointToSegment(Vec3d p, Vec3d a, Vec3d b) {
        Vec3d ab = b.subtract(a);
        double ab2 = ab.lengthSquared();
        if (ab2 <= 1e-9) return p.distanceTo(a);
        double t = p.subtract(a).dotProduct(ab) / ab2;
        t = MathHelper.clamp(t, 0.0, 1.0);
        Vec3d q = a.lerp(b, t);
        return p.distanceTo(q);
    }

    private static class Beam {
        final UUID owner;
        final long startTick;
        Beam(UUID owner, long startTick) { this.owner = owner; this.startTick = startTick; }
    }
}
