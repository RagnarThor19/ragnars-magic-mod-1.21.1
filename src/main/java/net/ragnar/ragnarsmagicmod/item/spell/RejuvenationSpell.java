package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Heals 4 hearts at once, then Regeneration II and Absorption II for {@link #EFFECT_TICKS}. A blooming ring of
 * leaves opens at your feet and two green-and-gold ribbons spiral up around you; while the magic lasts, motes of
 * light drift upward, cherry petals fall, and each pulse of regeneration ripples out from your feet.
 */
public class RejuvenationSpell implements Spell {
    private static final float INSTANT_HEAL = 8.0f;
    private static final int EFFECT_TICKS = 100;
    private static final int HELIX_TICKS = 24;            // the rising spiral at the start
    private static final double HELIX_RADIUS = 0.9;
    private static final double HELIX_HEIGHT = 2.4;
    private static final int PULSE_INTERVAL = 25;         // matches Regeneration II's healing rhythm

    private static final DustParticleEffect GREEN = new DustParticleEffect(new Vector3f(0.35f, 1.0f, 0.45f), 1.0f);
    private static final DustParticleEffect GOLD = new DustParticleEffect(new Vector3f(1.0f, 0.85f, 0.35f), 0.9f);
    private static final DustParticleEffect SOFT_GREEN = new DustParticleEffect(new Vector3f(0.6f, 1.0f, 0.6f), 0.6f);

    private static final class Bloom {
        final ServerWorld world;
        final UUID player;
        int age = 0;

        Bloom(ServerWorld world, UUID player) {
            this.world = world;
            this.player = player;
        }
    }

    private static final List<Bloom> ACTIVE = new ArrayList<>();
    private static boolean registered = false;

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            Iterator<Bloom> it = ACTIVE.iterator();
            while (it.hasNext()) {
                Bloom b = it.next();
                if (b.world == world && !tick(b)) it.remove();
            }
        });
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        ensureRegistered();
        ServerWorld sw = (ServerWorld) world;

        player.heal(INSTANT_HEAL);
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, EFFECT_TICKS, 1, false, false, true));
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, EFFECT_TICKS, 1, false, false, true));

        ACTIVE.removeIf(b -> b.player.equals(player.getUuid()));
        ACTIVE.add(new Bloom(sw, player.getUuid()));

        // A ring of leaves and sparkles opening outward from the feet
        Vec3d feet = player.getPos().add(0, 0.1, 0);
        for (int i = 0; i < 32; i++) {
            double a = i * MathHelper.TAU / 32;
            double dx = Math.cos(a);
            double dz = Math.sin(a);
            sw.spawnParticles(ParticleTypes.HAPPY_VILLAGER, feet.x + dx * 0.4, feet.y, feet.z + dz * 0.4, 0, dx, 0.05, dz, 0.25);
            if (i % 2 == 0) sw.spawnParticles(ParticleTypes.CHERRY_LEAVES, feet.x + dx * 1.2, feet.y + 0.3, feet.z + dz * 1.2, 1, 0.1, 0.1, 0.1, 0);
        }
        sw.spawnParticles(ParticleTypes.COMPOSTER, feet.x, feet.y + 0.2, feet.z, 20, 0.8, 0.1, 0.8, 0);

        sw.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BLOCK_AMETHYST_CLUSTER_BREAK, SoundCategory.PLAYERS, 1.0f, 1.3f);
        sw.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BLOCK_AZALEA_LEAVES_PLACE, SoundCategory.PLAYERS, 1.2f, 0.8f);
        sw.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BLOCK_BEACON_POWER_SELECT, SoundCategory.PLAYERS, 0.8f, 1.8f);
        return true;
    }

    /** Returns false once the effect is over. */
    private static boolean tick(Bloom b) {
        PlayerEntity p = b.world.getPlayerByUuid(b.player);
        if (p == null || !p.isAlive() || ++b.age > EFFECT_TICKS) return false;
        ServerWorld world = b.world;
        var rnd = world.random;
        Vec3d feet = p.getPos();

        if (b.age <= HELIX_TICKS) {
            // Two ribbons winding up around the body, a few points per tick so they read as solid lines
            for (int step = 0; step < 3; step++) {
                double t = (b.age - 1 + step / 3.0) / HELIX_TICKS;
                double y = feet.y + 0.1 + t * HELIX_HEIGHT;
                double r = HELIX_RADIUS * (1.0 - 0.45 * t);
                double a = t * MathHelper.TAU * 2.5;
                for (int strand = 0; strand < 2; strand++) {
                    double sa = a + strand * Math.PI;
                    world.spawnParticles(strand == 0 ? GREEN : GOLD,
                            feet.x + Math.cos(sa) * r, y, feet.z + Math.sin(sa) * r, 1, 0, 0, 0, 0);
                }
            }
            if (b.age == HELIX_TICKS) {
                // The ribbons meet overhead and burst
                Vec3d top = feet.add(0, HELIX_HEIGHT + 0.2, 0);
                world.spawnParticles(ParticleTypes.HEART, top.x, top.y, top.z, 5, 0.4, 0.2, 0.4, 0);
                world.spawnParticles(ParticleTypes.END_ROD, top.x, top.y, top.z, 12, 0.1, 0.1, 0.1, 0.12);
                world.playSound(null, top.x, top.y, top.z, SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.4f, 1.8f);
            }
        }

        // Motes of light drifting up out of the body
        if (b.age % 2 == 0) {
            double a = rnd.nextDouble() * MathHelper.TAU;
            double r = 0.3 + rnd.nextDouble() * 0.4;
            world.spawnParticles(SOFT_GREEN, feet.x + Math.cos(a) * r, feet.y + 0.2 + rnd.nextDouble() * 1.4, feet.z + Math.sin(a) * r,
                    0, 0, 1, 0, 0.04);
        }
        // Petals drifting down from above
        if (rnd.nextInt(4) == 0) {
            world.spawnParticles(ParticleTypes.CHERRY_LEAVES, feet.x, feet.y + 2.6, feet.z, 1, 0.7, 0.1, 0.7, 0);
        }
        // Each regeneration pulse ripples out along the ground with a heart
        if (b.age % PULSE_INTERVAL == 0) {
            for (int i = 0; i < 20; i++) {
                double a = i * MathHelper.TAU / 20;
                world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, feet.x + Math.cos(a) * 0.3, feet.y + 0.1, feet.z + Math.sin(a) * 0.3,
                        0, Math.cos(a), 0, Math.sin(a), 0.15);
            }
            world.spawnParticles(ParticleTypes.HEART, feet.x, feet.y + p.getHeight() + 0.3, feet.z, 1, 0.1, 0, 0.1, 0);
            world.playSound(null, feet.x, feet.y, feet.z, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.6f, 1.6f);
        }
        return true;
    }
}
