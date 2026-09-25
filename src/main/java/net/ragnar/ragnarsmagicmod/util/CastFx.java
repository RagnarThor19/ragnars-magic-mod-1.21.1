package net.ragnar.ragnarsmagicmod.util;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

/** Small shared helpers for cast effects. */
public final class CastFx {
    private CastFx() {}

    /** Where the staff "fires" from: just in front of and below the eyes. */
    public static Vec3d muzzle(Entity caster) {
        return caster.getEyePos().add(caster.getRotationVector().multiply(0.9)).add(0, -0.25, 0);
    }
}
