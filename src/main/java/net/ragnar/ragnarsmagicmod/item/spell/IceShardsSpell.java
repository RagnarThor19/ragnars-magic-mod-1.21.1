package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.entity.IceShardEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tome of Ice Shards: three fast shards fired in a quick rat-a-tat, each from alternating sides of the staff and
 * aimed at the crosshair, though each strays a little further off it than the last (bloom). Every shard does its own damage (they don't get swallowed by the target's hurt
 * cooldown) and chills a little harder; land all three on the same target and it freezes up and takes a shattering
 * bonus hit.
 */
public final class IceShardsSpell implements Spell {
    private static final int SHARDS = 3;
    private static final int SHARD_GAP_TICKS = 2;
    private static final double SPEED = 2.2;
    private static final double AIM_RANGE = 48.0;
    // Bloom: each shard strays up to this many degrees off the crosshair, and it gets worse through the volley
    private static final double[] BLOOM_DEGREES = {2.5, 4.0, 5.5};

    public static final float SHARD_DAMAGE = 3.0f;   // 1.5 hearts each
    private static final float SHATTER_DAMAGE = 2.0f; // bonus when all three land: 11 damage in total
    private static final int COMBO_WINDOW_TICKS = 20;

    private static final BlockStateParticleEffect ICE_CHIPS =
            new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.ICE.getDefaultState());

    private static final class Volley {
        final ServerWorld world;
        final UUID caster;
        final int id;
        int fired;
        int wait;

        Volley(ServerWorld world, UUID caster, int id) {
            this.world = world;
            this.caster = caster;
            this.id = id;
        }
    }

    /** How many shards of one volley have hit one target. */
    private record Combo(int volley, int hits, long lastHit) {}

    private static final List<Volley> VOLLEYS = new ArrayList<>();
    private static final Map<UUID, Combo> COMBOS = new HashMap<>();
    private static int nextVolley = 1;
    private static boolean registered = false;

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(IceShardsSpell::tick);
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw)) return false;
        ensureRegistered();
        Volley v = new Volley(sw, player.getUuid(), nextVolley++);
        VOLLEYS.add(v);
        fireNext(v, player); // the first one goes right away
        return true;
    }

    private static void tick(ServerWorld world) {
        if (VOLLEYS.isEmpty()) return;
        Iterator<Volley> it = VOLLEYS.iterator();
        while (it.hasNext()) {
            Volley v = it.next();
            if (v.world != world) continue;
            PlayerEntity player = world.getPlayerByUuid(v.caster);
            if (player == null || !player.isAlive() || v.fired >= SHARDS) {
                it.remove();
                continue;
            }
            if (--v.wait <= 0) fireNext(v, player);
        }
    }

    /** One shard, from alternating sides of the staff, aimed at whatever is under the crosshair right now. */
    private static void fireNext(Volley v, PlayerEntity player) {
        ServerWorld world = v.world;
        Vec3d eye = player.getEyePos();
        Vec3d look = player.getRotationVector();
        HitResult hit = world.raycast(new RaycastContext(eye, eye.add(look.multiply(AIM_RANGE)),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
        Vec3d target = hit.getType() == HitResult.Type.MISS ? eye.add(look.multiply(AIM_RANGE)) : hit.getPos();

        Vec3d right = look.crossProduct(new Vec3d(0, 1, 0));
        right = right.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : right.normalize();
        double side = switch (v.fired) { case 0 -> 0.0; case 1 -> -0.35; default -> 0.35; };
        Vec3d start = eye.add(0, -0.15, 0).add(look.multiply(0.4)).add(right.multiply(side));

        IceShardEntity shard = new IceShardEntity(world, player);
        shard.setVolley(v.id);
        shard.setPosition(start.x, start.y, start.z);
        Vec3d aim = bloom(target.subtract(start).normalize(), BLOOM_DEGREES[Math.min(v.fired, BLOOM_DEGREES.length - 1)], world);
        shard.setVelocity(aim.multiply(SPEED));
        world.spawnEntity(shard);

        // Each shot a step higher: ting, ting, TING
        float pitch = 1.3f + 0.2f * v.fired;
        world.playSound(null, start.x, start.y, start.z, SoundEvents.BLOCK_AMETHYST_BLOCK_HIT, SoundCategory.PLAYERS, 1.0f, pitch);
        world.playSound(null, start.x, start.y, start.z, SoundEvents.ENTITY_SNOWBALL_THROW, SoundCategory.PLAYERS, 0.6f, pitch);
        world.spawnParticles(ParticleTypes.SNOWFLAKE, start.x, start.y, start.z, 3, 0.05, 0.05, 0.05, 0.02);

        v.fired++;
        v.wait = SHARD_GAP_TICKS;
    }

    /** Tilts {@code dir} a random amount (up to {@code maxDegrees}) in a random direction, evenly over the cone. */
    private static Vec3d bloom(Vec3d dir, double maxDegrees, ServerWorld world) {
        Vec3d n1 = dir.crossProduct(new Vec3d(0, 1, 0));
        n1 = n1.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : n1.normalize();
        Vec3d n2 = dir.crossProduct(n1).normalize();
        double spin = world.random.nextDouble() * Math.PI * 2;
        double tilt = Math.toRadians(maxDegrees) * Math.sqrt(world.random.nextDouble());
        Vec3d off = n1.multiply(Math.cos(spin)).add(n2.multiply(Math.sin(spin)));
        return dir.multiply(Math.cos(tilt)).add(off.multiply(Math.sin(tilt))).normalize();
    }

    /**
     * A shard hit {@code target}: returns how many shards of that volley have now hit it. The third one freezes it
     * with a shattering bonus hit.
     */
    public static int onShardHit(ServerWorld world, IceShardEntity shard, LivingEntity target, LivingEntity owner) {
        long now = world.getTime();
        Combo c = COMBOS.get(target.getUuid());
        int hits = c != null && c.volley() == shard.getVolley() && now - c.lastHit() <= COMBO_WINDOW_TICKS ? c.hits() + 1 : 1;
        COMBOS.put(target.getUuid(), new Combo(shard.getVolley(), hits, now));
        if (COMBOS.size() > 256) COMBOS.values().removeIf(x -> now - x.lastHit() > COMBO_WINDOW_TICKS);

        // Chill builds up with every shard
        target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 30, hits - 1, false, true));
        target.setFrozenTicks(Math.max(target.getFrozenTicks(), 60 + 40 * hits));

        Vec3d c0 = target.getBoundingBox().getCenter();
        if (hits < SHARDS) {
            world.playSound(null, c0.x, c0.y, c0.z, SoundEvents.BLOCK_GLASS_HIT, SoundCategory.PLAYERS, 1.0f, 1.2f + 0.3f * hits);
            world.spawnParticles(ICE_CHIPS, c0.x, c0.y, c0.z, 10, 0.2, 0.3, 0.2, 0.08);
            return hits;
        }

        // All three landed: it freezes up and the ice shatters off it
        COMBOS.remove(target.getUuid());
        target.timeUntilRegen = 0;
        target.damage(shard.getDamageSources().thrown(shard, owner), SHATTER_DAMAGE);
        target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 40, 3, false, true));
        target.setFrozenTicks(Math.max(target.getFrozenTicks(), target.getMinFreezeDamageTicks() + 40));

        world.playSound(null, c0.x, c0.y, c0.z, SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 1.2f, 1.0f);
        world.playSound(null, c0.x, c0.y, c0.z, SoundEvents.BLOCK_AMETHYST_CLUSTER_BREAK, SoundCategory.PLAYERS, 1.0f, 1.2f);
        world.playSound(null, c0.x, c0.y, c0.z, SoundEvents.ENTITY_PLAYER_HURT_FREEZE, SoundCategory.PLAYERS, 0.8f, 1.3f);
        world.spawnParticles(ICE_CHIPS, c0.x, c0.y, c0.z, 45, target.getWidth() * 0.5, target.getHeight() * 0.4, target.getWidth() * 0.5, 0.2);
        world.spawnParticles(ParticleTypes.SNOWFLAKE, c0.x, c0.y, c0.z, 20, target.getWidth() * 0.5, target.getHeight() * 0.4, target.getWidth() * 0.5, 0.08);
        return hits;
    }
}
