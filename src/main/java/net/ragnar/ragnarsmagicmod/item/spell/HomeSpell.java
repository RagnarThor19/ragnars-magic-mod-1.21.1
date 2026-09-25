package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Points the way home: for a few seconds a stream of sparkles flows from you toward your respawn point (your bed or
 * respawn anchor), and the distance shows above the hotbar. If you have no respawn point set, or it is in another
 * dimension, it tells you so instead.
 */
public class HomeSpell implements Spell {
    private static final int GUIDE_TICKS = 20 * 4;
    private static final double STREAM_SPEED = 0.6;
    private static final double ARRIVED_DISTANCE = 4.0;
    private static final DustParticleEffect WARM = new DustParticleEffect(new Vector3f(1.0f, 0.8f, 0.35f), 1.0f);

    private record Guide(ServerWorld world, UUID player, Vec3d home, int[] age) {}

    private static final List<Guide> ACTIVE = new ArrayList<>();
    private static boolean registered = false;

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            Iterator<Guide> it = ACTIVE.iterator();
            while (it.hasNext()) {
                Guide g = it.next();
                if (g.world() == world && !tick(g)) it.remove();
            }
        });
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient || !(player instanceof ServerPlayerEntity sp)) return false;
        ensureRegistered();
        ServerWorld sw = (ServerWorld) world;

        BlockPos spawn = sp.getSpawnPointPosition();
        if (spawn == null) {
            fizzle(sw, sp, "You don't have a respawn point.");
            return false;
        }
        if (sp.getSpawnPointDimension() != sw.getRegistryKey()) {
            fizzle(sw, sp, "Your home is in another dimension.");
            return false;
        }

        Vec3d home = Vec3d.ofCenter(spawn);
        if (home.distanceTo(sp.getPos()) <= ARRIVED_DISTANCE) {
            sp.sendMessage(Text.literal("You're already home.").formatted(Formatting.GOLD), true);
            sw.spawnParticles(ParticleTypes.HEART, sp.getX(), sp.getY() + 2.1, sp.getZ(), 1, 0, 0, 0, 0);
            return true;
        }

        ACTIVE.removeIf(g -> g.player().equals(sp.getUuid()));
        ACTIVE.add(new Guide(sw, sp.getUuid(), home, new int[]{0}));
        sw.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, SoundCategory.PLAYERS, 0.6f, 1.6f);
        return true;
    }

    private static void fizzle(ServerWorld world, ServerPlayerEntity player, String message) {
        player.sendMessage(Text.literal(message).formatted(Formatting.GRAY), true);
        Vec3d p = player.getEyePos().add(player.getRotationVector().multiply(0.8));
        world.spawnParticles(ParticleTypes.SMOKE, p.x, p.y, p.z, 12, 0.15, 0.15, 0.15, 0.02);
        world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.PLAYERS, 0.5f, 1.4f);
    }

    /** Returns false when the guiding stream is done. */
    private static boolean tick(Guide g) {
        int age = ++g.age()[0];
        PlayerEntity player = g.world().getPlayerByUuid(g.player());
        if (player == null || !player.isAlive() || age > GUIDE_TICKS) return false;

        Vec3d start = player.getPos().add(0, 1.1, 0);
        Vec3d dir = g.home().subtract(start);
        double dist = dir.length();
        if (dist < 1.0e-3) return false;
        dir = dir.multiply(1.0 / dist);

        // Particles spawned with count 0 fly along the given direction, so the stream flows toward home
        Vec3d from = start.add(dir.multiply(0.8));
        g.world().spawnParticles(ParticleTypes.END_ROD, from.x, from.y, from.z, 0, dir.x, dir.y, dir.z, STREAM_SPEED);
        if (age % 2 == 0) {
            g.world().spawnParticles(ParticleTypes.WAX_ON, from.x, from.y, from.z, 0, dir.x, dir.y, dir.z, STREAM_SPEED * 0.8);
        }
        // A short, steady pointer so the direction is readable even when standing still
        for (int i = 1; i <= 4; i++) {
            Vec3d p = start.add(dir.multiply(0.8 + i * 0.5));
            g.world().spawnParticles(WARM, p.x, p.y, p.z, 1, 0, 0, 0, 0);
        }

        if (age % 10 == 1) {
            player.sendMessage(Text.literal("Home: " + Math.round(dist) + " blocks").formatted(Formatting.GOLD), true);
        }
        return true;
    }
}
