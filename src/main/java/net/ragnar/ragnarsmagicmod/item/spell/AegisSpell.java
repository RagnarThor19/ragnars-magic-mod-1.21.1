package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.*;

public class AegisSpell implements Spell {

    private static final int DURATION_TICKS = 50; // 2.5 seconds (20 ticks per second)
    private static final Map<UUID, Long> ACTIVE = new HashMap<>();
    private static boolean TICK_REGISTERED = false;

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;

        if (!TICK_REGISTERED) {
            ServerTickEvents.END_WORLD_TICK.register(AegisSpell::tick);
            TICK_REGISTERED = true;
        }

        // play sound
        world.playSound(null, player.getBlockPos(),
                SoundEvents.ITEM_TOTEM_USE, SoundCategory.PLAYERS,
                1.2f, 1.0f);

        // activate shield
        ACTIVE.put(player.getUuid(), world.getTime() + DURATION_TICKS);

        // initial burst particles
        spawnAegisParticles((ServerWorld) world, player);

        player.addStatusEffect(new StatusEffectInstance(
                net.minecraft.entity.effect.StatusEffects.RESISTANCE, 30, 10, false, false, true)); // level 5 -> 80%

        return true;
    }

    private static void tick(ServerWorld world) {
        long time = world.getTime();
        Iterator<Map.Entry<UUID, Long>> it = ACTIVE.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<UUID, Long> e = it.next();
            PlayerEntity p = world.getPlayerByUuid(e.getKey());
            if (p == null) {
                it.remove();
                continue;
            }

            // end if expired
            if (time >= e.getValue()) {
                it.remove();
                continue;
            }

            // swirl particles around player
            spawnAegisAura(world, p);
        }
    }

    private static void spawnAegisParticles(ServerWorld world, PlayerEntity p) {
        Vec3d pos = p.getPos().add(0, 1, 0);

        for (int i = 0; i < 60; i++) {
            double radius = 1.2 + world.random.nextDouble() * 0.2;
            double angle = world.random.nextDouble() * MathHelper.TAU;
            double yOffset = world.random.nextDouble() * 1.5 - 0.3;

            double x = pos.x + Math.cos(angle) * radius;
            double y = pos.y + yOffset;
            double z = pos.z + Math.sin(angle) * radius;

            if (world.random.nextBoolean()) {
                world.spawnParticles(ParticleTypes.PORTAL, x, y, z, 1, 0, 0, 0, 0);
            } else {
                world.spawnParticles(ParticleTypes.SCRAPE, x, y, z, 1, 0, 0, 0, 0);
            }
        }
    }

    private static void spawnAegisAura(ServerWorld world, PlayerEntity p) {
        Vec3d pos = p.getPos().add(0, 1, 0);

        // rotating swirl around player
        double radius = 1.0;
        double angleOffset = (world.getTime() % 360) * 0.2;
        for (int i = 0; i < 12; i++) {
            double angle = angleOffset + (Math.PI * 2 * i / 12);
            double x = pos.x + Math.cos(angle) * radius;
            double y = pos.y + Math.sin((angle + world.getTime() * 0.05)) * 0.3;
            double z = pos.z + Math.sin(angle) * radius;

            world.spawnParticles(ParticleTypes.ENCHANT, x, y, z, 1, 0, 0, 0, 0);
            world.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, x, y, z, 1, 0, 0, 0, 0);
        }

        // small sparkle above head
        if (world.random.nextInt(3) == 0) {
            world.spawnParticles(ParticleTypes.END_ROD,
                    pos.x, pos.y + 1.3, pos.z,
                    2, 0.05, 0.05, 0.05, 0.0);
        }
    }

    /**
     * Call this from a mixin or event if you want real invulnerability logic —
     * but for now, the spell just cancels damage manually.
     */
    public static boolean isShielded(PlayerEntity player) {
        return ACTIVE.containsKey(player.getUuid());
    }

    /**
     * Simple helper for checking invulnerability in damage event hooks.
     */
    public static boolean cancelDamage(PlayerEntity player, DamageSource source) {
        return isShielded(player);
    }
}
