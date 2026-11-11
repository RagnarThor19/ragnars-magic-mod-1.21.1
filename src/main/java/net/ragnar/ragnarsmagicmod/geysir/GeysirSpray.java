package net.ragnar.ragnarsmagicmod.geysir;

import net.minecraft.block.BlockState;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;

/**
 * The loose water around a geysir, thrown by each client from the geysir's age (the column itself is GeysirRenderer):
 * ground chips hopping and droplets seeping up while it shakes; a burst of spray, ground and steam as it breaks
 * through; spray and steam off the top and water raining back down round it while it stands; a last drizzle as it
 * falls.
 */
final class GeysirSpray {
    private GeysirSpray() {}

    static void tick(GeysirEntity g) {
        World w = g.getWorld();
        Random r = w.random;
        int t = g.age;
        double x = g.getX(), y = g.getY(), z = g.getZ();
        BlockState ground = w.getBlockState(g.getBlockPos().down());
        BlockStateParticleEffect chips = ground.isAir() ? null : new BlockStateParticleEffect(ParticleTypes.BLOCK, ground);

        if (t < GeysirEntity.WARN) {
            double shake = GeysirEntity.shakeAt(t);
            // The ground jumping
            if (chips != null && r.nextFloat() < 0.4f + shake) {
                double a = r.nextDouble() * Math.PI * 2, d = r.nextDouble() * 1.1;
                w.addParticle(chips, x + Math.cos(a) * d, y + 0.05, z + Math.sin(a) * d, 0, 0.05 + 0.2 * shake, 0);
            }
            // Water seeping and bubbling up through it
            if (r.nextFloat() < 0.3f + shake) {
                double a = r.nextDouble() * Math.PI * 2, d = r.nextDouble() * 0.6;
                w.addParticle(ParticleTypes.SPLASH, x + Math.cos(a) * d, y + 0.1, z + Math.sin(a) * d, 0, 0.1, 0);
            }
            if (t > GeysirEntity.WARN / 2 && r.nextFloat() < shake) {
                w.addParticle(ParticleTypes.BUBBLE_POP, x + (r.nextDouble() - 0.5) * 0.6, y + 0.1, z + (r.nextDouble() - 0.5) * 0.6, 0, 0.05, 0);
            }
            return;
        }

        double h = GeysirEntity.heightAt(t);
        if (t == GeysirEntity.WARN) {
            // Breaking through: spray, ground and a ring of steam rolling out
            for (int i = 0; i < 40; i++) {
                double a = r.nextDouble() * Math.PI * 2, d = r.nextDouble() * 1.0;
                w.addParticle(ParticleTypes.SPLASH, x + Math.cos(a) * d, y + 0.2, z + Math.sin(a) * d, 0, 0.3, 0);
            }
            for (int i = 0; i < 14; i++) {
                double a = i * Math.PI * 2 / 14;
                w.addParticle(ParticleTypes.CLOUD, x, y + 0.3, z, Math.cos(a) * 0.25, 0.04, Math.sin(a) * 0.25);
            }
            if (chips != null) {
                for (int i = 0; i < 16; i++) {
                    double a = r.nextDouble() * Math.PI * 2;
                    w.addParticle(chips, x + Math.cos(a) * 0.4, y + 0.2, z + Math.sin(a) * 0.4, Math.cos(a) * 0.3, 0.5, Math.sin(a) * 0.3);
                }
            }
        }

        boolean standing = t < GeysirEntity.WARN + GeysirEntity.RISE + GeysirEntity.HOLD;
        if (standing) {
            // Spray and steam off the top
            for (int i = 0; i < 6; i++) {
                w.addParticle(ParticleTypes.SPLASH, x + (r.nextDouble() - 0.5) * 1.0, y + h, z + (r.nextDouble() - 0.5) * 1.0, 0, 0.3, 0);
            }
            if (r.nextFloat() < 0.7f) {
                w.addParticle(ParticleTypes.CLOUD, x + (r.nextDouble() - 0.5) * 0.8, y + h + 0.3, z + (r.nextDouble() - 0.5) * 0.8,
                        (r.nextDouble() - 0.5) * 0.05, 0.08, (r.nextDouble() - 0.5) * 0.05);
            }
            // Splashing round the foot
            for (int i = 0; i < 2; i++) {
                double a = r.nextDouble() * Math.PI * 2;
                w.addParticle(ParticleTypes.SPLASH, x + Math.cos(a) * 0.9, y + 0.1, z + Math.sin(a) * 0.9, 0, 0.2, 0);
            }
        }
        // Water raining back down round it
        int rain = standing ? 5 : 3;
        for (int i = 0; i < rain && h > 0.5; i++) {
            double a = r.nextDouble() * Math.PI * 2, d = 0.7 + r.nextDouble() * 1.5;
            w.addParticle(ParticleTypes.FALLING_WATER, x + Math.cos(a) * d, y + h * (0.4 + 0.6 * r.nextDouble()), z + Math.sin(a) * d, 0, 0, 0);
        }
    }
}
