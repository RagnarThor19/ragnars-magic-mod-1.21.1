package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.Brightness;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.util.TempEntities;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Calls down a burning meteor on the spot under the crosshair. A ring of fire marks the blast zone while a
 * tumbling chunk of magma and blackstone streaks in from the sky at an angle, trailing smoke. On impact it
 * explodes, throws a shockwave that scorches and flings everything nearby, and sprays glowing debris.
 */
public final class MeteorSpell implements Spell {
    private static final double RANGE = 100.0;
    private static final int FALL_TICKS = 60;              // 3s from the sky to the ground
    private static final double START_HEIGHT = 70.0;
    private static final double START_BACK = 35.0;         // comes in at an angle, from behind the caster
    private static final float EXPLOSION_POWER = 8.0f;
    private static final double BLAST_RADIUS = 9.0;        // marked on the ground; full shockwave damage inside
    private static final double SHOCK_RADIUS = 16.0;       // knockback reaches this far
    private static final float SHOCK_DAMAGE = 12.0f;
    private static final int BURN_SECONDS = 8;
    private static final int RING_TICKS = 14;              // how long the shockwave ring takes to spread
    private static final int DEBRIS_COUNT = 14;
    private static final int DEBRIS_LIFE = 40;
    private static final float DEBRIS_SIZE = 0.6f;

    private static final class Chunk {
        final DisplayEntity.BlockDisplayEntity display;
        final Vector3f offset;
        final float size;
        final Vector3f spin;

        Chunk(DisplayEntity.BlockDisplayEntity display, Vector3f offset, float size, Vector3f spin) {
            this.display = display;
            this.offset = offset;
            this.size = size;
            this.spin = spin;
        }
    }

    private static final class Debris {
        final DisplayEntity.BlockDisplayEntity display;
        Vec3d pos;
        Vec3d vel;
        int age = 0;

        Debris(DisplayEntity.BlockDisplayEntity display, Vec3d pos, Vec3d vel) {
            this.display = display;
            this.pos = pos;
            this.vel = vel;
        }
    }

    private static final class Strike {
        final ServerWorld world;
        final UUID owner;
        final Vec3d target;
        final Vec3d start;
        final List<Chunk> chunks = new ArrayList<>();
        final List<Debris> debris = new ArrayList<>();
        Vec3d pos;
        int age = 0;
        int impactAge = -1;

        Strike(ServerWorld world, UUID owner, Vec3d target, Vec3d start) {
            this.world = world;
            this.owner = owner;
            this.target = target;
            this.start = start;
            this.pos = start;
        }
    }

