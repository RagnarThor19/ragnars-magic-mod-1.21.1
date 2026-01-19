package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.*;

public class BoomingSpell implements Spell {

    // --- Tunables ---
    private static final double CAST_RANGE = 120.0;
    private static final double SPEED = 0.90;
    private static final double TURN = 0.30;
    private static final int LIFE_TICKS = 85;
    private static final double HIT_RADIUS = 0.5;
    private static final float MAX_DAMAGE = 24.0f;
    private static final double BOOM_RADIUS = 5.0;

    private static final Map<RegistryKey<World>, List<BoomOrb>> ACTIVE = new HashMap<>();
    private static boolean TICK_REGISTERED = false;

    private static void ensureTickerRegistered() {
        if (TICK_REGISTERED) return;
        ServerTickEvents.END_WORLD_TICK.register(BoomingSpell::tickWorld);
        TICK_REGISTERED = true;
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        ensureTickerRegistered();

        // Heavy Charge Sound
        world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_WARDEN_SONIC_CHARGE,
                SoundCategory.PLAYERS, 1.0f, 1.5f);

        Vec3d eye = player.getCameraPosVec(0.0f);
        Vec3d fwd = player.getRotationVector().normalize();
        Vec3d start = eye.add(fwd.multiply(1.5));

        ServerWorld sw = (ServerWorld) world;
        ACTIVE.computeIfAbsent(sw.getRegistryKey(), k -> new ArrayList<>())
                .add(new BoomOrb(player.getUuid(), start, fwd.multiply(SPEED), fwd, sw.getTime()));

        return true;
    }

    private static void tickWorld(ServerWorld world) {
        List<BoomOrb> orbs = ACTIVE.get(world.getRegistryKey());
        if (orbs == null || orbs.isEmpty()) return;

        long now = world.getTime();
        Random rand = world.getRandom();

        Iterator<BoomOrb> it = orbs.iterator();
        while (it.hasNext()) {
            BoomOrb o = it.next();

            if (now - o.spawnTick > LIFE_TICKS) { it.remove(); continue; }

            PlayerEntity owner = world.getPlayerByUuid(o.owner);
            if (owner == null) { it.remove(); continue; }

            // Guidance Logic
            HitResult aim = owner.raycast(CAST_RANGE, 0.0f, false);
            Vec3d aimPos = (aim.getType() == HitResult.Type.BLOCK)
                    ? ((BlockHitResult)aim).getPos()
                    : owner.getCameraPosVec(0.0f).add(owner.getRotationVector().normalize().multiply(CAST_RANGE));

            Vec3d desiredVel = aimPos.subtract(o.pos).normalize().multiply(SPEED);
            Vec3d newVel = o.vel.multiply(1.0 - TURN).add(desiredVel.multiply(TURN));

            // Prevent backward movement
            if (newVel.dotProduct(o.initialForward) < 0) {
                newVel = o.initialForward.multiply(SPEED * 0.1);
            }
            o.vel = newVel;

            // Movement & Collision
            Vec3d oldPos = o.pos;
            Vec3d newPos = o.pos.add(o.vel);

            HitResult blockHit = world.raycast(new RaycastContext(
                    oldPos, newPos, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, owner));

            if (blockHit.getType() == HitResult.Type.BLOCK) {
                explode(world, blockHit.getPos(), owner);
                it.remove();
                continue;
            }

            List<Entity> targets = world.getOtherEntities(owner, new Box(oldPos, newPos).expand(HIT_RADIUS),
                    e -> e instanceof LivingEntity && !e.isSpectator());
            if (!targets.isEmpty()) {
                explode(world, targets.get(0).getPos(), owner);
                it.remove();
                continue;
            }

            o.pos = newPos;

            // --- SHINY/RARE VISUALS ---

            // 1. END_ROD: This is the "Shiny White" particle.
            // Spawning them with a tiny spread creates a condensed, glowing white ball.
            world.spawnParticles(ParticleTypes.END_ROD,
                    o.pos.x, o.pos.y, o.pos.z,
                    4, 0.05, 0.05, 0.05, 0.01);

            // 2. ENCHANTED_HIT: These are the little "magic stars" seen on crits.
            // Adds the "rare" vibe.
            world.spawnParticles(ParticleTypes.ENCHANTED_HIT,
                    o.pos.x, o.pos.y, o.pos.z,
                    2, 0.15, 0.15, 0.15, 0.0);

            // 3. ELECTRIC_SPARK: Occasional Zap to show power.
            if (rand.nextFloat() < 0.3f) {
                world.spawnParticles(ParticleTypes.ELECTRIC_SPARK,
                        o.pos.x, o.pos.y, o.pos.z,
                        1, 0.1, 0.1, 0.1, 0.0);
            }

            // Sound: Looping hum
            if ((now - o.spawnTick) % 4 == 0) {
                world.playSound(null, (int)o.pos.x, (int)o.pos.y, (int)o.pos.z,
                        SoundEvents.BLOCK_BEACON_AMBIENT, SoundCategory.PLAYERS, 2.0f, 2.0f);
            }
        }
    }

    private static void explode(ServerWorld world, Vec3d pos, PlayerEntity owner) {
        // Audio
        world.playSound(null, (int)pos.x, (int)pos.y, (int)pos.z,
                SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.PLAYERS, 4.0f, 1.0f);
        world.playSound(null, (int)pos.x, (int)pos.y, (int)pos.z,
                SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 1.0f, 0.5f);

        // Visuals: Sonic Boom + White Explosion
        world.spawnParticles(ParticleTypes.SONIC_BOOM, pos.x, pos.y, pos.z, 1, 0, 0, 0, 0);
        world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, pos.x, pos.y, pos.z, 1, 0, 0, 0, 0);
        world.spawnParticles(ParticleTypes.FLASH, pos.x, pos.y, pos.z, 3, 0, 0, 0, 0);

        // Logic: Damage Falloff (No block breaking)
        List<Entity> victims = world.getOtherEntities(owner,
                new Box(pos.subtract(BOOM_RADIUS, BOOM_RADIUS, BOOM_RADIUS), pos.add(BOOM_RADIUS, BOOM_RADIUS, BOOM_RADIUS)),
                e -> e instanceof LivingEntity && !e.isTeammate(owner));

        DamageSource source = world.getDamageSources().explosion(null, owner);

        for (Entity e : victims) {
            double dist = e.getPos().distanceTo(pos);
            if (dist > BOOM_RADIUS) continue;

            // Linear falloff: 100% damage at center, 0% at edge
            float factor = (float) (1.0 - (dist / BOOM_RADIUS));
            if (factor < 0) factor = 0;

            float toDeal = MAX_DAMAGE * factor;

            if (toDeal > 0.5f) {
                e.damage(source, toDeal);

                // Massive push away from center
                Vec3d push = e.getPos().subtract(pos).normalize().multiply(1.5 * factor);
                e.addVelocity(push.x, 0.4 + (0.4 * factor), push.z);
                e.velocityDirty = true;
            }
        }
    }

    private static class BoomOrb {
        final UUID owner;
        Vec3d pos;
        Vec3d vel;
        final Vec3d initialForward;
        final long spawnTick;
        BoomOrb(UUID owner, Vec3d pos, Vec3d vel, Vec3d initialForward, long spawnTick) {
            this.owner = owner; this.pos = pos; this.vel = vel;
            this.initialForward = initialForward; this.spawnTick = spawnTick;
        }
    }
}