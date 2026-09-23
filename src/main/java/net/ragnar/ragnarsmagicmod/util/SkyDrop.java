package net.ragnar.ragnarsmagicmod.util;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Something heavy conjured in the air above a target: it hangs and shakes, then is fired straight
 * down, hitting whatever it passes through, and finally reacts to the ground.
 * Rendered with block display entities so it can shake and fall smoothly at any speed.
 * Subclasses provide the look and what happens on each phase.
 */
public abstract class SkyDrop {
    private static final List<SkyDrop> ACTIVE = new ArrayList<>();
    private static final int MAX_FALL_TICKS = 100;

    protected enum Phase { SHAKE, FALL, LANDED }

    public record Piece(BlockState state, float scale, float yOffset) {}

    protected final ServerWorld world;
    protected final UUID ownerId;
    protected final Vec3d anchor;       // bottom centre while hanging
    protected final double halfWidth;
    protected final double height;
    protected final int shakeTicks;
    private final List<DisplayEntity.BlockDisplayEntity> displays = new ArrayList<>();
    private final Set<UUID> struck = new HashSet<>();

    protected Vec3d pos;                // bottom centre, current
    protected Phase phase = Phase.SHAKE;
    protected int age = 0;
    protected int phaseAge = 0;
    private double velY;

    protected SkyDrop(ServerWorld world, PlayerEntity owner, Vec3d anchor, double halfWidth, double height, int shakeTicks) {
        this.world = world;
        this.ownerId = owner != null ? owner.getUuid() : null;
        this.anchor = anchor;
        this.pos = anchor;
        this.halfWidth = halfWidth;
        this.height = height;
        this.shakeTicks = shakeTicks;
    }

