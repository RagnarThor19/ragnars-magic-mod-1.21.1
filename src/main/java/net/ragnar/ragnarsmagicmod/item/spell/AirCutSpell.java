package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.Brightness;
import net.minecraft.util.math.AffineTransformation;
import net.ragnar.ragnarsmagicmod.util.TempEntities;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.util.Slicer;
import org.joml.Vector3f;

/**
 * The sharp leaf's big brother: a crescent blade of compressed air, fired straight at the crosshair. It is faster,
 * a little more forgiving to aim, and hits harder, especially at head height: a 20-health mob drops in two clean
 * headshots or three body hits.
 */
public class AirCutSpell implements Spell {
    private static final float BODY_DAMAGE = 7.0f;
    private static final float HEAD_DAMAGE = 10.0f;
    private static final double CRESCENT_HALF_WIDTH = 0.8;
    private static final double SWEEP_BACK = 0.45;       // how far the tips trail behind the middle
    private static final int SLIVERS = 7;


    private static final Slicer.Style AIR_CUT = new Slicer.Style() {
        @Override public double speed() { return 2.8; }
        @Override public double range() { return 36.0; }
        @Override public double hitMargin() { return 0.4; }

        @Override
        public List<DisplayEntity> spawnVisual(ServerWorld world, Vec3d pos, Vec3d dir) {
            // The crescent is a row of thin glowing slivers that fly as one piece, so it leaves no trail
            List<DisplayEntity> slivers = new ArrayList<>();
            for (int i = 0; i < SLIVERS; i++) {
                DisplayEntity.BlockDisplayEntity d = EntityType.BLOCK_DISPLAY.create(world);
                if (d == null) continue;
                d.setBlockState(Blocks.WHITE_STAINED_GLASS.getDefaultState());
                d.setBrightness(Brightness.FULL);
                d.setTeleportDuration(1);
                d.setViewRange(1.5f);
                d.refreshPositionAndAngles(pos.x, pos.y, pos.z, 0f, 0f);
                d.setTransformation(sliverTransform(i, dir));
                TempEntities.track(d);
                world.spawnEntity(d);
                slivers.add(d);
            }
            return slivers;
        }

        @Override
        public void flight(ServerWorld world, List<DisplayEntity> visual, Vec3d from, Vec3d to, Vec3d dir, int age) {
            for (int i = 0; i < visual.size(); i++) {
                DisplayEntity d = visual.get(i);
                d.setPosition(to.x, to.y, to.z);
                d.setTransformation(sliverTransform(i, dir));
                d.setStartInterpolation(0);
                d.setInterpolationDuration(1);
            }
        }

        @Override
        public void hitEntity(ServerWorld world, PlayerEntity owner, LivingEntity target, Vec3d at, boolean head) {
            float damage = head ? HEAD_DAMAGE : BODY_DAMAGE;
            target.damage(owner != null ? world.getDamageSources().thrown(owner, owner) : world.getDamageSources().generic(), damage);
            world.spawnParticles(ParticleTypes.SWEEP_ATTACK, at.x, at.y, at.z, 2, 0.2, 0.1, 0.2, 0);
            world.spawnParticles(ParticleTypes.CLOUD, at.x, at.y, at.z, 8, 0.2, 0.2, 0.2, 0.08);
            world.spawnParticles(ParticleTypes.SMALL_GUST, at.x, at.y, at.z, 1, 0, 0, 0, 0);
            world.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 1.0f, 0.9f);
            world.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_BREEZE_DEFLECT, SoundCategory.PLAYERS, 0.8f, 1.3f);
            if (head) {
                world.spawnParticles(ParticleTypes.CRIT, at.x, at.y, at.z, 12, 0.15, 0.15, 0.15, 0.35);
                world.spawnParticles(ParticleTypes.ENCHANTED_HIT, at.x, at.y, at.z, 8, 0.15, 0.15, 0.15, 0.3);
                world.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, SoundCategory.PLAYERS, 1.0f, 1.3f);
            }
            if (owner != null) {
                // The hit "ding", pitched up for headshots
                world.playSound(null, owner.getX(), owner.getY(), owner.getZ(), SoundEvents.ENTITY_ARROW_HIT_PLAYER, SoundCategory.PLAYERS, 0.4f, head ? 1.9f : 1.4f);
            }
        }

        @Override
        public void hitWall(ServerWorld world, Vec3d at, Vec3d dir) {
            world.spawnParticles(ParticleTypes.CLOUD, at.x, at.y, at.z, 6, 0.15, 0.15, 0.15, 0.05);
            world.spawnParticles(ParticleTypes.SWEEP_ATTACK, at.x, at.y, at.z, 1, 0, 0, 0, 0);
            world.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_BREEZE_DEFLECT, SoundCategory.PLAYERS, 0.6f, 1.6f);
        }
    };

    /**
     * Sliver {@code i} of the crescent: a thin bar lying along the curve, which runs across the flight path with
     * its tips swept back. Local frame: across = sideways, back = against the flight direction.
     */
    private static AffineTransformation sliverTransform(int i, Vec3d dir) {
        Vec3d across = dir.crossProduct(new Vec3d(0, 1, 0));
        across = across.lengthSquared() < 1.0e-4 ? new Vec3d(1, 0, 0) : across.normalize();
        // Position and tangent on the curve p(s) = across * w * s - dir * c * s^2, for s in [-1, 1]
        double s = SLIVERS == 1 ? 0 : -1 + 2.0 * i / (SLIVERS - 1);
        Vec3d at = across.multiply(s * CRESCENT_HALF_WIDTH).subtract(dir.multiply(s * s * SWEEP_BACK));
        Vec3d tangent = across.multiply(CRESCENT_HALF_WIDTH).subtract(dir.multiply(2 * s * SWEEP_BACK)).normalize();
        float length = (float) (2 * CRESCENT_HALF_WIDTH / SLIVERS * 1.35);
        // Thicker in the middle, tapering to the tips
        float thickness = (float) (0.09 * (1.0 - 0.6 * s * s));
        return new AffineTransformation(new Matrix4f()
                .translate(at.toVector3f())
                .rotate(new Quaternionf().rotationTo(new Vector3f(1, 0, 0), tangent.toVector3f()))
                .scale(length, thickness, thickness * 2.5f)
                .translate(-0.5f, -0.5f, -0.5f));
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        ServerWorld sw = (ServerWorld) world;
        Slicer.fire(sw, player, AIR_CUT);
        sw.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.8f, 1.3f);
        sw.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENTITY_WIND_CHARGE_THROW, SoundCategory.PLAYERS, 0.8f, 1.4f);
        return true;
    }
}
