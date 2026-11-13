package net.ragnar.ragnarsmagicmod.oak;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.PillarBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;
import net.ragnar.ragnarsmagicmod.entity.IllusionEntity;
import net.ragnar.ragnarsmagicmod.network.ShakePayload;
import net.ragnar.ragnarsmagicmod.util.TempEntities;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One Tome of Oak oak, from the moment it bursts out of the ground to the moment its last splinter crumbles. Run by
 * the server, a tick at a time (see Oak.register):
 * <ol>
 *   <li><b>Grow</b> ({@link #GROW_TICKS}): the tree surges up out of the ground, overshooting a touch and settling,
 *       while its leaves burst out from the bottom of the crown to the top.</li>
 *   <li><b>Stand</b> ({@link #STAND_TICKS}): it groans and leans back for a heartbeat, as if gathering itself.</li>
 *   <li><b>Fall</b>: its roots tear out of the ground and it topples forward like a rod tipping over - slow to start,
 *       then faster and faster - until its crown meets the ground, wherever the ground happens to be.</li>
 *   <li><b>Bounce</b>: it kicks back up off the crown a little and slams down again.</li>
 *   <li><b>Lie</b> ({@link #LIE_TICKS}): it lies there, shedding leaves, as the dust settles and the crash echoes.</li>
 *   <li><b>Crumble</b>: it breaks up from the crown back to the roots and is gone.</li>
 * </ol>
 * The landing crushes everything under the trunk or crown for {@link #CRUSH_DAMAGE}, and the shockwave hits and
 * throws everything up to {@link #SHOCK_RANGE} blocks further out.
 * <p>
 * Every piece is a block display standing on the foot of the trunk (the pivot); only its transformation places it.
 * The whole tree's pose is one matrix (heading, then the tip angle, then the growth scale) put in front of each
 * piece's own, so turning the tree is just handing every piece its new transformation, which the clients ease into.
 */
public final class OakTree {
    // Timeline
    public static final int GROW_TICKS = 44;
    public static final int STAND_TICKS = 20;
    public static final int LIE_TICKS = 70;
    static final int CRUMBLE_SPREAD = 28;  // from the crown's first leaves to the last root
    static final int SHRINK_TICKS = 10;    // one piece crumbling away
    static final int MAX_FALL_TICKS = 100; // safety net: it has landed by now, whatever the terrain

    /** The whole tree, scaled up from the shape below: about 45 blocks from the roots to the top of the crown. */
    static final float S = 1.5f;

    // The fall: a rod tipping over, angular acceleration K * sin(angle) - a taller tree falls slower
    static final float K = 3.0f / S;
    static final float DT = 1f / 20f;
    static final float START_ANGLE = 0.04f, START_SPIN = 0.2f;
    static final float RESTITUTION = 0.25f;     // how much of the spin the bounce gives back
    static final float LIE_ANGLE = 1.40f;       // on flat ground it comes to rest about 80 degrees over
    static final float MIN_LAND = 0.55f, MAX_LAND = 1.80f;

    // Shape (local: +Y up the trunk, +Z the way it falls, the pivot at the foot of the trunk)
    static final int SEGMENTS = 13;
    static final float SEGMENT = 2.0f;
    static final float BASE_WIDTH = 3.2f, TOP_WIDTH = 1.7f;
    static final float CROWN_Y = 25f, CROWN_RADIUS = 7.5f;
    /** How high over the ground the crown's centre lies when the tree is down (the rest is crushed into it). */
    static final float CROWN_REST = 4.2f * S;

    // The landing
    public static final float CRUSH_DAMAGE = 80f;
    public static final float SHOCK_MAX = 24f, SHOCK_MIN = 6f;
    public static final double SHOCK_RANGE = 8;
    static final double PARTICLE_RANGE = 128;
    static final double TRUNK_REACH = 3.6, CROWN_REACH = CROWN_RADIUS * S + 0.5;

    enum Phase { GROW, STAND, FALL, BOUNCE, LIE, CRUMBLE }

    /** One block of the tree: what it is, and where it sits in the tree. */
    private static final class Piece {
        final BlockState state;
        final Vector3f center;
        final Quaternionf rotation;
        final Vector3f size;
        final float popAt;     // leaves: how far into the growth they burst out (-1: grows with the tree)
        float order;           // when it crumbles: 0 first (the crown's tip), 1 last (the roots)
        DisplayEntity.BlockDisplayEntity entity;
        boolean popped, gone;

        Piece(BlockState state, Vector3f center, Quaternionf rotation, Vector3f size, float popAt) {
            this.state = state;
            this.center = center;
            this.rotation = rotation;
            this.size = size;
            this.popAt = popAt;
        }

        boolean leaf() {
            return state.getBlock() instanceof LeavesBlock;
        }
    }

    /** A chunk of earth, leaves or wood thrown by the tree, flying on its own until it lands and fades. */
    private static final class Debris {
        final DisplayEntity.BlockDisplayEntity entity;
        final Vector3f size;
        final Quaternionf rotation, spin;
        Vec3d pos, velocity;
        int landedAt = -1, age = 0;

        Debris(DisplayEntity.BlockDisplayEntity entity, Vector3f size, Quaternionf rotation, Quaternionf spin, Vec3d pos, Vec3d velocity) {
            this.entity = entity;
            this.size = size;
            this.rotation = rotation;
            this.spin = spin;
            this.pos = pos;
            this.velocity = velocity;
        }
    }

    final ServerWorld world;
    private final UUID owner;
    private final List<Debris> debris = new ArrayList<>();
    private final Vec3d pivot;
    private final float heading;
    private final List<Piece> pieces = new ArrayList<>();
    private final Vector3f crown = new Vector3f(0, CROWN_Y, 0);

    private Phase phase = Phase.GROW;
    private int age = 0, phaseAge = 0, sinceImpact = -1;
    private float angle = 0f, spin = 0f, landAngle = LIE_ANGLE;
    private boolean landed = false;

    OakTree(ServerWorld world, PlayerEntity caster, Vec3d foot, Vec3d forward) {
        this.world = world;
        this.owner = caster.getUuid();
        this.pivot = foot;
        this.heading = (float) Math.atan2(forward.x, forward.z);
        build(Random.create(world.getRandom().nextLong()));
        spawn();

        sound(pivot, SoundEvents.ITEM_BONE_MEAL_USE, 2.5f, 0.5f);
        sound(pivot, SoundEvents.BLOCK_ROOTED_DIRT_BREAK, 3.0f, 0.5f);
        sound(pivot, SoundEvents.BLOCK_GRASS_BREAK, 2.0f, 0.5f);
        ShakePayload.around(world, pivot, 24, 0.35f, GROW_TICKS);
    }

    public boolean landed() {
        return landed;
    }

    public Vec3d pivot() {
        return pivot;
    }

    /** How many block displays make up the tree (kept modest: every one is re-posed each tick of the fall). */
    public int pieceCount() {
        return pieces.size();
    }

    // ---------------------------------------------------------------------
    // Shape
    // ---------------------------------------------------------------------

    private void build(Random r) {
        BlockState bark = Blocks.OAK_WOOD.getDefaultState().with(PillarBlock.AXIS, Direction.Axis.Y);

        // Trunk: thick at the foot, tapering, with a gentle bend so it never looks turned on a lathe
        Vector3f[] axis = new Vector3f[SEGMENTS + 1];
        for (int k = 0; k <= SEGMENTS; k++) {
            float f = k / (float) SEGMENTS;
            axis[k] = new Vector3f(-0.6f * MathHelper.sin(f * 2.2f), -1f + k * SEGMENT, -0.35f * MathHelper.sin(f * 1.7f));
        }
        for (int k = 0; k < SEGMENTS; k++) {
            float f = k / (float) (SEGMENTS - 1);
            float w = MathHelper.lerp(f, BASE_WIDTH, TOP_WIDTH);
            Vector3f c = new Vector3f(axis[k]).add(axis[k + 1]).mul(0.5f);
            pieces.add(new Piece(bark, c, new Quaternionf().rotateY(range(r, -0.2f, 0.2f)), new Vector3f(w, SEGMENT * 1.06f, w), -1));
        }
        Vector3f top = axis[SEGMENTS];
        crown.set(top.x, CROWN_Y, top.z);

        // Buttress roots flaring out at the foot, and thinner roots below (they come up with the root ball)
        for (int i = 0; i < 5; i++) {
            float a = i * MathHelper.TAU / 5 + range(r, -0.3f, 0.3f);
            Vector3f bottom = new Vector3f(MathHelper.cos(a) * 3.1f, -0.7f, MathHelper.sin(a) * 3.1f);
            Vector3f upper = new Vector3f(MathHelper.cos(a) * 0.9f, 2.6f, MathHelper.sin(a) * 0.9f);
            pieces.add(beam(bark, bottom, upper, range(r, 1.2f, 1.5f)));
        }
        for (int i = 0; i < 5; i++) {
            float a = i * MathHelper.TAU / 5 + MathHelper.PI / 5 + range(r, -0.3f, 0.3f);
            Vector3f from = new Vector3f(MathHelper.cos(a) * 1.2f, -1.5f, MathHelper.sin(a) * 1.2f);
            Vector3f to = new Vector3f(MathHelper.cos(a) * 4.0f, range(r, -4.2f, -3.0f), MathHelper.sin(a) * 4.0f);
            pieces.add(beam(bark, from, to, range(r, 0.5f, 0.7f)));
        }
        // The root ball: earth torn up with the roots
        BlockState[] earth = {Blocks.ROOTED_DIRT.getDefaultState(), Blocks.ROOTED_DIRT.getDefaultState(),
                Blocks.DIRT.getDefaultState(), Blocks.COARSE_DIRT.getDefaultState()};
        for (int i = 0; i < 6; i++) {
            float a = r.nextFloat() * MathHelper.TAU, d = range(r, 0.3f, 2.0f);
            Vector3f c = new Vector3f(MathHelper.cos(a) * d, range(r, -2.6f, -1.5f), MathHelper.sin(a) * d);
            float s = range(r, 2.6f, 3.4f);
            pieces.add(new Piece(earth[r.nextInt(earth.length)], c, randomTurn(r, 0.4f), new Vector3f(s, s, s), -1));
        }

        // Boughs reaching out of the upper trunk, each ending in its own cloud of leaves
        List<Vector3f> lumps = new ArrayList<>();
        List<Float> lumpRadii = new ArrayList<>();
        float start = r.nextFloat() * MathHelper.TAU;
        for (int i = 0; i < 5; i++) {
            float h = 13f + i * 2.2f + range(r, -0.5f, 0.5f);
            int k = MathHelper.clamp((int) ((h + 1f) / SEGMENT), 0, SEGMENTS - 1);
            Vector3f from = new Vector3f(axis[k].x, h, axis[k].z);
            float az = start + i * 2.39996f + range(r, -0.25f, 0.25f);
            float el = range(r, 0.6f, 0.95f);
            float len = range(r, 6.5f, 8.5f);
            Vector3f dir = new Vector3f(MathHelper.cos(az) * MathHelper.cos(el), MathHelper.sin(el), MathHelper.sin(az) * MathHelper.cos(el));
            Vector3f to = new Vector3f(dir).mul(len).add(from);
            pieces.add(beam(bark, from, to, MathHelper.lerp(i / 4f, 1.4f, 1.0f)));
            lumps.add(new Vector3f(to).add(0, 1.0f, 0));
            lumpRadii.add(range(r, 3.6f, 4.4f));
        }

        // The crown: one great cloud of leaves, a smaller one on top, and the boughs' own
        leafCloud(r, crown, CROWN_RADIUS, 0.8f, -0.6f);
        leafCloud(r, new Vector3f(crown).add(0, 4.5f, 0), 4.6f, 0.9f, -1f);
        for (int i = 0; i < lumps.size(); i++) leafCloud(r, lumps.get(i), lumpRadii.get(i), 0.85f, -1f);
        for (int i = 0; i < 4; i++) { // some depth inside, as the leaves are see-through
            Vector3f c = new Vector3f(crown).add(range(r, -2.5f, 2.5f), range(r, -1.5f, 1.5f), range(r, -2.5f, 2.5f));
            pieces.add(new Piece(leaves(r), c, randomTurn(r, 0.4f), new Vector3f(3.4f, 3.4f, 3.4f), popAt(r, c)));
        }

        // Up to full size
        for (Piece p : pieces) {
            p.center.mul(S);
            p.size.mul(S);
        }
        crown.mul(S);

        // Crumbling runs from the far end of the tree back to the roots
        float far = 0;
        for (Piece p : pieces) far = Math.max(far, p.center.length());
        for (Piece p : pieces) p.order = MathHelper.clamp(1f - p.center.length() / far + range(r, -0.04f, 0.04f), 0f, 1f);
    }

    /** Clusters of leaves over the surface of a (squashed) sphere, spaced evenly round it. */
    private void leafCloud(Random r, Vector3f center, float radius, float squash, float lowestUp) {
        float cluster = 2.8f;
        int n = Math.max(8, Math.round(4f * MathHelper.PI * radius * radius / (cluster * cluster * 1.5f)));
        for (int i = 0; i < n; i++) {
            float y = 1f - 2f * (i + 0.5f) / n;
            if (y < lowestUp) continue; // an open underside, so the boughs show
            float ring = MathHelper.sqrt(1f - y * y);
            float a = i * 2.39996f;
            float d = radius - cluster * 0.42f;
            Vector3f c = new Vector3f(MathHelper.cos(a) * ring * d, y * d * squash, MathHelper.sin(a) * ring * d).add(center);
            c.add(range(r, -0.4f, 0.4f), range(r, -0.4f, 0.4f), range(r, -0.4f, 0.4f));
            float s = range(r, 2.6f, 3.1f);
            pieces.add(new Piece(leaves(r), c, randomTurn(r, 0.35f), new Vector3f(s, s, s), popAt(r, c)));
        }
    }

    /** Leaves burst out from the bottom of the crown to the top, late in the growth. */
    private static float popAt(Random r, Vector3f c) {
        return 0.3f + 0.42f * MathHelper.clamp((c.y - 10f) / 25f, 0f, 1f) + range(r, 0f, 0.08f);
    }

    private static BlockState leaves(Random r) {
        float roll = r.nextFloat();
        BlockState s = roll < 0.72f ? Blocks.OAK_LEAVES.getDefaultState()
                : roll < 0.9f ? Blocks.DARK_OAK_LEAVES.getDefaultState()
                : Blocks.AZALEA_LEAVES.getDefaultState();
        return s.with(LeavesBlock.PERSISTENT, true);
    }

    /** A log running from {@code a} to {@code b}. */
    private static Piece beam(BlockState state, Vector3f a, Vector3f b, float thickness) {
        Vector3f d = new Vector3f(b).sub(a);
        float len = d.length();
        Quaternionf q = new Quaternionf().rotationTo(0, 1, 0, d.x / len, d.y / len, d.z / len);
        return new Piece(state, new Vector3f(a).add(b).mul(0.5f), q, new Vector3f(thickness, len + thickness * 0.3f, thickness), -1);
    }

    private static Quaternionf randomTurn(Random r, float max) {
        return new Quaternionf().rotateXYZ(range(r, -max, max), range(r, -MathHelper.PI, MathHelper.PI), range(r, -max, max));
    }

    private static float range(Random r, float min, float max) {
        return min + r.nextFloat() * (max - min);
    }

    // ---------------------------------------------------------------------
    // Pose
    // ---------------------------------------------------------------------

    private void spawn() {
        Matrix4f pose = pose(0.02f);
        for (Piece p : pieces) {
            DisplayEntity.BlockDisplayEntity e = EntityType.BLOCK_DISPLAY.create(world);
            if (e == null) continue;
            e.setBlockState(p.state);
            e.refreshPositionAndAngles(pivot.x, pivot.y, pivot.z, 0f, 0f);
            e.setViewRange(4f);
            e.setTransformation(transform(pose, p, p.popAt < 0 ? 1f : 0.001f));
            TempEntities.track(e);
            world.spawnEntity(e);
            p.entity = e;
        }
    }

    /** The whole tree's pose: turned to face the way it falls, tipped over by the current angle, grown to {@code grow}. */
    private Matrix4f pose(float grow) {
        return new Matrix4f().rotateY(heading).rotateX(angle).scale(Math.max(grow, 0.001f));
    }

    private static AffineTransformation transform(Matrix4f pose, Piece p, float pop) {
        float s = Math.max(pop, 0.001f);
        Matrix4f m = new Matrix4f(pose).translate(p.center).rotate(p.rotation)
                .scale(p.size.x * s, p.size.y * s, p.size.z * s).translate(-0.5f, -0.5f, -0.5f);
        return new AffineTransformation(m);
    }

    private static void send(Piece p, AffineTransformation t, int ticks) {
        if (p.entity == null || p.gone) return;
        p.entity.setTransformation(t);
        p.entity.setStartInterpolation(0);
        p.entity.setInterpolationDuration(ticks);
    }

    private void pose(float grow, float progress, int ticks) {
        Matrix4f pose = pose(grow);
        for (Piece p : pieces) send(p, transform(pose, p, pop(p, progress)), ticks);
    }

    private static float pop(Piece p, float progress) {
        if (p.popAt < 0) return 1f;
        float t = MathHelper.clamp((progress - p.popAt) / 0.22f, 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    /** Where a point of the tree is in the world right now. */
    private Vec3d at(Vector3f local) {
        Vector3f v = pose(1f).transformPosition(new Vector3f(local));
        return pivot.add(v.x, v.y, v.z);
    }

    // ---------------------------------------------------------------------
    // Ticking
    // ---------------------------------------------------------------------

    /** Returns false once the tree is gone. */
    boolean tick() {
        age++;
        phaseAge++;
        if (sinceImpact >= 0) echo(++sinceImpact);
        if (!debris.isEmpty()) tickDebris();
        switch (phase) {
            case GROW -> grow();
            case STAND -> stand();
            case FALL -> fall();
            case BOUNCE -> bounce();
            case LIE -> lie();
            case CRUMBLE -> {
                return crumble();
            }
        }
        return true;
    }

    private void next(Phase p) {
        phase = p;
        phaseAge = 0;
    }

    private void grow() {
        float t = MathHelper.clamp(phaseAge / (float) GROW_TICKS, 0f, 1f);
        // Surges up, overshoots a touch, settles
        float u = t - 1f;
        float grow = 1f + 2.4f * u * u * u + 1.4f * u * u;
        if (phaseAge % 2 == 0 || phaseAge >= GROW_TICKS) pose(Math.max(grow, 0.02f), t, 2);

        // Earth heaving at the foot
        BlockStateParticleEffect ground = groundDebris(BlockPos.ofFloored(pivot).down());
        particles(ground, pivot.x, pivot.y + 0.2, pivot.z, 8, 1.6, 0.3, 1.6, 0.25);
        if (phaseAge % 3 == 0) particles(ParticleTypes.DUST_PLUME, pivot.x, pivot.y + 0.3, pivot.z, 3, 1.8, 0.2, 1.8, 0.04);
        if (phaseAge % 3 == 0) sound(at(new Vector3f(0, 20f * S * grow, 0)), SoundEvents.BLOCK_WOOD_PLACE, 2.5f, 0.5f + 0.4f * t);
        if (phaseAge % 5 == 0) sound(pivot, SoundEvents.BLOCK_ROOTED_DIRT_PLACE, 2.0f, 0.6f);

        // Leaves bursting out
        int rustles = 0;
        for (Piece p : pieces) {
            if (p.popAt < 0 || p.popped || pop(p, t) <= 0f) continue;
            p.popped = true;
            Vec3d c = at(new Vector3f(p.center).mul(Math.max(grow, 0.02f)));
            particles(new BlockStateParticleEffect(ParticleTypes.BLOCK, p.state), c.x, c.y, c.z, 5, 1.0, 1.0, 1.0, 0.1);
            if (rustles++ == 0 && phaseAge % 2 == 0) {
                sound(c, SoundEvents.BLOCK_AZALEA_LEAVES_PLACE, 2.0f, range(world.getRandom(), 0.6f, 0.9f));
            }
        }

        if (phaseAge >= GROW_TICKS) {
            Vec3d c = at(crown);
            sound(c, SoundEvents.BLOCK_AZALEA_LEAVES_BREAK, 4.0f, 0.6f);
            sound(c, SoundEvents.BLOCK_GRASS_BREAK, 3.0f, 0.5f);
            particles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.OAK_LEAVES.getDefaultState()),
                    c.x, c.y, c.z, 60, 5.0, 4.0, 5.0, 0.15);
            next(Phase.STAND);
        }
    }

    private void stand() {
        float s = phaseAge / (float) STAND_TICKS;
        // Rocks back as if gathering itself, then starts to go
        angle = -0.07f * MathHelper.sin(MathHelper.PI * s) + START_ANGLE * s * s * s;
        pose(1f, 1f, 1);

        Vec3d mid = at(new Vector3f(0, 12 * S, 0));
        if (phaseAge == 1) sound(mid, SoundEvents.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 3.0f, 0.5f);
        if (phaseAge == 11) sound(mid, SoundEvents.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 3.0f, 0.42f);
        if (phaseAge % 4 == 0) {
            Vec3d c = at(crown);
            particles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.OAK_LEAVES.getDefaultState()),
                    c.x, c.y - 3, c.z, 6, 5.0, 2.0, 5.0, 0.05);
        }

        if (phaseAge >= STAND_TICKS) {
            // The roots give
            angle = START_ANGLE;
            spin = START_SPIN;
            landAngle = landingAngle();
            sound(pivot, SoundEvents.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 4.0f, 0.55f);
            sound(pivot, SoundEvents.BLOCK_WOOD_BREAK, 4.0f, 0.5f);
            sound(pivot, SoundEvents.BLOCK_ROOTED_DIRT_BREAK, 3.0f, 0.5f);
            sound(pivot, SoundEvents.BLOCK_MANGROVE_ROOTS_BREAK, 3.0f, 0.5f);
            Vec3d back = at(new Vector3f(0, -1.5f * S, -2.5f * S));
            BlockStateParticleEffect ground = groundDebris(BlockPos.ofFloored(pivot).down());
            particles(ground, back.x, back.y + 0.5, back.z, 60, 2.0, 0.6, 2.0, 0.35);
            particles(ParticleTypes.DUST_PLUME, back.x, back.y + 0.5, back.z, 12, 2.0, 0.4, 2.0, 0.08);
            ShakePayload.around(world, pivot, 28, 0.6f, 14);
            Random r = world.getRandom();
            BlockState topsoil = surface(BlockPos.ofFloored(pivot).down());
            for (int i = 0; i < 10; i++) {
                double a = r.nextDouble() * Math.PI * 2, d = r.nextDouble() * 3.5;
                Vec3d from = back.add(Math.cos(a) * d, 0.4, Math.sin(a) * d);
                Vec3d v = new Vec3d(Math.cos(a) * 0.15, range(r, 0.35f, 0.7f), Math.sin(a) * 0.15);
                throwDebris(r, i % 3 == 0 ? Blocks.DIRT.getDefaultState() : i % 3 == 1 ? Blocks.ROOTED_DIRT.getDefaultState() : topsoil, from, v);
            }
            next(Phase.FALL);
        }
    }

    /** How far over the tree goes before its crown (or trunk) meets the ground, wherever that is. */
    private float landingAngle() {
        float saved = angle;
        float land = MAX_LAND;
        for (float a = MIN_LAND; a <= MAX_LAND; a += 0.01f) {
            angle = a;
            Vec3d c = at(crown);
            Vec3d mid = at(new Vector3f(0, CROWN_Y * S * 0.5f, 0));
            if (c.y - CROWN_REST <= groundY(c) || mid.y - 1.6 * S <= groundY(mid)) {
                land = a;
                break;
            }
        }
        angle = saved;
        // On flat ground it settles at LIE_ANGLE; steps and slopes move that, within reason
        return land;
    }

    private double groundY(Vec3d at) {
        return world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, MathHelper.floor(at.x), MathHelper.floor(at.z));
    }

    private void fall() {
        float before = angle;
        spin += K * MathHelper.sin(angle) * DT;
        angle += spin * DT;
        boolean down = angle >= landAngle || phaseAge >= MAX_FALL_TICKS;
        if (down) angle = landAngle;
        pose(1f, 1f, 1);

        Vec3d c = at(crown);
        // Leaves torn off by the air, earth falling from the roots
        particles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.OAK_LEAVES.getDefaultState()),
                c.x, c.y, c.z, 8, 4.5, 3.0, 4.5, 0.1);
        Vec3d roots = at(new Vector3f(0, -2.2f * S, 0));
        particles(groundDebris(BlockPos.ofFloored(pivot).down()), roots.x, roots.y, roots.z, 3, 1.4, 0.6, 1.4, 0.05);

        if (phaseAge % 7 == 0) sound(at(new Vector3f(0, 10 * S, 0)), SoundEvents.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 2.5f, 0.45f);
        if (before < 0.7f && angle >= 0.7f) sound(c, SoundEvents.ENTITY_ENDER_DRAGON_FLAP, 5.0f, 0.5f);
        if (before < 1.05f && angle >= 1.05f) {
            sound(c, SoundEvents.ENTITY_BREEZE_WIND_BURST.value(), 5.0f, 0.5f);
            sound(c, SoundEvents.ENTITY_ENDER_DRAGON_FLAP, 5.0f, 0.4f);
        }

        if (down) {
            impact();
            spin = -spin * RESTITUTION;
            next(Phase.BOUNCE);
        }
    }

    private void bounce() {
        spin += K * MathHelper.sin(angle) * DT;
        angle += spin * DT;
        boolean down = angle >= landAngle || phaseAge >= 20;
        if (down) angle = landAngle;
        pose(1f, 1f, 1);
        if (down) {
            Vec3d c = at(crown);
            sound(c, SoundEvents.ITEM_MACE_SMASH_GROUND, 4.0f, 0.55f);
            sound(c, SoundEvents.BLOCK_WOOD_BREAK, 3.0f, 0.5f);
            particles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.OAK_LEAVES.getDefaultState()),
                    c.x, c.y, c.z, 50, 6.0, 2.0, 6.0, 0.15);
            particles(ParticleTypes.DUST_PLUME, c.x, groundY(c) + 0.3, c.z, 16, 6.0, 0.3, 6.0, 0.06);
            ShakePayload.around(world, c, 32, 0.6f, 10);
            next(Phase.LIE);
        }
    }

    private void lie() {
        if (phaseAge % 3 == 0) {
            Vec3d c = at(crown);
            particles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.OAK_LEAVES.getDefaultState()),
                    c.x, c.y + 2, c.z, 3, 5.0, 2.0, 5.0, 0.02);
        }
        if (phaseAge >= LIE_TICKS) next(Phase.CRUMBLE);
    }

    private boolean crumble() {
        if (phaseAge > CRUMBLE_SPREAD + SHRINK_TICKS + 2) {
            discard();
            return false;
        }
        Matrix4f pose = pose(1f);
        boolean heard = false;
        for (Piece p : pieces) {
            if (p.gone || p.order * CRUMBLE_SPREAD > phaseAge) continue;
            send(p, transform(pose, p, 0.001f), SHRINK_TICKS);
            p.gone = true;
            Vec3d c = at(p.center);
            double spread = p.size.x * 0.3;
            particles(new BlockStateParticleEffect(ParticleTypes.BLOCK, p.state), c.x, c.y, c.z,
                    p.leaf() ? 4 : 6, spread, spread, spread, 0.05);
            if (!heard && world.getRandom().nextInt(3) == 0) {
                heard = true;
                if (p.leaf()) sound(c, SoundEvents.BLOCK_AZALEA_LEAVES_BREAK, 1.5f, range(world.getRandom(), 0.7f, 0.9f));
                else sound(c, SoundEvents.BLOCK_WOOD_BREAK, 1.5f, range(world.getRandom(), 0.6f, 0.8f));
            }
        }
        return true;
    }

    void discard() {
        for (Piece p : pieces) {
            if (p.entity != null && !p.entity.isRemoved()) TempEntities.discard(p.entity);
        }
        for (Debris d : debris) {
            if (!d.entity.isRemoved()) TempEntities.discard(d.entity);
        }
        debris.clear();
    }

    // ---------------------------------------------------------------------
    // Debris
    // ---------------------------------------------------------------------

    private void throwDebris(Random r, BlockState state, Vec3d from, Vec3d velocity) {
        DisplayEntity.BlockDisplayEntity e = EntityType.BLOCK_DISPLAY.create(world);
        if (e == null) return;
        boolean splinter = state.isOf(Blocks.OAK_WOOD);
        float s = range(r, 0.55f, 1.15f);
        Vector3f size = splinter ? new Vector3f(0.3f, range(r, 1.2f, 2.0f), 0.3f) : new Vector3f(s, s, s);
        Quaternionf rotation = randomTurn(r, MathHelper.PI);
        Vector3f axis = new Vector3f(r.nextFloat() - 0.5f, r.nextFloat() - 0.5f, r.nextFloat() - 0.5f);
        if (axis.lengthSquared() < 1.0e-4f) axis.set(0, 1, 0);
        Quaternionf spin = new Quaternionf().rotationAxis(range(r, 0.2f, 0.5f), axis.normalize());
        e.setBlockState(state);
        e.refreshPositionAndAngles(from.x, from.y, from.z, 0f, 0f);
        e.setTeleportDuration(1);
        e.setViewRange(2f);
        e.setTransformation(debrisTransform(rotation, size, 1f));
        TempEntities.track(e);
        world.spawnEntity(e);
        debris.add(new Debris(e, size, rotation, spin, from, velocity));
    }

    private static AffineTransformation debrisTransform(Quaternionf rotation, Vector3f size, float scale) {
        float k = Math.max(scale, 0.001f);
        return new AffineTransformation(new Matrix4f().rotate(rotation).scale(size.x * k, size.y * k, size.z * k).translate(-0.5f, -0.5f, -0.5f));
    }

    private void tickDebris() {
        debris.removeIf(d -> {
            d.age++;
            if (d.landedAt < 0) {
                d.velocity = d.velocity.multiply(0.98).add(0, -0.08, 0);
                d.pos = d.pos.add(d.velocity);
                double floor = groundY(d.pos);
                if (d.velocity.y < 0 && d.pos.y <= floor + 0.25) {
                    d.pos = new Vec3d(d.pos.x, floor + 0.25, d.pos.z);
                    d.landedAt = d.age;
                    particles(new BlockStateParticleEffect(ParticleTypes.BLOCK, d.entity.getBlockState()), d.pos.x, d.pos.y, d.pos.z, 4, 0.2, 0.1, 0.2, 0.05);
                } else {
                    d.rotation.mul(d.spin);
                }
                d.entity.setPosition(d.pos.x, d.pos.y, d.pos.z);
                d.entity.setTransformation(debrisTransform(d.rotation, d.size, 1f));
                d.entity.setStartInterpolation(0);
                d.entity.setInterpolationDuration(1);
                if (d.age > 60) d.landedAt = d.age; // lost somewhere: just let it fade
                return false;
            }
            int lying = d.age - d.landedAt;
            if (lying == 30) {
                d.entity.setTransformation(debrisTransform(d.rotation, d.size, 0.001f));
                d.entity.setStartInterpolation(0);
                d.entity.setInterpolationDuration(10);
            }
            if (lying >= 41) {
                TempEntities.discard(d.entity);
                return true;
            }
            return false;
        });
    }

    // ---------------------------------------------------------------------
    // The landing
    // ---------------------------------------------------------------------

    private void impact() {
        landed = true;
        sinceImpact = 0;
        Vec3d c = at(crown);
        Vec3d mid = at(new Vector3f(0, CROWN_Y * S * 0.5f, 0));
        double floor = groundY(c);

        // A monument coming down: thunder rolling under it, the earth giving, the timber splitting
        sound(c, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 6.0f, 0.5f);
        sound(c, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 6.0f, 0.5f);
        sound(c, SoundEvents.ITEM_MACE_SMASH_GROUND_HEAVY, 6.0f, 0.5f);
        sound(mid, SoundEvents.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 6.0f, 0.5f);
        sound(mid, SoundEvents.BLOCK_WOOD_BREAK, 6.0f, 0.45f);
        sound(c, SoundEvents.BLOCK_AZALEA_LEAVES_BREAK, 6.0f, 0.5f);
        sound(c, SoundEvents.ENTITY_IRON_GOLEM_DAMAGE, 4.0f, 0.5f);
        ShakePayload.around(world, c, 48, 1.8f, 30);

        // Leaves exploding off the crown, earth thrown out in a ring, a wall of dust
        ParticleEffect leaves = new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.OAK_LEAVES.getDefaultState());
        particles(leaves, c.x, c.y, c.z, 260, 9.0, 3.5, 9.0, 0.3);
        particles(ParticleTypes.EXPLOSION_EMITTER, c.x, floor + 0.5, c.z, 1, 0, 0, 0, 0);
        particles(ParticleTypes.GUST_EMITTER_LARGE, c.x, floor + 0.5, c.z, 1, 0, 0, 0, 0);
        BlockStateParticleEffect earth = groundDebris(BlockPos.ofFloored(c.x, floor - 0.5, c.z));
        for (int i = 0; i < 48; i++) {
            double a = i * Math.PI * 2 / 48;
            double cos = Math.cos(a), sin = Math.sin(a);
            for (double r = 3; r <= CROWN_REACH + 3; r += 2) {
                particles(earth, c.x + cos * r, floor + 0.2, c.z + sin * r, 2, 0.3, 0.3, 0.3, 0.35);
            }
            particles(ParticleTypes.CLOUD, c.x + cos * CROWN_REACH, floor + 0.4, c.z + sin * CROWN_REACH, 0, cos, 0.04, sin, 0.9);
        }
        // A wave of dust rolling out from under it, and big puffs where it struck
        for (int i = 0; i < 40; i++) {
            double a = i * Math.PI * 2 / 40;
            double cos = Math.cos(a), sin = Math.sin(a), rr = CROWN_REACH * 0.7;
            particles(ParticleTypes.CAMPFIRE_COSY_SMOKE, c.x + cos * rr, floor + 0.6, c.z + sin * rr, 0, cos, 0.03, sin, 0.18);
        }
        Random r = world.getRandom();
        for (int i = 0; i < 12; i++) {
            double a = r.nextDouble() * Math.PI * 2, d = r.nextDouble() * CROWN_REACH * 0.8;
            particles(ParticleTypes.EXPLOSION, c.x + Math.cos(a) * d, floor + 1.0, c.z + Math.sin(a) * d, 1, 0, 0, 0, 0);
        }

        // Chunks of earth, leaves and splintered wood flung out of the crash
        BlockState topsoil = surface(BlockPos.ofFloored(c.x, floor - 0.5, c.z));
        Vec3d along = c.subtract(pivot);
        Vec3d across = new Vec3d(-along.z, 0, along.x).normalize();
        for (int i = 0; i < 34; i++) {
            Vec3d from, out;
            if (i < 24) {
                double a = r.nextDouble() * Math.PI * 2, d = Math.sqrt(r.nextDouble()) * CROWN_REACH * 0.85;
                from = new Vec3d(c.x + Math.cos(a) * d, floor + 0.6, c.z + Math.sin(a) * d);
                out = new Vec3d(Math.cos(a), 0, Math.sin(a));
            } else {
                Vec3d p = at(new Vector3f(0, range(r, 0.2f, 0.8f) * CROWN_Y * S, 0));
                from = new Vec3d(p.x, groundY(p) + 0.6, p.z);
                out = across.multiply(r.nextBoolean() ? 1 : -1);
            }
            Vec3d v = out.multiply(range(r, 0.2f, 0.55f)).add(0, range(r, 0.45f, 0.95f), 0);
            float roll = r.nextFloat();
            BlockState state = roll < 0.35f ? topsoil : roll < 0.55f ? Blocks.DIRT.getDefaultState()
                    : roll < 0.82f ? Blocks.OAK_LEAVES.getDefaultState().with(LeavesBlock.PERSISTENT, true)
                    : Blocks.OAK_WOOD.getDefaultState();
            throwDebris(r, state, from, v);
        }
        // ...and all along the trunk where it slammed down
        for (float s = 2; s < (CROWN_Y - 4) * S; s += 2) {
            Vec3d p = at(new Vector3f(0, s, 0));
            double g = groundY(p);
            particles(earth, p.x, g + 0.3, p.z, 10, 1.4, 0.3, 1.4, 0.3);
            particles(ParticleTypes.DUST_PLUME, p.x, g + 0.3, p.z, 2, 1.2, 0.2, 1.2, 0.05);
        }

        strike(c, floor);
    }

    /** The crash rolling back off the land around it. */
    private void echo(int t) {
        if (t != 7 && t != 16 && t != 28) return;
        Vec3d c = at(crown);
        float fade = t == 7 ? 3.0f : t == 16 ? 2.0f : 1.3f;
        sound(c, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), fade, 0.42f);
        sound(c, SoundEvents.ITEM_MACE_SMASH_GROUND_HEAVY, fade * 0.7f, 0.4f);
    }

    /** Crushes everything under the tree and throws everything around it. */
    private void strike(Vec3d crownAt, double floor) {
        PlayerEntity caster = world.getPlayerByUuid(owner);
        Vec3d foot = at(new Vector3f(0, 1, 0));
        Vec3d end = at(new Vector3f(0, (CROWN_Y - 3) * S, 0));
        double reach = CROWN_REACH + SHOCK_RANGE + 2;
        Box area = new Box(foot, crownAt).expand(reach, 12, reach);
        var type = world.getRegistryManager().get(RegistryKeys.DAMAGE_TYPE).entryOf(DamageTypes.FALLING_BLOCK);

        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, area, e -> e.isAlive() && !e.isSpectator())) {
            if (friendly(e, caster)) continue;
            Vec3d body = e.getPos().add(0, e.getHeight() / 2, 0);

            Vec3d onTrunk = closest(foot, end, body);
            double trunkGap = body.distanceTo(onTrunk) - TRUNK_REACH - e.getWidth() / 2;
            double dx = body.x - crownAt.x, dz = body.z - crownAt.z;
            boolean level = e.getY() > floor - 3 && e.getY() < crownAt.y + 4;
            double crownGap = level ? Math.sqrt(dx * dx + dz * dz) - CROWN_REACH - e.getWidth() / 2 : Double.MAX_VALUE;
            double gap = Math.min(trunkGap, crownGap);
            if (gap > SHOCK_RANGE) continue;

            // The caster's kill, but with no source (so no position): no shield can turn a falling tree
            DamageSource source = new DamageSource(type, null, caster);
            e.timeUntilRegen = 0;
            if (gap <= 0) {
                // Under it: crushed and pinned
                e.damage(source, CRUSH_DAMAGE);
                e.setVelocity(0, -0.6, 0);
                if (e.isAlive()) e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 50, 3, false, false, true));
            } else {
                // Caught by the blast: hurt and hurled away from where it hit
                float f = (float) (gap / SHOCK_RANGE);
                e.damage(source, MathHelper.lerp(f, SHOCK_MAX, SHOCK_MIN));
                Vec3d from = crownGap <= trunkGap ? crownAt : onTrunk;
                Vec3d away = new Vec3d(body.x - from.x, 0, body.z - from.z);
                away = away.lengthSquared() < 1.0e-4 ? Vec3d.ZERO : away.normalize();
                double push = 0.4 + 1.4 * (1 - f);
                e.setVelocity(away.x * push, 0.25 + 0.6 * (1 - f), away.z * push);
            }
            e.velocityModified = true;
        }
    }

    private static Vec3d closest(Vec3d a, Vec3d b, Vec3d p) {
        Vec3d ab = b.subtract(a);
        double t = MathHelper.clamp(p.subtract(a).dotProduct(ab) / ab.lengthSquared(), 0, 1);
        return a.add(ab.multiply(t));
    }

    private boolean friendly(Entity e, PlayerEntity caster) {
        if (e.getUuid().equals(owner) || e instanceof IllusionEntity) return true;
        if (e instanceof TameableEntity pet && owner.equals(pet.getOwnerUuid())) return true;
        return caster != null && e.isTeammate(caster);
    }

    // ---------------------------------------------------------------------

    /** The block making up the ground at {@code pos}, or plain dirt if there's nothing solid there. */
    private BlockState surface(BlockPos pos) {
        BlockState s = world.getBlockState(pos);
        return s.isAir() || !s.isOpaqueFullCube(world, pos) ? Blocks.DIRT.getDefaultState() : s;
    }

    private BlockStateParticleEffect groundDebris(BlockPos pos) {
        BlockState s = world.getBlockState(pos);
        return new BlockStateParticleEffect(ParticleTypes.BLOCK, s.isAir() ? Blocks.DIRT.getDefaultState() : s);
    }

    /**
     * Particles sent to everyone within {@link #PARTICLE_RANGE} blocks, not just the usual 32: the crown comes down
     * thirty-odd blocks from the caster, and the whole point is to be seen from a distance.
     */
    private void particles(ParticleEffect effect, double x, double y, double z, int count, double dx, double dy, double dz, double speed) {
        for (net.minecraft.server.network.ServerPlayerEntity viewer : world.getPlayers()) {
            if (viewer.squaredDistanceTo(x, y, z) > PARTICLE_RANGE * PARTICLE_RANGE) continue;
            world.spawnParticles(viewer, effect, true, x, y, z, count, dx, dy, dz, speed);
        }
    }

    private void sound(Vec3d at, SoundEvent sound, float volume, float pitch) {
        world.playSound(null, at.x, at.y, at.z, sound, SoundCategory.PLAYERS, volume, pitch);
    }
}
