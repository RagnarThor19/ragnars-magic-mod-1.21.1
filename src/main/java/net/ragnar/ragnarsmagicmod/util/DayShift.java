package net.ragnar.ragnarsmagicmod.util;

import net.minecraft.util.math.MathHelper;

/** Timing shared by the server and the clients for the Tome of Dusk and Dawn, so the sky moves in step. */
public final class DayShift {
    private DayShift() {}

    public static final int DURATION = 140; // 7 seconds of the sky wheeling overhead
    public static final long HALF_DAY = 12000;

    /** True between sunrise and sunset. */
    public static boolean isDay(long timeOfDay) {
        long t = Math.floorMod(timeOfDay, 24000L);
        return t < 12500 || t >= 23500;
    }

    /** The exact opposite time: half a day forward. Noon becomes midnight, dusk becomes dawn, and so on. */
    public static long target(long timeOfDay) {
        return timeOfDay + HALF_DAY;
    }

    /** Slow to start, racing through the middle, settling gently at the end. */
    public static double ease(double t) {
        t = MathHelper.clamp(t, 0.0, 1.0);
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    public static double timeAt(long start, long end, double progress) {
        return start + (end - start) * ease(progress);
    }
}
