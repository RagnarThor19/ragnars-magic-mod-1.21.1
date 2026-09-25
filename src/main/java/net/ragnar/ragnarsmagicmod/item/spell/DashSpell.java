package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public final class DashSpell implements Spell {

    // Feel knobs
    private static final double SPEED = 1.55;       // initial horizontal speed
    private static final double LIFT  = 0.10;       // tiny lift so you don't scrape
    private static final int    DURATION_T = 10;    // ticks we keep hitting/particles (~0.5s)
    private static final double HIT_RADIUS = 1.1;   // around player while dashing
    private static final float  DAMAGE = 4.0f;      // 2 hearts
    private static final double KNOCK = 0.9;        // horizontal knockback
    private static final double KNOCK_UP = 0.20;    // vertical bump

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return true;

        // SFX: a sharp burst of wind + whoosh
        world.playSound(null, player.getBlockPos(),
                SoundEvents.ENTITY_BREEZE_JUMP, SoundCategory.PLAYERS, 0.8f, 1.3f + world.random.nextFloat() * 0.15f);
        world.playSound(null, player.getBlockPos(),
                SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.5f, 1.5f);

        // Set forward velocity (dash)
        Vec3d dir = player.getRotationVec(1.0f).normalize();

        // A puff of wind kicked out behind you as you launch
        if (world instanceof ServerWorld sw) {
            Vec3d back = player.getPos().add(0, 0.6, 0).subtract(new Vec3d(dir.x, 0, dir.z).multiply(0.6));
            sw.spawnParticles(net.minecraft.particle.ParticleTypes.SMALL_GUST, back.x, back.y, back.z, 1, 0, 0, 0, 0);
        }
        Vec3d vel = new Vec3d(dir.x * SPEED, player.getVelocity().y + LIFT, dir.z * SPEED);
        player.setVelocity(vel);
        player.velocityModified = true;
        player.velocityDirty = true;

        // Tiny resistance so you don't get deleted on first frame of contact
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 8, 0, false, false));
        player.fallDistance = 0.0f;

        if (world instanceof ServerWorld sw) {
            net.ragnar.ragnarsmagicmod.util.DashRunner.start(
                    sw, player, dir, DURATION_T, HIT_RADIUS, DAMAGE, KNOCK, KNOCK_UP
            );
        }


        return true;
    }
}
