package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.PointedDripstoneBlock;
import net.minecraft.block.enums.Thickness;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.util.Aim;
import net.ragnar.ragnarsmagicmod.util.TempEntities;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Marks a spot on the ground under the crosshair (or under the creature you aim at). After a short rumble a
 * huge dripstone spike bursts out of it, ringed by smaller spikes: whatever stands on the spot is impaled and
 * thrown high into the air, and anything close by is gored and knocked away. The spike is made of display
 * entities, so it never touches the world's blocks, and sinks back into the ground after a few seconds.
 */
public class ImpalingSpell implements Spell {
    private static final double RANGE = 40.0;
    private static final int GROUND_SEARCH = 8;          // how far below the aim point we look for ground

    private static final int WARNING_TICKS = 12;         // rumble before the eruption, enough to dodge
    private static final int RISE_TICKS = 3;
    private static final int STAND_TICKS = 60;
    private static final int SINK_TICKS = 10;

    // The main spike: tapering dripstone segments topped by a pointed tip
    private static final int MAIN_SEGMENTS = 5;
    private static final float MAIN_WIDTH = 2.4f;
    private static final float SEGMENT_HEIGHT = 1.2f;
    private static final float TIP_WIDTH = 1.8f;
    private static final float TIP_HEIGHT = 2.4f;
    private static final float MAIN_HEIGHT = MAIN_SEGMENTS * SEGMENT_HEIGHT + TIP_HEIGHT;

    // Smaller spikes around it, leaning outward
    private static final int RING_SPIKES = 6;
    private static final double RING_RADIUS = 2.6;
    private static final float RING_TILT = (float) Math.toRadians(22);

    private static final double CORE_RADIUS = 1.5;
    private static final float CORE_DAMAGE = 18.0f;
    private static final double CORE_LAUNCH = 1.5;
    private static final double OUTER_RADIUS = 3.8;
    private static final float OUTER_DAMAGE = 8.0f;

    private static final BlockState DRIPSTONE = Blocks.DRIPSTONE_BLOCK.getDefaultState();
    private static final BlockState TIP = Blocks.POINTED_DRIPSTONE.getDefaultState()
            .with(PointedDripstoneBlock.VERTICAL_DIRECTION, Direction.UP)
            .with(PointedDripstoneBlock.THICKNESS, Thickness.TIP);

    private static final class Segment {
        final DisplayEntity.BlockDisplayEntity display;
        final AffineTransformation raised;
        final AffineTransformation buried;

        Segment(DisplayEntity.BlockDisplayEntity display, AffineTransformation raised, AffineTransformation buried) {
            this.display = display;
            this.raised = raised;
            this.buried = buried;
        }
    }

    private static final class Eruption {
        final ServerWorld world;
        final UUID owner;
        final Vec3d base;          // centre of the ground surface the spike comes out of
        final BlockState ground;
        final List<Segment> segments = new ArrayList<>();
        int age = 0;

        Eruption(ServerWorld world, UUID owner, Vec3d base, BlockState ground) {
            this.world = world;
            this.owner = owner;
            this.base = base;
            this.ground = ground;
        }
    }

