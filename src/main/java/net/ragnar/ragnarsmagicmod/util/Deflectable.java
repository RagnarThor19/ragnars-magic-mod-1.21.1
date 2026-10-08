package net.ragnar.ragnarsmagicmod.util;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * A spell's own projectile (one that isn't a vanilla ProjectileEntity) that a Tome of Deflection pane can turn back.
 * The pane moves it to where it leaves the glass, then calls {@link #deflect}.
 */
public interface Deflectable {
    /** How it's moving this tick, in blocks a tick, or zero while it can't be deflected (still forming, stuck...). */
    Vec3d deflectVelocity();

    /** Whose it is: a pane never turns back its own caster's spells. */
    @Nullable UUID deflectOwner();

    /** How hard it falls each tick, so the pane can aim it back (0 for things that fly straight). */
    default double deflectGravity() {
        return 0;
    }

    /** It's been turned around: fly along {@code dir} (a unit vector) at about {@code speed}, belonging to {@code deflector} now. */
    void deflect(PlayerEntity deflector, Vec3d dir, double speed);
}
