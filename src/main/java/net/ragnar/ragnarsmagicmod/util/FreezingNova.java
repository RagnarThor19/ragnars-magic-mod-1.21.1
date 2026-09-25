package net.ragnar.ragnarsmagicmod.util;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.decoration.Brightness;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * The Tome of Freezing's nova. A frost sigil burns into the ground and ice crystals rise and circle the caster while
 * a sphere of frost swells out to {@link #MAX_RADIUS}. Then it detonates: the crystals shatter outward, and every
 * creature inside is hurt, frozen solid in a shell of ice and held there while snow falls over the frozen field.
 */
public final class FreezingNova {
    private FreezingNova() {}

    private static final float MAX_RADIUS = 22.0f;
    private static final int EXPANSION_TICKS = 40;   // 2.0s swelling
    private static final int HOLD_TICKS = 10;        // 0.5s at full size
    private static final int FREEZE_TICKS = 100;     // 5.0s frozen
    private static final int DETONATE = EXPANSION_TICKS + HOLD_TICKS;
    private static final int FINISH = DETONATE + FREEZE_TICKS;
    private static final float DAMAGE = 12.0f;
    private static final int STEP_TICKS = 5;         // the globe grows one step per chime
    private static final int STEPS = EXPANSION_TICKS / STEP_TICKS;

    private static final int CRYSTALS = 6;
    private static final float CRYSTAL_ORBIT = 1.9f;
    private static final float CRYSTAL_FLY_OUT = 9.0f;
    private static final int CRYSTAL_SHATTER_TICKS = 6;
    private static final float SIGIL_RADIUS = 3.2f;

    private static final DustParticleEffect FROST = new DustParticleEffect(new Vector3f(0.55f, 0.85f, 1.0f), 1.0f);
    private static final DustParticleEffect FROST_BRIGHT = new DustParticleEffect(new Vector3f(0.85f, 0.97f, 1.0f), 1.3f);
    private static final BlockState ICE = Blocks.ICE.getDefaultState();

    private static final class Frozen {
        final LivingEntity entity;
        final DisplayEntity.BlockDisplayEntity shell;

        Frozen(LivingEntity entity, DisplayEntity.BlockDisplayEntity shell) {
            this.entity = entity;
            this.shell = shell;
        }
    }

    private static final class Nova {
        final ServerWorld world;
        final Vec3d center;
        final UUID casterId;
        final List<DisplayEntity.BlockDisplayEntity> crystals = new ArrayList<>();
        final List<Frozen> frozen = new ArrayList<>();
        int age = 0;

        Nova(ServerWorld world, Vec3d center, UUID casterId) {
            this.world = world;
            this.center = center;
            this.casterId = casterId;
        }
    }

    private static final List<Nova> NOVAS = new ArrayList<>();
    private static boolean registered = false;

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            Iterator<Nova> it = NOVAS.iterator();
            while (it.hasNext()) {
                Nova n = it.next();
                if (n.world == world && !tick(n)) {
                    for (var c : n.crystals) TempEntities.discard(c);
                    for (Frozen f : n.frozen) if (f.shell != null) TempEntities.discard(f.shell);
                    it.remove();
                }
            }
        });
    }

    public static void create(ServerWorld world, Vec3d center, UUID casterId) {
        ensureRegistered();
        Nova n = new Nova(world, center, casterId);
        for (int i = 0; i < CRYSTALS; i++) {
            DisplayEntity.BlockDisplayEntity d = EntityType.BLOCK_DISPLAY.create(world);
            if (d == null) continue;
            d.setBlockState(i % 2 == 0 ? Blocks.BLUE_ICE.getDefaultState() : Blocks.PACKED_ICE.getDefaultState());
            d.setBrightness(new Brightness(15, 15));
            d.setTeleportDuration(1);
            d.setViewRange(3.0f);
            d.refreshPositionAndAngles(center.x, center.y, center.z, 0f, 0f);
            d.setTransformation(crystalTransform(i, 0, CRYSTAL_ORBIT, 0.01f));
            TempEntities.track(d);
            world.spawnEntity(d);
            n.crystals.add(d);
        }
        NOVAS.add(n);
        world.playSound(null, center.x, center.y, center.z, SoundEvents.BLOCK_BEACON_POWER_SELECT, SoundCategory.PLAYERS, 1.5f, 0.6f);
        world.playSound(null, center.x, center.y, center.z, SoundEvents.ENTITY_PLAYER_HURT_FREEZE, SoundCategory.PLAYERS, 1.5f, 0.5f);
    }

    // ---------------------------------------------------------------------
    // Timeline
    // ---------------------------------------------------------------------

    /** Returns false once the last victim has thawed. */
    private static boolean tick(Nova n) {
        n.age++;
        ServerWorld w = n.world;

        if (n.age <= DETONATE) {
            float t = n.age / (float) DETONATE;
            if (n.age % 2 == 0) drawSigil(n, t);
            moveCrystals(n, t);

            if (n.age <= EXPANSION_TICKS) {
                // The globe grows in steps, one per chime, so you can see exactly how far it reaches
                int step = (n.age - 1) / STEP_TICKS + 1;
                float radius = globeRadius(step);
                boolean newStep = (n.age - 1) % STEP_TICKS == 0;
                if (newStep) {
                    drawGlobe(w, n.center, radius, FROST_BRIGHT);
                    float progress = step / (float) STEPS;
                    w.playSound(null, n.center.x, n.center.y, n.center.z, SoundEvents.BLOCK_GLASS_PLACE, SoundCategory.PLAYERS, 2.0f, 0.5f + progress);
                    w.playSound(null, n.center.x, n.center.y, n.center.z, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.5f, 0.6f + progress);
                } else {
                    drawSphere(w, n.center, radius, (int) (radius * 5));
                }
                drawGroundRing(w, n.center, radius);
                snowInside(w, n.center, radius);
            } else {
                // Held at full size, shimmering, the crystals humming before the burst
                drawSphere(w, n.center, MAX_RADIUS, (int) (MAX_RADIUS * 5));
                drawGroundRing(w, n.center, MAX_RADIUS);
                snowInside(w, n.center, MAX_RADIUS);
                if (n.age % STEP_TICKS == 0) drawGlobe(w, n.center, MAX_RADIUS, FROST);
                if (n.age == EXPANSION_TICKS + 2) {
                    w.playSound(null, n.center.x, n.center.y, n.center.z, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 3.0f, 0.5f);
                }
            }
            if (n.age == DETONATE) detonate(n);
            return true;
        }

        shatterCrystals(n);
        tickFrozen(n);

        // Snow drifting down over the frozen field
        for (int i = 0; i < 18; i++) {
            double r = MAX_RADIUS * Math.sqrt(w.random.nextDouble());
            double a = w.random.nextDouble() * MathHelper.TAU;
            w.spawnParticles(ParticleTypes.SNOWFLAKE, n.center.x + Math.cos(a) * r, n.center.y + 5 + w.random.nextDouble() * 3,
                    n.center.z + Math.sin(a) * r, 0, 0, -1, 0, 0.08);
        }
        return n.age <= FINISH;
    }

    private static void detonate(Nova n) {
        ServerWorld w = n.world;
        Vec3d c = n.center;
        w.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 4.0f, 0.6f);
        w.playSound(null, c.x, c.y, c.z, SoundEvents.ITEM_TRIDENT_THUNDER, SoundCategory.PLAYERS, 2.0f, 1.2f);
        w.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_PLAYER_HURT_FREEZE, SoundCategory.PLAYERS, 3.0f, 0.6f);
        w.playSound(null, c.x, c.y, c.z, SoundEvents.BLOCK_AMETHYST_BLOCK_BREAK, SoundCategory.PLAYERS, 3.0f, 0.5f);

        // Burst: a flash, a blast of frost flying outward in every direction, and the shell of the sphere
        w.spawnParticles(ParticleTypes.FLASH, c.x, c.y, c.z, 2, 0, 0, 0, 0);
        for (int i = 0; i < 120; i++) {
            Vec3d dir = new Vec3d(w.random.nextGaussian(), w.random.nextGaussian() * 0.5, w.random.nextGaussian()).normalize();
            w.spawnParticles(i % 3 == 0 ? ParticleTypes.END_ROD : ParticleTypes.SNOWFLAKE, c.x, c.y, c.z, 0, dir.x, dir.y, dir.z, 1.2);
        }
        w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, ICE), c.x, c.y, c.z, 80, 1.5, 1.0, 1.5, 0.4);
        drawSphere(w, c, MAX_RADIUS, (int) (MAX_RADIUS * 14));

        PlayerEntity caster = n.casterId == null ? null : w.getPlayerByUuid(n.casterId);
        DamageSource source = new DamageSource(
                w.getRegistryManager().get(RegistryKeys.DAMAGE_TYPE).entryOf(DamageTypes.FREEZE), caster);
        Box box = Box.of(c, MAX_RADIUS * 2, MAX_RADIUS * 2, MAX_RADIUS * 2);
        for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, box,
                e -> e.isAlive() && e.squaredDistanceTo(c) <= MAX_RADIUS * MAX_RADIUS)) {
            if (n.casterId != null && e.getUuid().equals(n.casterId)) continue;

            e.damage(source, DAMAGE);
            e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, FREEZE_TICKS, 255, false, false, true));
            e.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE, FREEZE_TICKS, 255, false, false, true));
            e.setFrozenTicks(240);
            n.frozen.add(new Frozen(e, encase(w, e)));
        }
    }

    // ---------------------------------------------------------------------
    // Crystals and sigil
    // ---------------------------------------------------------------------

    /** Crystals rise out of the ground, grow, and circle faster and faster as the nova builds. */
    private static void moveCrystals(Nova n, float t) {
        for (int i = 0; i < n.crystals.size(); i++) {
            var d = n.crystals.get(i);
            float size = Math.min(1f, t * 1.8f);
            d.setTransformation(crystalTransform(i, n.age, CRYSTAL_ORBIT, size));
            d.setStartInterpolation(0);
            d.setInterpolationDuration(1);
        }
        if (n.age % 3 == 0) {
            for (int i = 0; i < n.crystals.size(); i++) {
                float a = crystalAngle(i, n.age);
                n.world.spawnParticles(ParticleTypes.SNOWFLAKE, n.center.x + Math.cos(a) * CRYSTAL_ORBIT, n.center.y,
                        n.center.z + Math.sin(a) * CRYSTAL_ORBIT, 1, 0.1, 0.3, 0.1, 0.01);
            }
        }
    }

    /** At the burst, the crystals fly outward, shrinking, and break apart. */
    private static void shatterCrystals(Nova n) {
        if (n.crystals.isEmpty()) return;
        int since = n.age - DETONATE;
        if (since == 1) {
            for (int i = 0; i < n.crystals.size(); i++) {
                var d = n.crystals.get(i);
                d.setTransformation(crystalTransform(i, n.age + 4, CRYSTAL_FLY_OUT, 0.2f));
                d.setStartInterpolation(0);
                d.setInterpolationDuration(CRYSTAL_SHATTER_TICKS);
            }
        } else if (since >= CRYSTAL_SHATTER_TICKS) {
            for (int i = 0; i < n.crystals.size(); i++) {
                float a = crystalAngle(i, n.age);
                double x = n.center.x + Math.cos(a) * CRYSTAL_FLY_OUT;
                double z = n.center.z + Math.sin(a) * CRYSTAL_FLY_OUT;
                n.world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.BLUE_ICE.getDefaultState()),
                        x, n.center.y, z, 20, 0.3, 0.3, 0.3, 0.2);
                TempEntities.discard(n.crystals.get(i));
            }
            n.crystals.clear();
            n.world.playSound(null, n.center.x, n.center.y, n.center.z, SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 2.0f, 1.3f);
        }
    }

    private static float crystalAngle(int i, int age) {
        // Spins up as it charges
        float spin = age < DETONATE ? age * age * 0.0035f : DETONATE * DETONATE * 0.0035f;
        return spin + i * MathHelper.TAU / CRYSTALS;
    }

    private static AffineTransformation crystalTransform(int i, int age, float radius, float size) {
        float a = crystalAngle(i, age);
        float rise = -0.8f + Math.min(1f, age / 20f) * 0.8f + MathHelper.sin(age * 0.2f + i) * 0.12f;
        return new AffineTransformation(new Matrix4f()
                .translate((float) Math.cos(a) * radius, rise, (float) Math.sin(a) * radius)
                .rotateY(-a)
                .rotateZ(0.25f)
                .scale(0.38f * size, 1.1f * size, 0.38f * size)
                .translate(-0.5f, -0.5f, -0.5f));
    }

    /** A turning hexagram inside two rings, drawn in frost on the ground under the caster. */
    private static void drawSigil(Nova n, float t) {
        ServerWorld w = n.world;
        double y = n.center.y - 0.95;
        float spin = n.age * 0.03f;
        float r = SIGIL_RADIUS * Math.min(1f, t * 2.5f);
        for (int i = 0; i < 36; i++) {
            double a = i * MathHelper.TAU / 36 + spin;
            w.spawnParticles(FROST, n.center.x + Math.cos(a) * r, y, n.center.z + Math.sin(a) * r, 1, 0, 0, 0, 0);
            if (i % 2 == 0) {
                double a2 = i * MathHelper.TAU / 36 - spin;
                w.spawnParticles(FROST, n.center.x + Math.cos(a2) * r * 0.75, y, n.center.z + Math.sin(a2) * r * 0.75, 1, 0, 0, 0, 0);
            }
        }
        // Two triangles: points 0,2,4 and 1,3,5 of a hexagon, joined with straight lines
        for (int tri = 0; tri < 2; tri++) {
            for (int k = 0; k < 3; k++) {
                double a1 = (tri + k * 2) * MathHelper.TAU / 6 + spin;
                double a2 = (tri + ((k + 1) % 3) * 2) * MathHelper.TAU / 6 + spin;
                Vec3d p1 = new Vec3d(n.center.x + Math.cos(a1) * r, y, n.center.z + Math.sin(a1) * r);
                Vec3d p2 = new Vec3d(n.center.x + Math.cos(a2) * r, y, n.center.z + Math.sin(a2) * r);
                for (int s = 0; s <= 8; s++) {
                    Vec3d p = p1.lerp(p2, s / 8.0);
                    w.spawnParticles(FROST_BRIGHT, p.x, p.y, p.z, 1, 0, 0, 0, 0);
                }
            }
        }
    }

    private static float globeRadius(int step) {
        return MAX_RADIUS * Math.min(step, STEPS) / (float) STEPS;
    }

    /** The glass of the snow globe: a crisp grid of meridians and latitude rings, flashed on each step. */
    private static void drawGlobe(ServerWorld w, Vec3d center, float radius, DustParticleEffect color) {
        double spacing = Math.max(0.6, radius / 14.0);
        int around = Math.max(12, (int) (MathHelper.TAU * radius / spacing));
        // Latitude rings
        for (int lat = -2; lat <= 2; lat++) {
            double phi = lat * Math.PI / 6;                 // -60° .. 60°
            double r = radius * Math.cos(phi);
            double y = center.y + radius * Math.sin(phi);
            int n = Math.max(8, (int) (around * Math.cos(phi)));
            for (int i = 0; i < n; i++) {
                double a = i * MathHelper.TAU / n;
                w.spawnParticles(color, center.x + Math.cos(a) * r, y, center.z + Math.sin(a) * r, 1, 0, 0, 0, 0);
            }
        }
        // Meridians
        for (int m = 0; m < 6; m++) {
            double a = m * Math.PI / 6;
            double cx = Math.cos(a);
            double cz = Math.sin(a);
            for (int i = 0; i < around; i++) {
                double phi = i * MathHelper.TAU / around;
                double h = Math.cos(phi) * radius;
                w.spawnParticles(color, center.x + cx * h, center.y + Math.sin(phi) * radius, center.z + cz * h, 1, 0, 0, 0, 0);
            }
        }
    }

    /** Where the globe meets the ground: a bright ring marking the edge of the area. */
    private static void drawGroundRing(ServerWorld w, Vec3d center, float radius) {
        double y = center.y - 0.9;
        int n = Math.max(12, (int) (MathHelper.TAU * radius / 1.2));
        double offset = w.random.nextDouble();
        for (int i = 0; i < n; i++) {
            double a = (i + offset) * MathHelper.TAU / n;
            w.spawnParticles(ParticleTypes.SNOWFLAKE, center.x + Math.cos(a) * radius, y, center.z + Math.sin(a) * radius, 1, 0, 0.05, 0, 0);
        }
    }

    /** Snow swirling around inside the globe, like a shaken snow globe. */
    private static void snowInside(ServerWorld w, Vec3d center, float radius) {
        int count = 4 + (int) (radius * 0.8);
        for (int i = 0; i < count; i++) {
            Vec3d dir = new Vec3d(w.random.nextGaussian(), w.random.nextGaussian(), w.random.nextGaussian()).normalize();
            Vec3d p = center.add(dir.multiply(radius * Math.cbrt(w.random.nextDouble())));
            w.spawnParticles(ParticleTypes.SNOWFLAKE, p.x, p.y, p.z, 1, 0.1, 0.1, 0.1, 0.02);
        }
    }

    private static void drawSphere(ServerWorld w, Vec3d center, float radius, int count) {
        for (int i = 0; i < count; i++) {
            double theta = w.random.nextDouble() * 2 * Math.PI;
            double phi = Math.acos(2 * w.random.nextDouble() - 1);
            double x = center.x + radius * Math.sin(phi) * Math.cos(theta);
            double y = center.y + radius * Math.cos(phi);
            double z = center.z + radius * Math.sin(phi) * Math.sin(theta);
            w.spawnParticles(ParticleTypes.SNOWFLAKE, x, y, z, 1, 0, 0, 0, 0);
        }
    }

    // ---------------------------------------------------------------------
    // Victims
    // ---------------------------------------------------------------------

    /** A shell of ice around the creature, sized to its hitbox. */
    private static DisplayEntity.BlockDisplayEntity encase(ServerWorld w, LivingEntity e) {
        DisplayEntity.BlockDisplayEntity d = EntityType.BLOCK_DISPLAY.create(w);
        if (d == null) return null;
        float width = e.getWidth() + 0.35f;
        float height = e.getHeight() + 0.25f;
        d.setBlockState(ICE);
        d.setTeleportDuration(1);
        d.setViewRange(2.0f);
        d.refreshPositionAndAngles(e.getX(), e.getY(), e.getZ(), 0f, 0f);
        d.setTransformation(new AffineTransformation(new Matrix4f()
                .translate(0, -0.1f, 0)
                .scale(width, height, width)
                .translate(-0.5f, 0, -0.5f)));
        TempEntities.track(d);
        w.spawnEntity(d);
        return d;
    }

    private static void tickFrozen(Nova n) {
        ServerWorld w = n.world;
        var iceParticle = new BlockStateParticleEffect(ParticleTypes.BLOCK, ICE);
        boolean thawing = n.age >= FINISH;
        Iterator<Frozen> it = n.frozen.iterator();
        while (it.hasNext()) {
            Frozen f = it.next();
            LivingEntity e = f.entity;
            if (!e.isAlive() || e.isRemoved() || e.getWorld() != w || thawing) {
                // The shell cracks apart when they thaw (or die)
                if (f.shell != null) {
                    Vec3d p = f.shell.getPos();
                    w.spawnParticles(iceParticle, p.x, p.y + e.getHeight() / 2, p.z, 30, e.getWidth() / 2 + 0.2, e.getHeight() / 2, e.getWidth() / 2 + 0.2, 0.15);
                    w.playSound(null, p.x, p.y, p.z, SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 0.8f, 1.2f + w.random.nextFloat() * 0.3f);
                    TempEntities.discard(f.shell);
                }
                it.remove();
                continue;
            }
            if (f.shell != null) {
                f.shell.setPosition(e.getX(), e.getY(), e.getZ());
            }
            // A little ice crumbling off them
            if (w.random.nextInt(3) == 0) {
                w.spawnParticles(iceParticle, e.getX(), e.getBodyY(0.5), e.getZ(), 1, e.getWidth() / 2, e.getHeight() / 2, e.getWidth() / 2, 0.02);
            }
        }
    }
}
