package net.ragnar.ragnarsmagicmod.util;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Fast, straight, single-target cutting projectiles (the sharp leaf and the air cut). Each flies along the exact
 * crosshair line and cuts the first creature whose hitbox (widened by the style's margin) it passes through,
 * doing more damage if the cut lands at head height.
 */
public final class Slicer {
    private Slicer() {}

    /** What a particular kind of cut looks like and how hard it hits. */
    public interface Style {
        double speed();
        double range();
        double hitMargin();

        /** The display entities that make up the cut (already spawned); may be empty. */
        List<DisplayEntity> spawnVisual(ServerWorld world, Vec3d pos, Vec3d dir);

        /** Called every tick with the segment flown, to move the visual. */
        void flight(ServerWorld world, List<DisplayEntity> visual, Vec3d from, Vec3d to, Vec3d dir, int age);

        /** Deals the damage; {@code head} is true when the cut landed at head height. */
        void hitEntity(ServerWorld world, PlayerEntity owner, LivingEntity target, Vec3d at, boolean head);

        void hitWall(ServerWorld world, Vec3d at, Vec3d dir);
    }

    private static final class Cut {
        final ServerWorld world;
        final UUID owner;
        final Style style;
        final Vec3d dir;
        final List<DisplayEntity> visual;
        Vec3d pos;
        double travelled = 0;
        int age = 0;

        Cut(ServerWorld world, UUID owner, Style style, Vec3d pos, Vec3d dir, List<DisplayEntity> visual) {
            this.world = world;
            this.owner = owner;
            this.style = style;
            this.pos = pos;
            this.dir = dir;
            this.visual = visual;
        }
    }

    private static final List<Cut> ACTIVE = new ArrayList<>();
    private static boolean registered = false;

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            Iterator<Cut> it = ACTIVE.iterator();
            while (it.hasNext()) {
                Cut c = it.next();
                if (c.world == world && !tick(c)) {
                    for (DisplayEntity d : c.visual) TempEntities.discard(d);
                    it.remove();
                }
            }
        });
    }

    /** Fires a cut from just in front of the player's eyes, straight along their crosshair. */
    public static void fire(ServerWorld world, PlayerEntity player, Style style) {
        ensureRegistered();
        Vec3d dir = player.getRotationVector().normalize();
        Vec3d start = player.getEyePos().add(dir.multiply(0.6));
        List<DisplayEntity> visual = style.spawnVisual(world, start, dir);
        ACTIVE.add(new Cut(world, player.getUuid(), style, start, dir, visual));
    }

    /** Returns false once the cut has hit something or run out of range. */
    private static boolean tick(Cut c) {
        ServerWorld world = c.world;
        c.age++;
        Style s = c.style;
        Vec3d from = c.pos;
        Vec3d to = from.add(c.dir.multiply(Math.min(s.speed(), s.range() - c.travelled)));

        BlockHitResult wall = world.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, net.minecraft.block.ShapeContext.absent()));
        Vec3d end = wall.getType() == HitResult.Type.BLOCK ? wall.getPos() : to;

        // First creature along the path
        LivingEntity target = null;
        Vec3d hitAt = null;
        double best = Double.MAX_VALUE;
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, new Box(from, end).expand(s.hitMargin() + 0.5),
                e -> e.isAlive() && !e.isSpectator() && !e.getUuid().equals(c.owner))) {
            var clip = e.getBoundingBox().expand(s.hitMargin()).raycast(from, end);
            if (clip.isEmpty()) continue;
            double d = from.squaredDistanceTo(clip.get());
            if (d < best) {
                best = d;
                target = e;
                hitAt = clip.get();
            }
        }

        if (target != null) {
            s.flight(world, c.visual, from, hitAt, c.dir, c.age);
            // Head height: from a little under the eyes up to the top of the hitbox
            boolean head = hitAt.y >= target.getEyeY() - 0.3;
            s.hitEntity(world, world.getPlayerByUuid(c.owner), target, hitAt, head);
            return false;
        }
        s.flight(world, c.visual, from, end, c.dir, c.age);
        if (wall.getType() == HitResult.Type.BLOCK) {
            s.hitWall(world, wall.getPos(), c.dir);
            return false;
        }
        c.pos = end;
        c.travelled += from.distanceTo(end);
        return c.travelled < s.range() - 1.0e-6;
    }
}
