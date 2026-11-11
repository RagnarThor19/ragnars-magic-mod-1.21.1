package net.ragnar.ragnarsmagicmod.geysir;

import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Tome of Geysir (see Geysir): a geysir under every creature within {@link #RADIUS} blocks, on the ground right where
 * it stands. Nothing nearby, nothing cast (and nothing spent).
 */
public class GeysirSpell implements Spell {
    /** How far out creatures get a geysir. */
    public static final double RADIUS = 10.0;
    /** At most this many geysirs at once, nearest first. */
    public static final int MAX_GEYSIRS = 32;
    /** How far below a creature the ground can be and still get a geysir (so one jumping or swimming still does). */
    private static final int MAX_DROP = 6;

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw)) return false;
        List<Vec3d> spots = spots(sw, player);
        if (spots.isEmpty()) {
            player.sendMessage(Text.literal("No creatures nearby for a geysir.").formatted(Formatting.GRAY), true);
            return false;
        }
        for (int i = 0; i < spots.size(); i++) GeysirEntity.erupt(sw, spots.get(i), player, i < 8);

        // A deep watery hum as the ground answers
        sw.playSound(null, player.getX(), player.getEyeY(), player.getZ(), SoundEvents.BLOCK_CONDUIT_ACTIVATE, SoundCategory.PLAYERS, 0.9f, 1.3f);
        sw.playSound(null, player.getX(), player.getEyeY(), player.getZ(), SoundEvents.ITEM_BUCKET_EMPTY, SoundCategory.PLAYERS, 0.8f, 0.7f);
        sw.spawnParticles(ParticleTypes.SPLASH, player.getX(), player.getY() + 0.1, player.getZ(), 30, 0.6, 0.05, 0.6, 0.3);
        return true;
    }

    /** Where the geysirs go: the ground under each creature in reach, nearest first, never two on the same spot. */
    public static List<Vec3d> spots(ServerWorld world, PlayerEntity caster) {
        Box box = caster.getBoundingBox().expand(RADIUS, 6.0, RADIUS);
        List<MobEntity> mobs = world.getEntitiesByClass(MobEntity.class, box,
                m -> m.squaredDistanceTo(caster) <= RADIUS * RADIUS && GeysirEntity.canLaunch(m, caster.getUuid()));
        mobs.sort(Comparator.comparingDouble(m -> m.squaredDistanceTo(caster)));
        List<Vec3d> spots = new ArrayList<>();
        for (MobEntity m : mobs) {
            if (spots.size() >= MAX_GEYSIRS) break;
            double ground = groundBelow(world, m.getX(), m.getY(), m.getZ());
            if (Double.isNaN(ground)) continue;
            Vec3d spot = new Vec3d(m.getX(), ground, m.getZ());
            if (spots.stream().anyMatch(s -> s.squaredDistanceTo(spot) < 0.7 * 0.7)) continue;
            spots.add(spot);
        }
        return spots;
    }

    /** The top of the first solid ground at or below {@code y}, or NaN if there's none within {@link #MAX_DROP}. */
    static double groundBelow(World world, double x, double y, double z) {
        BlockPos.Mutable p = BlockPos.ofFloored(x, y + 0.5, z).mutableCopy();
        for (int i = 0; i <= MAX_DROP + 1; i++, p.move(0, -1, 0)) {
            var shape = world.getBlockState(p).getCollisionShape(world, p);
            if (!shape.isEmpty()) {
                double top = p.getY() + shape.getMax(net.minecraft.util.math.Direction.Axis.Y);
                if (top <= y + 0.6) return top;
            }
        }
        return Double.NaN;
    }
}