    private static final List<Eruption> ACTIVE = new ArrayList<>();
    private static boolean registered = false;

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            Iterator<Eruption> it = ACTIVE.iterator();
            while (it.hasNext()) {
                Eruption e = it.next();
                if (e.world == world && !tick(e)) {
                    for (Segment s : e.segments) TempEntities.discard(s.display);
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

        Vec3d aim = aimPoint(sw, player);
        Vec3d base = aim == null ? null : findGround(sw, aim);
        if (base == null) {
            player.sendMessage(Text.literal("No ground to erupt from."), true);
            return false;
        }

        BlockState ground = sw.getBlockState(BlockPos.ofFloored(base).down());
        ACTIVE.add(new Eruption(sw, player.getUuid(), base, ground));
        sw.playSound(null, base.x, base.y, base.z, SoundEvents.BLOCK_DEEPSLATE_BREAK, SoundCategory.PLAYERS, 1.5f, 0.5f);
        sw.playSound(null, base.x, base.y, base.z, SoundEvents.ENTITY_VEX_AMBIENT, SoundCategory.PLAYERS, 1.5f, 0.5f);
        return true;
    }

    // ---------------------------------------------------------------------
    // Targeting
    // ---------------------------------------------------------------------

    /** The creature under the crosshair if there is one, otherwise the block under it. */
    private static Vec3d aimPoint(ServerWorld world, PlayerEntity player) {
        Entity target = Aim.target(world, player, RANGE, 4.0, e -> e instanceof LivingEntity);
        if (target != null) return target.getPos().add(0, 0.5, 0);
        Vec3d eye = player.getEyePos();
        BlockHitResult hit = world.raycast(new RaycastContext(eye, eye.add(player.getRotationVector().multiply(RANGE)),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) return null;
        // Step back out of the block we hit, so walls and floors both work
        return hit.getPos().add(Vec3d.of(hit.getSide().getVector()).multiply(0.5));
    }

    /** The top of the first solid block at or below {@code from}, keeping its exact x/z. */
    private static Vec3d findGround(ServerWorld world, Vec3d from) {
        BlockPos start = BlockPos.ofFloored(from);
        for (int dy = 0; dy <= GROUND_SEARCH; dy++) {
            BlockPos pos = start.down(dy);
            BlockState state = world.getBlockState(pos);
            if (!state.getFluidState().isEmpty()) continue;
            if (state.isSideSolidFullSquare(world, pos, Direction.UP)
                    && world.getBlockState(pos.up()).getCollisionShape(world, pos.up()).isEmpty()) {
                return new Vec3d(from.x, pos.getY() + 1.0, from.z);
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // Timeline
    // ---------------------------------------------------------------------

    /** Returns false once the spike has sunk away. */
    private static boolean tick(Eruption e) {
        e.age++;
        ServerWorld world = e.world;
        Vec3d b = e.base;
        int erupt = WARNING_TICKS;
        int sink = erupt + 1 + RISE_TICKS + STAND_TICKS;

        if (e.age < erupt) {
            // The ground cracks and shakes; a ring tightens on the spot
            var debris = new BlockStateParticleEffect(ParticleTypes.BLOCK, e.ground);
            world.spawnParticles(debris, b.x, b.y + 0.05, b.z, 6, 0.8, 0.02, 0.8, 0.1);
            double r = 2.5 * (1.0 - e.age / (double) erupt) + 0.4;
            for (int i = 0; i < 10; i++) {
                double a = i * Math.PI * 2 / 10 + e.age * 0.3;
                world.spawnParticles(ParticleTypes.CRIT, b.x + Math.cos(a) * r, b.y + 0.1, b.z + Math.sin(a) * r, 1, 0, 0, 0, 0);
            }
            if (e.age % 4 == 0) {
                world.playSound(null, b.x, b.y, b.z, SoundEvents.BLOCK_POINTED_DRIPSTONE_BREAK, SoundCategory.PLAYERS, 1.0f, 0.5f + e.age * 0.03f);
            }
            return true;
        }
        if (e.age == erupt) {
            buildSpike(e);
            strike(e);
            var debris = new BlockStateParticleEffect(ParticleTypes.BLOCK, e.ground);
            world.spawnParticles(debris, b.x, b.y + 0.3, b.z, 80, 1.5, 0.4, 1.5, 0.3);
            world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, DRIPSTONE), b.x, b.y + 2, b.z, 40, 0.8, 2.0, 0.8, 0.2);
            world.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, b.x, b.y + 0.3, b.z, 12, 1.2, 0.2, 1.2, 0.03);
            world.spawnParticles(ParticleTypes.EXPLOSION, b.x, b.y + 0.5, b.z, 2, 0.6, 0.2, 0.6, 0);
            world.playSound(null, b.x, b.y, b.z, SoundEvents.BLOCK_POINTED_DRIPSTONE_LAND, SoundCategory.PLAYERS, 2.0f, 0.5f);
            world.playSound(null, b.x, b.y, b.z, SoundEvents.ENTITY_EVOKER_FANGS_ATTACK, SoundCategory.PLAYERS, 2.0f, 0.6f);
            world.playSound(null, b.x, b.y, b.z, SoundEvents.BLOCK_DEEPSLATE_BREAK, SoundCategory.PLAYERS, 2.0f, 0.4f);
            return true;
        }
        if (e.age == erupt + 1) {
            // Shoot up out of the ground
            for (Segment s : e.segments) animate(s.display, s.raised, RISE_TICKS);
            return true;
        }
        if (e.age == sink) {
            for (Segment s : e.segments) animate(s.display, s.buried, SINK_TICKS);
            world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, DRIPSTONE), b.x, b.y + 0.3, b.z, 40, 1.2, 0.3, 1.2, 0.1);
            world.playSound(null, b.x, b.y, b.z, SoundEvents.BLOCK_DRIPSTONE_BLOCK_BREAK, SoundCategory.PLAYERS, 1.5f, 0.6f);
            return true;
        }
        return e.age < sink + SINK_TICKS;
    }

    private static void animate(DisplayEntity.BlockDisplayEntity d, AffineTransformation to, int ticks) {
        d.setTransformation(to);
        d.setStartInterpolation(0);
        d.setInterpolationDuration(ticks);
    }

    // ---------------------------------------------------------------------
    // The spike
    // ---------------------------------------------------------------------