    /** Hooks up the ticker. Call once at init. */
    public static void register() {
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            Iterator<SkyDrop> it = ACTIVE.iterator();
            while (it.hasNext()) {
                SkyDrop drop = it.next();
                if (drop.world == world && !drop.tick()) {
                    drop.discardDisplays();
                    it.remove();
                }
            }
        });
    }

    /** Spawns the visuals and starts the drop. */
    public final void launch(List<Piece> pieces) {
        for (Piece piece : pieces) {
            DisplayEntity.BlockDisplayEntity display = EntityType.BLOCK_DISPLAY.create(world);
            if (display == null) continue;
            display.setBlockState(piece.state());
            float s = piece.scale();
            display.setTransformation(new AffineTransformation(
                    new Vector3f(-s / 2f, piece.yOffset(), -s / 2f), new Quaternionf(), new Vector3f(s, s, s), new Quaternionf()));
            display.setTeleportDuration(1);   // client interpolates each move, so shaking and falling look smooth
            display.setViewRange(2.0f);
            display.refreshPositionAndAngles(pos.x, pos.y, pos.z, 0f, 0f);
            TempEntities.track(display);
            world.spawnEntity(display);
            displays.add(display);
        }
        ACTIVE.add(this);
        onStart();
    }

    private boolean tick() {
        age++;
        phaseAge++;
        switch (phase) {
            case SHAKE -> {
                double progress = phaseAge / (double) shakeTicks;
                double amp = 0.02 + 0.1 * progress * progress;
                moveTo(anchor.add((world.random.nextDouble() * 2 - 1) * amp,
                        (world.random.nextDouble() * 2 - 1) * amp * 0.4,
                        (world.random.nextDouble() * 2 - 1) * amp));
                onShake(progress);
                if (phaseAge >= shakeTicks) {
                    moveTo(anchor);
                    setPhase(Phase.FALL);
                    velY = initialSpeed();
                    onRelease();
                }
            }
            case FALL -> {
                velY = Math.min(maxSpeed(), velY + acceleration());
                Vec3d from = pos;
                Vec3d to = pos.add(0, -velY, 0);

                BlockHitResult ground = world.raycast(new RaycastContext(from.add(0, 0.05, 0), to,
                        RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, net.minecraft.block.ShapeContext.absent()));
                if (ground.getType() == HitResult.Type.BLOCK) to = new Vec3d(pos.x, ground.getPos().y, pos.z);

                strikeAlong(from, to);
                moveTo(to);
                onFall(from, to);

                if (ground.getType() == HitResult.Type.BLOCK) {
                    setPhase(Phase.LANDED);
                    onImpact(ground);
                } else if (phaseAge > MAX_FALL_TICKS) {
                    return false;
                }
            }
            case LANDED -> {
                if (!onLandedTick(phaseAge)) return false;
            }
        }
        return true;
    }

    /** Anything overlapping the column swept this tick gets hit once. */
    private void strikeAlong(Vec3d from, Vec3d to) {
        Box sweep = new Box(pos.x - halfWidth, to.y, pos.z - halfWidth, pos.x + halfWidth, from.y + height, pos.z + halfWidth);
        PlayerEntity owner = owner();
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, sweep, e -> e.isAlive() && e != owner && !e.isSpectator())) {
            if (struck.add(e.getUuid())) onStrike(e);
        }
    }

    protected final void setPhase(Phase next) {
        phase = next;
        phaseAge = 0;
    }

    protected final void moveTo(Vec3d p) {
        pos = p;
        for (DisplayEntity.BlockDisplayEntity d : displays) d.setPosition(p.x, p.y, p.z);
    }

    protected final PlayerEntity owner() {
        return ownerId != null ? world.getPlayerByUuid(ownerId) : null;
    }

    protected final void discardDisplays() {
        for (DisplayEntity.BlockDisplayEntity d : displays) TempEntities.discard(d);
        displays.clear();
    }

    // --- Tuning & hooks ---
    protected abstract double initialSpeed();
    protected abstract double acceleration();
    protected abstract double maxSpeed();

    protected void onStart() {}
    protected void onShake(double progress) {}
    protected void onRelease() {}
    protected void onFall(Vec3d from, Vec3d to) {}
    protected abstract void onStrike(LivingEntity target);
    protected abstract void onImpact(BlockHitResult ground);
    /** Called each tick after landing; return false to finish (visuals are removed). */
    protected boolean onLandedTick(int ticksSinceImpact) { return false; }

    // --- Placement helper ---

    /**
     * Where to hang a drop of the given height above {@code ground}: {@code hover} blocks up,
     * or just under the ceiling if something is in the way.
     */
    public static Vec3d anchorAbove(ServerWorld world, Vec3d ground, double hover, double dropHeight) {
        Vec3d top = ground.add(0, hover + dropHeight, 0);
        BlockHitResult ceiling = world.raycast(new RaycastContext(ground.add(0, 0.1, 0), top,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, net.minecraft.block.ShapeContext.absent()));
        double y = ground.y + hover;
        if (ceiling.getType() == HitResult.Type.BLOCK) {
            y = Math.min(y, ceiling.getPos().y - dropHeight - 0.1);
        }
        return new Vec3d(ground.x, Math.max(ground.y + 1.5, y), ground.z);
    }

    /** The ground under where the player is aiming (the aimed point if nothing is below it within range). */
    public static Vec3d aimedGround(ServerWorld world, PlayerEntity player, double range) {
        HitResult hit = player.raycast(range, 0f, false);
        // Step back off the hit face so aiming at a wall picks the ground in front of it
        Vec3d aim = hit.getType() == HitResult.Type.BLOCK
                ? hit.getPos().subtract(player.getRotationVector().multiply(0.3))
                : player.getEyePos().add(player.getRotationVector().multiply(range));
        BlockHitResult down = world.raycast(new RaycastContext(aim.add(0, 0.5, 0), aim.add(0, -range, 0),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, net.minecraft.block.ShapeContext.absent()));
        return down.getType() == HitResult.Type.BLOCK ? down.getPos() : aim;
    }
}
