package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.util.Aim;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Makes the creature you're looking at drift slowly up into the air for 15 seconds.
 * Look straight down to levitate yourself instead; you also get Slow Falling so the way down is safe.
 */
public class LevitationSpell implements Spell {
    private static final double RANGE = 32.0;
    private static final double AIM_CONE = 6.0;
    private static final int DURATION = 20 * 15;
    private static final float SELF_PITCH = 75.0f;           // looking this far down targets yourself
    private static final int SELF_SAFE_LANDING = 20 * 10;    // Slow Falling that outlasts the levitation

    private static final DustParticleEffect GLOW = new DustParticleEffect(new Vector3f(0.85f, 0.95f, 1.0f), 0.9f);

    private record Floating(ServerWorld world, LivingEntity entity, long until) {}

    private static final List<Floating> FLOATING = new ArrayList<>();
    private static boolean registered = false;

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            Iterator<Floating> it = FLOATING.iterator();
            while (it.hasNext()) {
                Floating f = it.next();
                if (f.world() != world) continue;
                LivingEntity e = f.entity();
                if (!e.isAlive() || world.getTime() >= f.until() || !e.hasStatusEffect(StatusEffects.LEVITATION)) {
                    it.remove();
                    continue;
                }
                // Soft motes drifting down from under its feet
                if (world.getTime() % 3 == 0) {
                    world.spawnParticles(GLOW, e.getX(), e.getY() - 0.1, e.getZ(), 2, e.getWidth() * 0.4, 0.05, e.getWidth() * 0.4, 0);
                }
                if (world.getTime() % 8 == 0) {
                    world.spawnParticles(ParticleTypes.END_ROD, e.getX(), e.getY(), e.getZ(), 0, 0, -0.04, 0, 1.0);
                }
            }
        });
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        ensureRegistered();
        ServerWorld sw = (ServerWorld) world;

        LivingEntity target;
        if (player.getPitch() >= SELF_PITCH) {
            target = player;
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, DURATION + SELF_SAFE_LANDING, 0, false, false, true));
        } else {
            Entity aimed = Aim.target(sw, player, RANGE, AIM_CONE, e -> e instanceof LivingEntity);
            if (aimed == null) {
                player.sendMessage(Text.literal("Look at a creature, or straight down to lift yourself."), true);
                return false;
            }
            target = (LivingEntity) aimed;
            beam(sw, player, target);
        }

        target.addStatusEffect(new StatusEffectInstance(StatusEffects.LEVITATION, DURATION, 0, false, false, true));
        FLOATING.add(new Floating(sw, target, sw.getTime() + DURATION));

        // A ring of light blooms under it as it lifts off
        Vec3d c = target.getPos();
        double r = Math.max(0.6, target.getWidth());
        for (int i = 0; i < 24; i++) {
            double a = i * Math.PI * 2.0 / 24;
            sw.spawnParticles(ParticleTypes.END_ROD, c.x + Math.cos(a) * r, c.y + 0.1, c.z + Math.sin(a) * r, 0, 0, 0.06, 0, 1.0);
        }
        sw.spawnParticles(ParticleTypes.CLOUD, c.x, c.y + 0.1, c.z, 8, 0.4, 0.05, 0.4, 0.02);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_SHULKER_BULLET_HIT, SoundCategory.PLAYERS, 1.0f, 1.3f);
        world.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 1.0f, 1.6f);
        return true;
    }

    /** A thread of light from the staff to the target. */
    private static void beam(ServerWorld world, PlayerEntity player, LivingEntity target) {
        Vec3d from = player.getEyePos().add(player.getRotationVector().multiply(0.8)).add(0, -0.3, 0);
        Vec3d to = target.getBoundingBox().getCenter();
        Vec3d path = to.subtract(from);
        int steps = (int) (path.length() * 3);
        for (int i = 0; i <= steps; i++) {
            Vec3d p = from.add(path.multiply(i / (double) Math.max(1, steps)));
            world.spawnParticles(GLOW, p.x, p.y, p.z, 1, 0, 0, 0, 0);
        }
        world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENTITY_SHULKER_SHOOT, SoundCategory.PLAYERS, 0.8f, 1.4f);
    }
}