    private static void buildSpike(Eruption e) {
        // Main spike: each segment narrower than the last and twisted a little for a jagged look
        float y = 0;
        for (int i = 0; i < MAIN_SEGMENTS; i++) {
            float w = MAIN_WIDTH * (1f - i / (float) (MAIN_SEGMENTS + 1));
            addSegment(e, e.base, DRIPSTONE, y, w, SEGMENT_HEIGHT, i * 0.3f, 0, 0, 0, MAIN_HEIGHT);
            y += SEGMENT_HEIGHT;
        }
        addSegment(e, e.base, TIP, y, TIP_WIDTH, TIP_HEIGHT, 0.4f, 0, 0, 0, MAIN_HEIGHT);

        // A crown of smaller spikes leaning away from the centre
        for (int i = 0; i < RING_SPIKES; i++) {
            double a = i * Math.PI * 2 / RING_SPIKES + e.world.random.nextDouble() * 0.4;
            Vec3d spot = findGround(e.world, e.base.add(Math.cos(a) * RING_RADIUS, 1.5, Math.sin(a) * RING_RADIUS));
            if (spot == null) continue;
            float scale = 0.8f + e.world.random.nextFloat() * 0.4f;
            // Tilt around the axis that swings "up" toward the outward direction
            float ax = (float) Math.sin(a);
            float az = (float) -Math.cos(a);
            addSegment(e, spot, DRIPSTONE, 0, 1.0f * scale, 1.1f * scale, (float) a, RING_TILT, ax, az, 3.5f);
            addSegment(e, spot, TIP, 1.1f * scale, 1.2f * scale, 1.6f * scale, (float) a, RING_TILT, ax, az, 3.5f);
        }
    }

    /**
     * One block display at {@code at}: a block scaled to {@code width} x {@code height}, sitting {@code y} above
     * the ground, twisted by {@code twist} and leaning by {@code tilt} around (ax, 0, az). Its buried pose is the
     * same shape sunk {@code sink} blocks into the ground.
     */
    private static void addSegment(Eruption e, Vec3d at, BlockState state, float y, float width, float height,
                                   float twist, float tilt, float ax, float az, float sink) {
        DisplayEntity.BlockDisplayEntity d = EntityType.BLOCK_DISPLAY.create(e.world);
        if (d == null) return;
        AffineTransformation raised = segmentTransform(y, width, height, twist, tilt, ax, az, 0);
        AffineTransformation buried = segmentTransform(y, width, height, twist, tilt, ax, az, -(sink + 0.5f));
        d.setBlockState(state);
        d.setViewRange(3.0f);
        d.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
        d.setTransformation(buried);
        TempEntities.track(d);
        e.world.spawnEntity(d);
        e.segments.add(new Segment(d, raised, buried));
    }

    private static AffineTransformation segmentTransform(float y, float width, float height, float twist,
                                                         float tilt, float ax, float az, float offsetY) {
        Matrix4f m = new Matrix4f().translate(0, offsetY, 0);
        if (tilt != 0) m.rotate(tilt, ax, 0, az);
        m.translate(0, y, 0)
                .rotateY(twist)
                .scale(width, height, width)
                .translate(-0.5f, 0, -0.5f);
        return new AffineTransformation(m);
    }

    // ---------------------------------------------------------------------
    // Damage
    // ---------------------------------------------------------------------

    private static void strike(Eruption e) {
        ServerWorld world = e.world;
        Vec3d b = e.base;
        PlayerEntity owner = world.getPlayerByUuid(e.owner);
        var box = new net.minecraft.util.math.Box(b.x - OUTER_RADIUS, b.y - 1, b.z - OUTER_RADIUS,
                b.x + OUTER_RADIUS, b.y + MAIN_HEIGHT, b.z + OUTER_RADIUS);

        for (LivingEntity le : world.getEntitiesByClass(LivingEntity.class, box,
                le -> le.isAlive() && !le.isSpectator() && !le.getUuid().equals(e.owner))) {
            double dx = le.getX() - b.x;
            double dz = le.getZ() - b.z;
            double dist = Math.sqrt(dx * dx + dz * dz);
            double outX = dist > 1.0e-3 ? dx / dist : 0;
            double outZ = dist > 1.0e-3 ? dz / dist : 0;
            var source = owner != null ? world.getDamageSources().indirectMagic(owner, owner) : world.getDamageSources().stalagmite();

            if (dist <= CORE_RADIUS) {
                // Right on top of it: impaled and thrown skyward
                le.damage(source, CORE_DAMAGE);
                le.setVelocity(outX * 0.2, CORE_LAUNCH, outZ * 0.2);
            } else if (dist <= OUTER_RADIUS && le.getY() < b.y + 3.5) {
                le.damage(source, OUTER_DAMAGE);
                double push = MathHelper.lerp((dist - CORE_RADIUS) / (OUTER_RADIUS - CORE_RADIUS), 1.1, 0.6);
                le.setVelocity(outX * push, 0.6, outZ * push);
            } else {
                continue;
            }
            le.velocityModified = true;
            Vec3d c = le.getBoundingBox().getCenter();
            world.spawnParticles(ParticleTypes.DAMAGE_INDICATOR, c.x, c.y, c.z, 6, 0.3, 0.3, 0.3, 0.1);
            world.playSound(null, c.x, c.y, c.z, SoundEvents.ITEM_TRIDENT_HIT, SoundCategory.PLAYERS, 1.2f, 0.6f);
        }
    }
}
