package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public final class WindPushSpell implements Spell {

    // tuneables
    private static final double RANGE = 7.0;            // how far the cone reaches
    private static final double ANGLE_DEG = 55.0;        // cone half-angle
    private static final double BASE_PUSH = 2.6;         // base horizontal push
    private static final double EXTRA_PUSH = 3.2;        // added push near the player
    private static final double VERTICAL_BOOST = 1.00;   // little lift to sell "gust"

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return true;

        // sound: a gust bursting out, with a lighter whoosh on top
        world.playSound(null, player.getBlockPos(),
                SoundEvents.ENTITY_WIND_CHARGE_WIND_BURST.value(), SoundCategory.PLAYERS, 0.8f, 0.9f + world.random.nextFloat() * 0.15f);
        world.playSound(null, player.getBlockPos(),
                SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.5f, 1.3f);

        Vec3d origin = player.getPos().add(0, player.getStandingEyeHeight() * 0.6, 0);
        Vec3d dir = player.getRotationVec(1.0f).normalize();
        double cosLimit = Math.cos(Math.toRadians(ANGLE_DEG));

        // particles: wind streaks fanning out through the cone, and a gust swirl in front
        if (world instanceof ServerWorld sw) {
            Vec3d flat = new Vec3d(dir.x, 0, dir.z);
            Vec3d side = flat.lengthSquared() > 1.0e-4 ? new Vec3d(-flat.z, 0, flat.x).normalize() : new Vec3d(1, 0, 0);
            int streaks = 9;
            for (int i = 0; i < streaks; i++) {
                // Spread across the cone's width; each streak is a particle that flies outward along its own line
                double spread = (i / (double) (streaks - 1) - 0.5) * 2.0 * Math.tan(Math.toRadians(ANGLE_DEG)) * 0.6;
                Vec3d line = dir.add(side.multiply(spread)).normalize();
                Vec3d from = origin.add(line.multiply(0.8)).add(0, (sw.random.nextDouble() - 0.5) * 0.6, 0);
                sw.spawnParticles(ParticleTypes.CLOUD, from.x, from.y, from.z, 0, line.x, line.y, line.z, 0.9);
                if (i % 2 == 0) sw.spawnParticles(ParticleTypes.CLOUD, from.x, from.y, from.z, 0, line.x, line.y, line.z, 0.55);
            }
            Vec3d gust = origin.add(dir.multiply(2.0));
            sw.spawnParticles(ParticleTypes.GUST, gust.x, gust.y, gust.z, 1, 0, 0, 0, 0);
            Vec3d far = origin.add(dir.multiply(4.5));
            sw.spawnParticles(ParticleTypes.SMALL_GUST, far.x, far.y, far.z, 2, 0.4, 0.3, 0.4, 0);
        }

        // affect entities in front of the player
        Box box = player.getBoundingBox().expand(RANGE, 3.0, RANGE);
        for (Entity e : world.getOtherEntities(player, box, entity -> !entity.isSpectator() && entity.isAlive())) {
            Vec3d to = e.getPos().add(0, e.getStandingEyeHeight() * 0.3, 0).subtract(origin);
            double dist = to.length();
            if (dist > RANGE || dist < 0.001) continue;

            Vec3d ndir = to.normalize();
            double dot = ndir.dotProduct(dir);
            if (dot < cosLimit) continue; // outside cone

            double falloff = 1.0 - MathHelper.clamp(dist / RANGE, 0.0, 1.0); // 1 near, 0 far
            double push = BASE_PUSH + EXTRA_PUSH * falloff;                  // stronger up close

            // entity-specific handling
            if (e instanceof LivingEntity target) {
                // horizontal shove + a bit of lift
                Vec3d kick = new Vec3d(dir.x * push, VERTICAL_BOOST * (0.6 + 0.4 * falloff), dir.z * push);
                target.addVelocity(kick);
                target.velocityModified = true;
                target.velocityDirty = true;
                target.fallDistance = 0; // feel nicer
            } else if (e instanceof ProjectileEntity proj) {
                // flip / slow projectiles
                Vec3d v = proj.getVelocity();
                proj.setVelocity(v.multiply(0.25).add(dir.multiply(push * 0.7)));
                proj.velocityDirty = true;
            } else {
                // generic entities (boats, minecarts, etc.)
                e.addVelocity(dir.x * push, 0.1 + VERTICAL_BOOST * 0.25, dir.z * push);
                e.velocityDirty = true;
            }
        }

        return true;
    }
}
