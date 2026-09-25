package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.PointedDripstoneBlock;
import net.minecraft.block.enums.Thickness;
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
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.util.TempEntities;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Sends a wave of dripstone spikes bursting out of the ground in front of you, three lanes wide. Each spike that
 * comes up under a creature hurts it and tosses it into the air. The middle lane rises tallest and the outer lanes
 * lean outward, so the wave reads as a jagged wedge tearing across the ground. The spikes are display entities:
 * they never place or break real blocks, and sink away on their own.
 */
public final class RisingSpikesSpell implements Spell {
    // Wave tuning (same wave as before)
    private static final int LENGTH_BLOCKS = 16;         // how far forward the wave travels
    private static final int STEP_DELAY_T = 2;           // ticks between segments
    private static final int LIFETIME_T = 15;            // how long each spike stands
    private static final int[] LANES = {-1, 0, 1};      // left, centre, right
    private static final double START_UP = 2.5;          // search for ground from this far above the caster's feet

    // Hits (same numbers as before)
    private static final float DAMAGE = 12.0f;
    private static final double LIFT = 1.3;
    private static final double FORWARD_PUSH = 0.35;
    private static final double HIT_HALF_WIDTH = 0.85;

    // Looks
    private static final int RISE_T = 2;
    private static final int SINK_T = 5;
    private static final float CENTER_HEIGHT = 2.4f;
    private static final float SIDE_HEIGHT = 1.8f;
    private static final float SIDE_LEAN = (float) Math.toRadians(16);

    private static final BlockState ROCK = Blocks.DRIPSTONE_BLOCK.getDefaultState();
    private static final BlockState TIP = Blocks.POINTED_DRIPSTONE.getDefaultState()
            .with(PointedDripstoneBlock.VERTICAL_DIRECTION, net.minecraft.util.math.Direction.UP)
            .with(PointedDripstoneBlock.THICKNESS, Thickness.TIP);

    private static final class Pending {
        final double x, z, startY;
        final int lane;
        int delay;

        Pending(double x, double z, double startY, int lane, int delay) {
            this.x = x;
            this.z = z;
            this.startY = startY;
            this.lane = lane;
            this.delay = delay;
        }
    }

    private static final class Spike {
        final List<DisplayEntity.BlockDisplayEntity> parts = new ArrayList<>();
        final List<AffineTransformation> raised = new ArrayList<>();
        final List<AffineTransformation> buried = new ArrayList<>();
        int age = 0;
    }

    private static final class Wave {
        final ServerWorld world;
        final UUID owner;
        final Vec3d dir;         // flat forward
        final Vec3d left;
        final List<Pending> pending = new ArrayList<>();
        final List<Spike> spikes = new ArrayList<>();

        Wave(ServerWorld world, UUID owner, Vec3d dir) {
            this.world = world;
            this.owner = owner;
            this.dir = dir;
            this.left = new Vec3d(-dir.z, 0, dir.x);
        }
    }

