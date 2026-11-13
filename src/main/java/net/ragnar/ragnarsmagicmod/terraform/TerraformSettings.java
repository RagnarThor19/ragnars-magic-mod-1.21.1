package net.ragnar.ragnarsmagicmod.terraform;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.MathHelper;

/** The Tome of Terraforming's settings: chosen in its menu on the client, sent with every cast, checked again on the server. */
public final class TerraformSettings {
    public static final int MIN_RADIUS = 1, MAX_RADIUS = 16;

    public TerraformMode mode = TerraformMode.FLATTEN;
    /** Size: the brush reaches this many blocks out from its centre (so it's 2r + 1 across). */
    public int radius = 5;
    public boolean square = false;
    /** Soft blends into the land around it; hard stops dead at the brush's edge. */
    public boolean softEdge = true;
    private final int[] strengths = new int[TerraformMode.values().length];

    public TerraformSettings() {
        for (TerraformMode m : TerraformMode.values()) strengths[m.ordinal()] = m.defaultStrength;
    }

    public int strength() {
        return strengths[mode.ordinal()];
    }

    public void setStrength(int value) {
        strengths[mode.ordinal()] = value;
        clamp();
    }

    public int diameter() {
        return radius * 2 + 1;
    }

    /** Grows or shrinks the brush by one step. Returns true if it changed. */
    public boolean resize(int direction) {
        int before = radius;
        radius = MathHelper.clamp(radius + direction, MIN_RADIUS, MAX_RADIUS);
        return radius != before;
    }

    public void clamp() {
        radius = MathHelper.clamp(radius, MIN_RADIUS, MAX_RADIUS);
        for (TerraformMode m : TerraformMode.values()) {
            int i = m.ordinal();
            strengths[i] = m.hasStrength() ? MathHelper.clamp(strengths[i], m.minStrength, m.maxStrength) : 0;
        }
    }

    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("mode", mode.name());
        nbt.putInt("radius", radius);
        nbt.putBoolean("square", square);
        nbt.putBoolean("soft", softEdge);
        nbt.putIntArray("strengths", strengths.clone());
        return nbt;
    }

    public static TerraformSettings fromNbt(NbtCompound nbt) {
        TerraformSettings s = new TerraformSettings();
        if (nbt == null) return s;
        try {
            s.mode = TerraformMode.valueOf(nbt.getString("mode"));
        } catch (IllegalArgumentException ignored) {
            // unknown or missing: keep the default
        }
        if (nbt.contains("radius")) s.radius = nbt.getInt("radius");
        s.square = nbt.getBoolean("square");
        s.softEdge = !nbt.contains("soft") || nbt.getBoolean("soft");
        int[] saved = nbt.getIntArray("strengths");
        for (int i = 0; i < Math.min(saved.length, s.strengths.length); i++) s.strengths[i] = saved[i];
        s.clamp();
        return s;
    }
}
