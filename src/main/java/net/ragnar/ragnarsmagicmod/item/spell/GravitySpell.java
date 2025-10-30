package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.World;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;

import java.util.*;

public class GravitySpell implements Spell {

    private static final double RANGE = 64.0;
    private static final double RADIUS = 3.0;
    private static final int DURATION_TICKS = 100;   // 5s
    private static final int APPLY_EVERY = 5;        // re-apply every 0.25s
    private static final int LEV_DURATION = 15;      // short refresh window
    private static final int LEV_AMP = 20;            // Levitation III

    private static final Map<RegistryKey<World>, List<Field>> ACTIVE = new HashMap<>();
    private static boolean TICK_REGISTERED = false;

    private static void ensureTicker() {
        if (TICK_REGISTERED) return;
        ServerTickEvents.END_WORLD_TICK.register(GravitySpell::tickWorld);
        TICK_REGISTERED = true;
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        ensureTicker();

        // exact look point (no surfacing). If miss, use 8 blocks ahead.
        Vec3d center = pickTargetPoint(world, player);

        // SFX
        var horn = SoundEvents.GOAT_HORN_SOUNDS.get(2).value();
        world.playSound(
                null,
                player.getBlockPos(),
                horn,
                SoundCategory.PLAYERS,
                0.75f,
                1.4f
        );
        world.playSound(null, BlockPos.ofFloored(center),
                SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 0.9f, 1.2f);

        ServerWorld sw = (ServerWorld) world;
        ACTIVE.computeIfAbsent(sw.getRegistryKey(), k -> new ArrayList<>())
                .add(new Field(center, sw.getTime()));
        return true;
    }

    private static Vec3d pickTargetPoint(World world, PlayerEntity player) {
        HitResult hit = player.raycast(RANGE, 0.0f, false);
        if (hit.getType() == HitResult.Type.BLOCK) {
            // exact hit position on the block face
            return ((BlockHitResult) hit).getPos();
        } else {
            // 8 blocks straight ahead
            return player.getCameraPosVec(0).add(player.getRotationVector().normalize().multiply(8.0));
        }
    }

    private static void tickWorld(ServerWorld world) {
        List<Field> fields = ACTIVE.get(world.getRegistryKey());
        if (fields == null || fields.isEmpty()) return;

        long now = world.getTime();
        Iterator<Field> it = fields.iterator();
        while (it.hasNext()) {
            Field f = it.next();

            int age = (int) (now - f.spawnTick);
            if (age >= DURATION_TICKS) {
                world.playSound(null, BlockPos.ofFloored(f.center),
                        SoundEvents.BLOCK_BEACON_DEACTIVATE, SoundCategory.PLAYERS, 0.9f, 1.0f);
                it.remove();
                continue;
            }

            // visuals at exact height, then slowly rising
            renderParticles(world, f, age);

            // affect ALL living entities (players included, caster included if inside)
            if (age % APPLY_EVERY == 0) {
                double r = RADIUS;
                Box aabb = new Box(
                        f.center.x - r, f.center.y - 0.5, f.center.z - r,
                        f.center.x + r, f.center.y + 3.0, f.center.z + r
                );
                List<Entity> ents = world.getOtherEntities(null, aabb,
                        e -> e instanceof LivingEntity && e.isAlive());

                for (Entity e : ents) {
                    LivingEntity le = (LivingEntity) e;
                    le.addStatusEffect(new StatusEffectInstance(
                            StatusEffects.LEVITATION, LEV_DURATION, LEV_AMP, true, true, true));
                }
            }
        }
    }

    private static void renderParticles(ServerWorld world, Field f, int age) {
        net.minecraft.util.math.random.Random rand = world.getRandom();

        // boundary ring exactly at look Y, rising slowly
        double ringY = f.center.y + age * 0.02; // gentle rise
        int ringPts = 36;
        for (int i = 0; i < ringPts; i++) {
            double a = (MathHelper.TAU * i) / ringPts;
            double x = f.center.x + Math.cos(a) * RADIUS;
            double z = f.center.z + Math.sin(a) * RADIUS;
            world.spawnParticles(ParticleTypes.END_ROD, x, ringY, z, 1, 0, 0, 0, 0.0);
        }

        // interior wisps start at exact center height
        for (int i = 0; i < 20; i++) {
            double rx = (rand.nextDouble() * 2 - 1) * (RADIUS * 0.9);
            double rz = (rand.nextDouble() * 2 - 1) * (RADIUS * 0.9);
            double baseY = f.center.y + (rand.nextDouble() * 0.2 - 0.1);
            for (int h = 0; h < 6; h++) {
                world.spawnParticles(
                        ParticleTypes.ENCHANT,
                        f.center.x + rx * 0.85,
                        baseY + h * 0.2 + age * 0.01,
                        f.center.z + rz * 0.85,
                        1, 0, 0, 0, 0.0
                );
            }
        }

        if (age % 20 == 0) {
            world.playSound(null, BlockPos.ofFloored(f.center),
                    SoundEvents.BLOCK_ENCHANTMENT_TABLE_USE, SoundCategory.PLAYERS, 0.6f, 1.4f);
        }
    }

    private static class Field {
        final Vec3d center;
        final long spawnTick;
        Field(Vec3d center, long spawnTick) {
            this.center = center;
            this.spawnTick = spawnTick;
        }
    }
}
