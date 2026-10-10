package net.ragnar.ragnarsmagicmod.sleep;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Tome of Sleep Darts: a {@link #CHARGE_TICKS} tick charge - lavender motes drawing in to the staff with a rising
 * chime - then the dart flies off wherever you're looking by then.
 */
public class SleepDartSpell implements Spell {
    public static final int CHARGE_TICKS = 20; // 1 second

    /** Server: casters mid-charge, and how long they've been at it. */
    private static final Map<UUID, int[]> CHARGING = new HashMap<>();

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw) || CHARGING.containsKey(player.getUuid())) return false;
        CHARGING.put(player.getUuid(), new int[1]);
        sw.playSound(null, player.getX(), player.getEyeY(), player.getZ(), SoundEvents.ITEM_CROSSBOW_LOADING_START.value(), SoundCategory.PLAYERS, 0.7f, 1.6f);
        sw.playSound(null, player.getX(), player.getEyeY(), player.getZ(), SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 0.8f, 1.4f);
        return true;
    }

    static void tick(ServerWorld world) {
        if (CHARGING.isEmpty()) return;
        Iterator<Map.Entry<UUID, int[]>> it = CHARGING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, int[]> entry = it.next();
            PlayerEntity p = world.getPlayerByUuid(entry.getKey());
            if (p == null) {
                if (world.getServer().getPlayerManager().getPlayer(entry.getKey()) == null) it.remove(); // left the game
                continue;
            }
            // Can't finish the shot while nodding off yourself
            if (!p.isAlive() || Sleep.isAsleep(p)) {
                it.remove();
                continue;
            }
            int age = ++entry.getValue()[0];
            Vec3d tip = tip(p);
            if (age < CHARGE_TICKS) {
                charge(world, p, tip, age);
            } else {
                it.remove();
                fire(world, p, tip);
            }
        }
    }

    static void clear() {
        CHARGING.clear();
    }

    /** About where the staff's tip is, a little out in front of the caster's eyes. */
    private static Vec3d tip(PlayerEntity p) {
        Vec3d look = p.getRotationVec(1f);
        Vec3d right = look.crossProduct(new Vec3d(0, 1, 0));
        if (right.lengthSquared() < 1.0e-4) right = new Vec3d(1, 0, 0);
        return p.getEyePos().add(look.multiply(1.1)).add(right.normalize().multiply(0.3)).add(0, -0.25, 0);
    }

    private static void charge(ServerWorld world, PlayerEntity p, Vec3d tip, int age) {
        float k = age / (float) CHARGE_TICKS;
        // Motes spiralling in from all round to the tip
        for (int i = 0; i < 2; i++) {
            double a = world.random.nextDouble() * MathHelper.TAU;
            double r = 0.6 * (1.0 - k) + 0.25;
            double x = tip.x + Math.cos(a) * r, z = tip.z + Math.sin(a) * r;
            double y = tip.y + (world.random.nextDouble() - 0.5) * r;
            Vec3d in = tip.subtract(x, y, z).multiply(0.18);
            world.spawnParticles(Sleep.MOTE, x, y, z, 0, in.x, in.y, in.z, 1.0);
        }
        if (age % 3 == 0) world.spawnParticles(ParticleTypes.ENCHANT, tip.x, tip.y, tip.z, 3, 0.15, 0.15, 0.15, 0.5);
        if (age % 5 == 0) {
            world.playSound(null, tip.x, tip.y, tip.z, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.6f, 0.9f + k * 0.9f);
        }
    }

    private static void fire(ServerWorld world, PlayerEntity p, Vec3d tip) {
        SleepDartEntity dart = new SleepDartEntity(world, p);
        dart.setPosition(tip.x, tip.y, tip.z);
        dart.setVelocity(p, p.getPitch(), p.getYaw(), 0f, SleepDartEntity.SPEED, 0.3f);
        world.spawnEntity(dart);

        world.playSound(null, tip.x, tip.y, tip.z, SoundEvents.ENTITY_ARROW_SHOOT, SoundCategory.PLAYERS, 0.8f, 1.9f);
        world.playSound(null, tip.x, tip.y, tip.z, SoundEvents.ENTITY_BREEZE_SHOOT, SoundCategory.PLAYERS, 0.5f, 1.8f);
        world.spawnParticles(Sleep.MOTE, tip.x, tip.y, tip.z, 6, 0.08, 0.08, 0.08, 0.0);
        world.spawnParticles(ParticleTypes.END_ROD, tip.x, tip.y, tip.z, 3, 0.05, 0.05, 0.05, 0.03);
    }
}
