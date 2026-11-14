package net.ragnar.ragnarsmagicmod.sun;

import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;

/**
 * A sun's particles, made by each client for itself (so none of them cross the network): flames streaming off its
 * surface, loops of flame arching up off it like solar prominences, drips of lava, a trail of smoke as it drifts -
 * and the burst of flame when it goes nova. Counts grow with the sun but are capped, so it stays light.
 */
final class SunParticles {
    private SunParticles() {}

    static void tick(SunEntity sun) {
        World w = sun.getWorld();
        Random r = sun.rand();
        Vec3d c = sun.getPos();
        float rad = sun.radius();
        int age = sun.clientStageAge;
        switch (sun.stage()) {
            case SunEntity.IGNITING -> {
                // Flame drawn in from all round
                for (int i = 0; i < 6; i++) {
                    Vec3d dir = unit(r);
                    double d = rad * 2.5 + 1.5 + r.nextDouble() * 2;
                    Vec3d p = c.add(dir.multiply(d));
                    Vec3d v = dir.multiply(-d * 0.1);
                    w.addParticle(i % 2 == 0 ? ParticleTypes.FLAME : ParticleTypes.SMALL_FLAME, p.x, p.y, p.z, v.x, v.y, v.z);
                }
            }
            case SunEntity.DRIFTING -> {
                // Flames lifting off the surface
                int n = Math.min(12, 3 + (int) (rad * 1.6));
                for (int i = 0; i < n; i++) {
                    Vec3d dir = unit(r);
                    Vec3d p = c.add(dir.multiply(rad * 1.02));
                    Vec3d v = dir.multiply(0.03 + r.nextDouble() * 0.06).add(0, 0.02, 0);
                    w.addParticle(r.nextInt(4) == 0 ? ParticleTypes.SMALL_FLAME : ParticleTypes.FLAME, p.x, p.y, p.z, v.x, v.y, v.z);
                }
                if (age % 3 == 0) {
                    Vec3d p = c.add(unit(r).multiply(rad * 0.95));
                    w.addParticle(ParticleTypes.LAVA, p.x, p.y, p.z, 0, 0, 0);
                }
                // Smoke trailing behind it
                Vec3d moving = c.subtract(sun.prevX, sun.prevY, sun.prevZ);
                if (moving.lengthSquared() > 1e-6 && age % 2 == 0) {
                    Vec3d back = c.subtract(moving.normalize().multiply(rad * 1.05)).add(unit(r).multiply(rad * 0.4));
                    w.addParticle(ParticleTypes.LARGE_SMOKE, back.x, back.y, back.z, 0, 0.03, 0);
                }
                // A prominence: a loop of flame arching up off the surface
                if (age % 22 == 7) prominence(w, r, c, rad);
            }
            case SunEntity.COLLAPSING -> {
                for (int i = 0; i < 10; i++) {
                    Vec3d dir = unit(r);
                    double d = rad * 1.8 + r.nextDouble() * 2;
                    Vec3d p = c.add(dir.multiply(d));
                    Vec3d v = dir.multiply(-d * 0.18);
                    w.addParticle(ParticleTypes.FLAME, p.x, p.y, p.z, v.x, v.y, v.z);
                }
                if (age % 2 == 0) {
                    Vec3d p = c.add(unit(r).multiply(rad));
                    w.addParticle(ParticleTypes.SMOKE, p.x, p.y, p.z, 0, 0.05, 0);
                }
            }
            default -> {}
        }
    }

    /** A loop of flame between two nearby points of the surface, bulging out past it. */
    private static void prominence(World w, Random r, Vec3d c, float rad) {
        Vec3d a = unit(r);
        Vec3d side = a.crossProduct(Math.abs(a.y) < 0.9 ? new Vec3d(0, 1, 0) : new Vec3d(1, 0, 0)).normalize();
        double spread = 0.35 + r.nextDouble() * 0.25;
        Vec3d p0 = c.add(a.add(side.multiply(spread)).normalize().multiply(rad));
        Vec3d p2 = c.add(a.subtract(side.multiply(spread)).normalize().multiply(rad));
        Vec3d p1 = c.add(a.multiply(rad * (1.5 + r.nextDouble() * 0.4)));
        int n = 10 + (int) (rad * 3);
        for (int i = 0; i <= n; i++) {
            double t = i / (double) n;
            Vec3d p = p0.multiply((1 - t) * (1 - t)).add(p1.multiply(2 * (1 - t) * t)).add(p2.multiply(t * t));
            w.addParticle(ParticleTypes.FLAME, p.x, p.y, p.z, 0, 0.005, 0);
        }
    }

    /** The burst: flame flung out in every direction, lava, smoke left hanging, a blinding flash. */
    static void nova(SunEntity sun) {
        World w = sun.getWorld();
        Random r = sun.rand();
        Vec3d c = sun.getPos();
        float rad = sun.radius();
        w.addParticle(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y, c.z, 0, 0, 0);
        for (int i = 0; i < 240; i++) {
            Vec3d dir = unit(r);
            Vec3d p = c.add(dir.multiply(rad * 0.7));
            Vec3d v = dir.multiply(0.4 + r.nextDouble() * 0.75);
            w.addParticle(i % 4 == 0 ? ParticleTypes.SMALL_FLAME : ParticleTypes.FLAME, p.x, p.y, p.z, v.x, v.y, v.z);
        }
        for (int i = 0; i < 60; i++) {
            Vec3d p = c.add(unit(r).multiply(rad * (0.5 + r.nextDouble())));
            w.addParticle(ParticleTypes.LAVA, p.x, p.y, p.z, 0, 0, 0);
        }
        // A ring of fire thrown out flat around it
        for (int i = 0; i < 64; i++) {
            double a = i * Math.PI * 2 / 64;
            Vec3d dir = new Vec3d(Math.cos(a), 0, Math.sin(a));
            Vec3d p = c.add(dir.multiply(rad));
            Vec3d v = dir.multiply(0.9);
            w.addParticle(ParticleTypes.FLAME, p.x, p.y, p.z, v.x, 0.02, v.z);
        }
        for (int i = 0; i < 36; i++) {
            Vec3d dir = unit(r);
            Vec3d p = c.add(dir.multiply(rad * (0.5 + r.nextDouble())));
            Vec3d v = dir.multiply(0.05 + r.nextDouble() * 0.05);
            w.addParticle(ParticleTypes.LARGE_SMOKE, p.x, p.y, p.z, v.x, v.y + 0.02, v.z);
        }
    }

    private static Vec3d unit(Random r) {
        double z = r.nextDouble() * 2 - 1, a = r.nextDouble() * Math.PI * 2, s = Math.sqrt(1 - z * z);
        return new Vec3d(Math.cos(a) * s, z, Math.sin(a) * s);
    }
}
