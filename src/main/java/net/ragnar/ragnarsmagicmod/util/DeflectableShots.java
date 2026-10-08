package net.ragnar.ragnarsmagicmod.util;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Spell projectiles that aren't entities at all - a spell moves them itself and draws them with display entities
 * (sharp leaves, air cuts, stone cannons, quiver arrows, swords, spraying arrows, booming orbs) - but that a Tome of
 * Deflection pane can still turn back. Each spell registers where its shots in flight are kept; for projectiles that
 * are entities, see {@link Deflectable}.
 */
public final class DeflectableShots {
    private DeflectableShots() {}

    public interface Shot {
        ServerWorld world();

        /** Where its middle is now. */
        Vec3d pos();

        /** How it's moving this tick, in blocks a tick, or zero while it can't be deflected (charging, stuck...). */
        Vec3d deflectVelocity();

        /** Whose it is: a pane never turns back its own caster's spells. */
        @Nullable UUID deflectOwner();

        /** About how far it reaches from its middle, so a near miss of the glass still counts. */
        default double radius() {
            return 0.25;
        }

        /** How hard it falls each tick, so the pane can aim it back (0 for things that fly straight). */
        default double deflectGravity() {
            return 0;
        }

        /**
         * It's been turned around: from {@code at}, fly along {@code dir} (a unit vector) at about {@code speed},
         * belonging to {@code deflector} now. {@code aimedAt} is who the deflector was aiming at, if anyone, for
         * shots that home in on a target.
         */
        void deflect(PlayerEntity deflector, Vec3d at, Vec3d dir, double speed, @Nullable LivingEntity aimedAt);
    }

    private static final List<Supplier<? extends Collection<? extends Shot>>> SOURCES = new ArrayList<>();

    /** Called once by each spell with the list it keeps its shots in flight in. */
    public static void register(Supplier<? extends Collection<? extends Shot>> source) {
        SOURCES.add(source);
    }

    /** Every shot in flight in {@code world} right now (a copy, so they can be deflected while looping over it). */
    public static List<Shot> in(ServerWorld world) {
        List<Shot> out = new ArrayList<>();
        for (var source : SOURCES) {
            for (Shot s : source.get()) if (s.world() == world) out.add(s);
        }
        return out;
    }
}
