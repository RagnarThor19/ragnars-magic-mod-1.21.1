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

        // sound: fast whoosh + flap
        world.playSound(null, player.getBlockPos(),
                SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.9f, 1.2f);
        world.playSound(null, player.getBlockPos(),
                SoundEvents.ENTITY_ENDER_DRAGON_FLAP, SoundCategory.PLAYERS, 0.6f, 1.35f);

        Vec3d origin = player.getPos().add(0, player.getStandingEyeHeight() * 0.6, 0);
        Vec3d dir = player.getRotationVec(1.0f).normalize();
        double cosLimit = Math.cos(Math.toRadians(ANGLE_DEG));

        // particles along the cone
        if (world instanceof ServerWorld sw) {
            int steps = 10;
            for (int i = 1; i <= steps; i++) {
                double t = (i / (double) steps) * RANGE;
                Vec3d p = origin.add(dir.multiply(t));
                sw.spawnParticles(ParticleTypes.CLOUD, p.x, p.y, p.z, 7, 0.35, 0.22, 0.35, 0.05);
                if (i % 2 == 0) {
                    sw.spawnParticles(ParticleTypes.POOF, p.x, p.y, p.z, 4, 0.25, 0.15, 0.25, 0.02);
                }
            }
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