    private static final List<Wave> ACTIVE = new ArrayList<>();
    private static boolean registered = false;

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            Iterator<Wave> it = ACTIVE.iterator();
            while (it.hasNext()) {
                Wave w = it.next();
                if (w.world == world && !tick(w)) {
                    for (Spike s : w.spikes) for (var d : s.parts) TempEntities.discard(d);
                    it.remove();
                }
            }
        });
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        Vec3d look = player.getRotationVec(1.0f);
        Vec3d flat = new Vec3d(look.x, 0, look.z);
        if (flat.lengthSquared() < 1.0e-6) return false;   // looking straight up or down
        ensureRegistered();
        ServerWorld sw = (ServerWorld) world;

        // Cast cue (low rumble + rock)
        sw.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_STONE_PLACE, SoundCategory.PLAYERS, 0.9f, 0.9f);
        sw.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_EVOKER_PREPARE_SUMMON, SoundCategory.PLAYERS, 0.5f, 0.7f);
        sw.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_WARDEN_SONIC_CHARGE, SoundCategory.PLAYERS, 1.0f, 0.7f);

        Wave wave = new Wave(sw, player.getUuid(), flat.normalize());
        // Starts 2 blocks in front of the player
        Vec3d origin = player.getPos().add(wave.dir.multiply(2.0));
        double startY = origin.y + START_UP;
        for (int step = 1; step <= LENGTH_BLOCKS; step++) {
            Vec3d base = origin.add(wave.dir.multiply(step));
            for (int lane : LANES) {
                Vec3d p = base.add(wave.left.multiply(lane));
                wave.pending.add(new Pending(p.x, p.z, startY, lane, step * STEP_DELAY_T));
            }
        }
        ACTIVE.add(wave);
        return true;
    }

    // ---------------------------------------------------------------------
    // Timeline
    // ---------------------------------------------------------------------

    /** Returns false once every spike has risen and sunk again. */
    private static boolean tick(Wave w) {
        Iterator<Pending> pending = w.pending.iterator();
        while (pending.hasNext()) {
            Pending p = pending.next();
            if (--p.delay == 2 && p.lane == 0) crackAhead(w, p);
            if (p.delay <= 0) {
                erupt(w, p);
                pending.remove();
            }
        }

        Iterator<Spike> spikes = w.spikes.iterator();
        while (spikes.hasNext()) {
            Spike s = spikes.next();
            s.age++;
            if (s.age == 1) {
                animate(s, s.raised, RISE_T);
            } else if (s.age == 1 + RISE_T + LIFETIME_T) {
                animate(s, s.buried, SINK_T);
            } else if (s.age > 1 + RISE_T + LIFETIME_T + SINK_T) {
                for (var d : s.parts) TempEntities.discard(d);
                spikes.remove();
            }
        }
        return !w.pending.isEmpty() || !w.spikes.isEmpty();
    }

    /** A moment before a spike comes up, the ground where it will break open cracks and spits dust. */
    private static void crackAhead(Wave w, Pending p) {
        BlockPos ground = findGround(w.world, MathHelper.floor(p.x), MathHelper.floor(p.z), MathHelper.floor(p.startY));
        if (ground == null) return;
        var dust = new BlockStateParticleEffect(ParticleTypes.BLOCK, w.world.getBlockState(ground));
        w.world.spawnParticles(dust, p.x, ground.getY() + 1.05, p.z, 6, 0.6, 0.02, 0.6, 0.05);
    }

    private static void erupt(Wave w, Pending p) {
        ServerWorld world = w.world;
        BlockPos ground = findGround(world, MathHelper.floor(p.x), MathHelper.floor(p.z), MathHelper.floor(p.startY));
        if (ground == null) return;
        // Needs open space to come up into
        BlockPos above = ground.up();
        if (!world.getBlockState(above).getCollisionShape(world, above).isEmpty()) return;

        boolean center = p.lane == 0;
        float height = center ? CENTER_HEIGHT : SIDE_HEIGHT;
        height *= 0.9f + world.random.nextFloat() * 0.2f;
        double baseY = ground.getY() + 1.0;
        Vec3d at = new Vec3d(p.x, baseY, p.z);

        // Hit whatever it comes up under: pop them onto the tip and throw them
        PlayerEntity owner = world.getPlayerByUuid(w.owner);
        Box box = new Box(above).expand(HIT_HALF_WIDTH, height, HIT_HALF_WIDTH);
        double topY = baseY + height;
        for (LivingEntity le : world.getEntitiesByClass(LivingEntity.class, box, e -> e.isAlive() && !e.isSpectator())) {
            if (le.getUuid().equals(w.owner)) continue;
            le.setPosition(le.getX(), topY, le.getZ());
            le.damage(owner != null ? world.getDamageSources().playerAttack(owner) : world.getDamageSources().stalagmite(), DAMAGE);
            le.addVelocity(w.dir.x * FORWARD_PUSH, LIFT, w.dir.z * FORWARD_PUSH);
            le.velocityModified = true;
            world.spawnParticles(ParticleTypes.CRIT, le.getX(), le.getBodyY(0.5), le.getZ(), 10, 0.3, 0.3, 0.3, 0.3);
        }

        // Build the spike: a rock base and a pointed tip, the outer lanes leaning away from the centre
        float twist = world.random.nextFloat() * MathHelper.TAU;
        float lean = p.lane * SIDE_LEAN;
        Vec3d axis = w.dir;   // leaning sideways means rotating about the forward axis
        Spike spike = new Spike();
        float baseH = height * 0.45f;
        addPart(world, spike, at, ROCK, 0, 0.7f, baseH, twist, lean, axis);
        addPart(world, spike, at, TIP, baseH, 0.9f, height - baseH, twist, lean, axis);
        w.spikes.add(spike);

        var dust = new BlockStateParticleEffect(ParticleTypes.BLOCK, world.getBlockState(ground));
        world.spawnParticles(dust, p.x, baseY + 0.1, p.z, 14, 0.4, 0.1, 0.4, 0.15);
        world.spawnParticles(ParticleTypes.POOF, p.x, baseY + 0.2, p.z, 3, 0.2, 0.1, 0.2, 0.02);
        if (center) {
            world.playSound(null, p.x, baseY, p.z, SoundEvents.BLOCK_POINTED_DRIPSTONE_LAND, SoundCategory.PLAYERS, 0.9f, 0.7f + world.random.nextFloat() * 0.2f);
            world.playSound(null, p.x, baseY, p.z, SoundEvents.BLOCK_STONE_PLACE, SoundCategory.PLAYERS, 0.8f, 0.8f);
        }
    }

    private static void addPart(ServerWorld world, Spike spike, Vec3d at, BlockState state, float y, float width,
                                float height, float twist, float lean, Vec3d axis) {
        DisplayEntity.BlockDisplayEntity d = EntityType.BLOCK_DISPLAY.create(world);
        if (d == null) return;
        AffineTransformation up = partTransform(y, width, height, twist, lean, axis, 0);
        AffineTransformation down = partTransform(y, width, height, twist, lean, axis, -(y + height + 0.3f));
        d.setBlockState(state);
        d.setViewRange(2.0f);
        d.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
        d.setTransformation(down);
        TempEntities.track(d);
        world.spawnEntity(d);
        spike.parts.add(d);
        spike.raised.add(up);
        spike.buried.add(down);
    }

    private static AffineTransformation partTransform(float y, float width, float height, float twist, float lean,
                                                      Vec3d axis, float sink) {
        Matrix4f m = new Matrix4f().translate(0, sink, 0);
        if (lean != 0) m.rotate(lean, (float) axis.x, 0, (float) axis.z);
        m.translate(0, y, 0)
                .rotateY(twist)
                .scale(width, height, width)
                .translate(-0.5f, 0, -0.5f);
        return new AffineTransformation(m);
    }

    private static void animate(Spike s, List<AffineTransformation> to, int ticks) {
        for (int i = 0; i < s.parts.size(); i++) {
            var d = s.parts.get(i);
            d.setTransformation(to.get(i));
            d.setStartInterpolation(0);
            d.setInterpolationDuration(ticks);
        }
    }

    /** The first solid block at or below startY, skipping fluids. */
    private static BlockPos findGround(ServerWorld w, int x, int z, int startY) {
        int minY = w.getBottomY() + 1;
        for (int y = startY; y >= minY && y >= startY - 12; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            var state = w.getBlockState(pos);
            if (!state.getFluidState().isEmpty()) continue;
            if (!state.getCollisionShape(w, pos).isEmpty()) return pos;
        }
        return null;
    }
}