    private static final List<Strike> ACTIVE = new ArrayList<>();
    private static boolean registered = false;

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            Iterator<Strike> it = ACTIVE.iterator();
            while (it.hasNext()) {
                Strike s = it.next();
                if (s.world == world && !tick(s)) {
                    for (Chunk c : s.chunks) TempEntities.discard(c.display);
                    for (Debris d : s.debris) TempEntities.discard(d.display);
                    it.remove();
                }
            }
        });
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        ensureRegistered();
        ServerWorld sw = (ServerWorld) world;

        Vec3d eye = player.getEyePos();
        BlockHitResult hit = sw.raycast(new RaycastContext(eye, eye.add(player.getRotationVector().multiply(RANGE)),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.ANY, player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            player.sendMessage(Text.literal("The sky can't find that spot."), true);
            return false;
        }
        Vec3d target = hit.getPos();

        Vec3d flat = new Vec3d(target.x - player.getX(), 0, target.z - player.getZ());
        flat = flat.lengthSquared() > 1.0e-4 ? flat.normalize() : Vec3d.fromPolar(0, player.getYaw());
        double startY = Math.min(target.y + START_HEIGHT, sw.getTopY() + 20);
        Vec3d start = new Vec3d(target.x - flat.x * START_BACK, startY, target.z - flat.z * START_BACK);

        Strike strike = new Strike(sw, player.getUuid(), target, start);
        buildMeteor(strike);
        ACTIVE.add(strike);

        var horn = SoundEvents.GOAT_HORN_SOUNDS.get(7).value();
        sw.playSound(null, player.getBlockPos(), horn, SoundCategory.PLAYERS, 1.5f, 0.55f);
        sw.playSound(null, target.x, target.y, target.z, SoundEvents.ENTITY_GOAT_SCREAMING_AMBIENT, SoundCategory.PLAYERS, 2.2f, 0.9f);
        return true;
    }

    // ---------------------------------------------------------------------
    // The meteor
    // ---------------------------------------------------------------------

    private static void buildMeteor(Strike s) {
        var rnd = s.world.random;
        addChunk(s, Blocks.MAGMA_BLOCK.getDefaultState(), new Vector3f(), 3.2f);
        BlockState[] rocks = {Blocks.BLACKSTONE.getDefaultState(), Blocks.NETHERRACK.getDefaultState(),
                Blocks.BASALT.getDefaultState(), Blocks.MAGMA_BLOCK.getDefaultState()};
        for (int i = 0; i < 7; i++) {
            Vector3f dir = new Vector3f(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f).normalize();
            addChunk(s, rocks[i % rocks.length], dir.mul(1.3f + rnd.nextFloat() * 0.4f), 1.4f + rnd.nextFloat() * 0.8f);
        }
    }

    private static void addChunk(Strike s, BlockState state, Vector3f offset, float size) {
        DisplayEntity.BlockDisplayEntity d = EntityType.BLOCK_DISPLAY.create(s.world);
        if (d == null) return;
        var rnd = s.world.random;
        Vector3f spin = new Vector3f(rnd.nextFloat() * 0.3f - 0.15f, rnd.nextFloat() * 0.3f - 0.15f, rnd.nextFloat() * 0.3f - 0.15f);
        Chunk c = new Chunk(d, offset, size, spin);
        d.setBlockState(state);
        d.setBrightness(Brightness.FULL);
        d.setTeleportDuration(1);
        d.setViewRange(8.0f);
        d.refreshPositionAndAngles(s.start.x, s.start.y, s.start.z, 0f, 0f);
        d.setTransformation(chunkTransform(c, 0));
        TempEntities.track(d);
        s.world.spawnEntity(d);
        s.chunks.add(c);
    }

    private static AffineTransformation chunkTransform(Chunk c, int age) {
        return new AffineTransformation(new Matrix4f()
                .rotateXYZ(age * 0.12f, age * 0.07f, age * 0.05f)
                .translate(c.offset)
                .rotateXYZ(age * c.spin.x, age * c.spin.y, age * c.spin.z)
                .scale(c.size)
                .translate(-0.5f, -0.5f, -0.5f));
    }

    // ---------------------------------------------------------------------
    // Timeline
    // ---------------------------------------------------------------------

    /** Returns false once everything has settled. */
    private static boolean tick(Strike s) {
        s.age++;
        if (s.impactAge < 0) {
            fall(s);
            return true;
        }
        int since = s.age - s.impactAge;
        if (since <= RING_TICKS) shockRing(s, since);
        tickDebris(s);
        return since < DEBRIS_LIFE || !s.debris.isEmpty();
    }

    private static void fall(Strike s) {
        ServerWorld world = s.world;
        // Speeds up as it comes in
        double t = Math.min(1.0, s.age / (double) FALL_TICKS);
        double eased = t * t;
        Vec3d prev = s.pos;
        s.pos = s.start.lerp(s.target, eased);

        for (Chunk c : s.chunks) {
            c.display.setPosition(s.pos.x, s.pos.y, s.pos.z);
            c.display.setTransformation(chunkTransform(c, s.age));
            c.display.setStartInterpolation(0);
            c.display.setInterpolationDuration(1);
        }

        // Trail: fire around the rock, a long smoke plume behind it
        Vec3d p = s.pos;
        Vec3d back = prev.subtract(p);
        world.spawnParticles(ParticleTypes.FLAME, p.x, p.y, p.z, 25, 1.4, 1.4, 1.4, 0.05);
        world.spawnParticles(ParticleTypes.LAVA, p.x, p.y, p.z, 3, 1.0, 1.0, 1.0, 0);
        for (int i = 0; i < 4; i++) {
            Vec3d q = p.add(back.multiply(i / 4.0));
            world.spawnParticles(ParticleTypes.LARGE_SMOKE, q.x, q.y, q.z, 4, 0.8, 0.8, 0.8, 0.02);
        }
        if (s.age % 3 == 0) world.spawnParticles(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, p.x, p.y, p.z, 2, 0.6, 0.6, 0.6, 0.01);

        // The blast zone burns on the ground, tightening as it gets close
        Vec3d g = s.target;
        int points = 36;
        for (int i = 0; i < points; i++) {
            double a = i * Math.PI * 2 / points + s.age * 0.02;
            world.spawnParticles(ParticleTypes.FLAME, g.x + Math.cos(a) * BLAST_RADIUS, g.y + 0.1, g.z + Math.sin(a) * BLAST_RADIUS, 1, 0, 0.02, 0, 0);
        }
        double inner = BLAST_RADIUS * (1.0 - t);
        for (int i = 0; i < 16; i++) {
            double a = i * Math.PI * 2 / 16 - s.age * 0.05;
            world.spawnParticles(ParticleTypes.SMALL_FLAME, g.x + Math.cos(a) * inner, g.y + 0.1, g.z + Math.sin(a) * inner, 1, 0, 0, 0, 0);
        }

        if (s.age % 6 == 0) world.playSound(null, p.x, p.y, p.z, SoundEvents.ENTITY_BLAZE_SHOOT, SoundCategory.PLAYERS, 3.0f, 0.4f);
        if (s.age == FALL_TICKS - 20) world.playSound(null, g.x, g.y, g.z, SoundEvents.ITEM_FIRECHARGE_USE, SoundCategory.PLAYERS, 3.0f, 0.5f);

        if (s.age >= FALL_TICKS) impact(s);
    }

    private static void impact(Strike s) {
        ServerWorld world = s.world;
        Vec3d g = s.target;
        s.impactAge = s.age;
        for (Chunk c : s.chunks) TempEntities.discard(c.display);
        s.chunks.clear();

        PlayerEntity owner = world.getPlayerByUuid(s.owner);
        // Mob-type explosion, so the mobGriefing rule decides whether it breaks blocks
        world.createExplosion(owner, g.x, g.y, g.z, EXPLOSION_POWER, true, World.ExplosionSourceType.MOB);

        // Shockwave: scorch and fling everything around the crater
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, new Box(g, g).expand(SHOCK_RADIUS),
                e -> e.isAlive() && !e.isSpectator())) {
            Vec3d to = e.getPos().subtract(g);
            double dist = to.length();
            if (dist > SHOCK_RADIUS) continue;
            double falloff = 1.0 - dist / SHOCK_RADIUS;
            Vec3d out = new Vec3d(to.x, 0, to.z);
            out = out.lengthSquared() > 1.0e-4 ? out.normalize() : Vec3d.ZERO;
            e.addVelocity(out.x * 2.0 * falloff, 0.4 + 0.8 * falloff, out.z * 2.0 * falloff);
            e.velocityModified = true;
            if (dist <= BLAST_RADIUS && !e.getUuid().equals(s.owner)) {
                e.damage(owner != null ? world.getDamageSources().indirectMagic(owner, owner) : world.getDamageSources().inFire(),
                        (float) (SHOCK_DAMAGE * (0.5 + 0.5 * falloff)));
                e.setOnFireFor(BURN_SECONDS);
            }
        }

        world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, g.x, g.y + 1, g.z, 4, 2.5, 1.0, 2.5, 0);
        world.spawnParticles(ParticleTypes.FLAME, g.x, g.y + 1, g.z, 300, 1.5, 1.0, 1.5, 0.6);
        world.spawnParticles(ParticleTypes.LAVA, g.x, g.y + 1, g.z, 80, 3.0, 1.0, 3.0, 0);
        world.spawnParticles(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, g.x, g.y + 1, g.z, 40, 2.5, 1.0, 2.5, 0.03);
        world.spawnParticles(ParticleTypes.LARGE_SMOKE, g.x, g.y + 1, g.z, 150, 4.0, 2.0, 4.0, 0.1);
        world.playSound(null, g.x, g.y, g.z, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 8.0f, 0.5f);
        world.playSound(null, g.x, g.y, g.z, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.PLAYERS, 8.0f, 0.5f);
        world.playSound(null, g.x, g.y, g.z, SoundEvents.ENTITY_DRAGON_FIREBALL_EXPLODE, SoundCategory.PLAYERS, 6.0f, 0.6f);

        spawnDebris(s);
    }

    /** A ring of fire and dust racing outward from the crater. */
    private static void shockRing(Strike s, int since) {
        Vec3d g = s.target;
        double r = SHOCK_RADIUS * since / (double) RING_TICKS;
        int points = (int) (r * 5) + 12;
        for (int i = 0; i < points; i++) {
            double a = i * Math.PI * 2 / points;
            double x = g.x + Math.cos(a) * r;
            double z = g.z + Math.sin(a) * r;
            s.world.spawnParticles(since % 2 == 0 ? ParticleTypes.FLAME : ParticleTypes.CLOUD, x, g.y + 0.3, z, 1, 0.1, 0.05, 0.1, 0.01);
        }
    }

    // ---------------------------------------------------------------------
    // Debris
    // ---------------------------------------------------------------------

    private static void spawnDebris(Strike s) {
        var rnd = s.world.random;
        BlockState[] rocks = {Blocks.MAGMA_BLOCK.getDefaultState(), Blocks.BLACKSTONE.getDefaultState(), Blocks.NETHERRACK.getDefaultState()};
        for (int i = 0; i < DEBRIS_COUNT; i++) {
            DisplayEntity.BlockDisplayEntity d = EntityType.BLOCK_DISPLAY.create(s.world);
            if (d == null) continue;
            double a = rnd.nextDouble() * Math.PI * 2;
            double speed = 0.5 + rnd.nextDouble() * 0.6;
            Vec3d vel = new Vec3d(Math.cos(a) * speed, 0.7 + rnd.nextDouble() * 0.7, Math.sin(a) * speed);
            Vec3d pos = s.target.add(0, 1.0, 0);
            d.setBlockState(rocks[i % rocks.length]);
            d.setBrightness(Brightness.FULL);
            d.setTeleportDuration(1);
            d.setViewRange(4.0f);
            d.refreshPositionAndAngles(pos.x, pos.y, pos.z, 0f, 0f);
            d.setTransformation(new AffineTransformation(new Matrix4f().scale(DEBRIS_SIZE).translate(-0.5f, -0.5f, -0.5f)));
            TempEntities.track(d);
            s.world.spawnEntity(d);
            s.debris.add(new Debris(d, pos, vel));
        }
    }

    private static void tickDebris(Strike s) {
        Iterator<Debris> it = s.debris.iterator();
        while (it.hasNext()) {
            Debris d = it.next();
            d.age++;
            Vec3d next = d.pos.add(d.vel);
            BlockHitResult hit = s.world.raycast(new RaycastContext(d.pos, next, RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.ANY, net.minecraft.block.ShapeContext.absent()));
            if (hit.getType() == HitResult.Type.BLOCK || d.age > DEBRIS_LIFE) {
                Vec3d at = hit.getType() == HitResult.Type.BLOCK ? hit.getPos() : d.pos;
                s.world.spawnParticles(ParticleTypes.LAVA, at.x, at.y, at.z, 3, 0.2, 0.1, 0.2, 0);
                s.world.spawnParticles(ParticleTypes.SMOKE, at.x, at.y, at.z, 6, 0.2, 0.1, 0.2, 0.02);
                TempEntities.discard(d.display);
                it.remove();
                continue;
            }
            d.pos = next;
            d.vel = d.vel.multiply(0.98).add(0, -0.08, 0);
            d.display.setPosition(next.x, next.y, next.z);
            float spin = d.age * 0.4f;
            d.display.setTransformation(new AffineTransformation(new Matrix4f()
                    .rotateXYZ(spin, spin * 0.7f, 0).scale(DEBRIS_SIZE).translate(-0.5f, -0.5f, -0.5f)));
            d.display.setStartInterpolation(0);
            d.display.setInterpolationDuration(1);
            s.world.spawnParticles(ParticleTypes.FLAME, next.x, next.y, next.z, 1, 0.05, 0.05, 0.05, 0);
            if (d.age % 2 == 0) s.world.spawnParticles(ParticleTypes.SMOKE, next.x, next.y, next.z, 1, 0.05, 0.05, 0.05, 0);
        }
    }
}
