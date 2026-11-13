package net.ragnar.ragnarsmagicmod.gambling.client;

import net.minecraft.util.math.MathHelper;
import net.ragnar.ragnarsmagicmod.gambling.Gambling;

/**
 * The wheel of fortune, painted pixel by pixel like a Minecraft texture, so it sits right next to the vanilla GUI:
 * <ul>
 *   <li>slices textured like blocks - deepslate for LOSE, emerald for DOUBLE, a gold block for the one TRIPLE - each
 *       with its own speckle, divided by dark oak spokes, with a peg at the rim between every two;</li>
 *   <li>a gold rim studded with glowstone lamps that chase round while it spins and flash when it stops;</li>
 *   <li>an iron hub, and a redstone pointer at the top that gets knocked aside by every peg that passes.</li>
 * </ul>
 * Pixel (0,0) is the centre; the art is {@link #SIZE} pixels across. The same art is drawn on the HUD and, for other
 * players, as a sign over the spinner's head.
 */
public final class WheelArt {
    private WheelArt() {}

    public static final int R = 30;
    public static final int SIZE = 2 * R + 1;
    public static final double SLICE = Math.PI * 2 / Gambling.SLICES;

    @FunctionalInterface
    public interface Sink {
        void pixel(int x, int y, int argb);
    }

    // Block palettes: base, light, dark
    private static final int[] DEEPSLATE = {0x45444C, 0x55545D, 0x34333A};
    private static final int[] DEEPSLATE_B = {0x3C3B42, 0x4B4A52, 0x2D2C32};
    private static final int[] EMERALD = {0x17C552, 0x41F07B, 0x0E8A3A};
    private static final int[] GOLD = {0xF7CF3A, 0xFFF08A, 0xD49A16};
    private static final int[] RIM = {0xE2AE2C, 0xF6D35A, 0xB07A16};
    private static final int[] IRON = {0xD6D6D6, 0xF0F0F0, 0xA8A8A8};
    private static final int SPOKE = 0x2B1E12;
    private static final int PEG = 0x6B4A2A;
    private static final int RIM_EDGE = 0x5E3F0C;
    private static final int LAMP_ON = 0xFFF2A6;
    private static final int LAMP_GLOW = 0xF8C95A;
    private static final int LAMP_OFF = 0x7A5222;
    private static final int LAMP_WIN = 0x7CFF9E;
    private static final int LAMP_JACKPOT = 0xFFFFFF;
    private static final int LAMP_LOSE = 0x8A2A22;

    /** Where the slices are drawn and how the lamps behave. */
    public record State(double theta, float time, boolean spinning, int landed, float flash, float alpha) {}

    /** The slice under the pointer when the wheel has turned {@code theta} (clockwise). */
    public static int sliceAt(double theta) {
        double phi = -theta % (Math.PI * 2);
        if (phi < 0) phi += Math.PI * 2;
        return Math.min(Gambling.SLICES - 1, (int) (phi / SLICE));
    }

    public static void paint(Sink sink, State st) {
        int a = MathHelper.clamp(Math.round(st.alpha * 255), 0, 255) << 24;
        if (a == 0) return;
        for (int y = -R; y <= R; y++) {
            for (int x = -R; x <= R; x++) {
                double cx = x + 0.5 - 0.5, cy = y + 0.5 - 0.5;
                double d = Math.sqrt(cx * cx + cy * cy);
                if (d > R + 0.35) continue;
                int c = color(cx, cy, d, st);
                sink.pixel(x, y, a | c);
            }
        }
    }

    private static int color(double x, double y, double d, State st) {
        double ang = Math.atan2(x, -y); // 0 at the top, clockwise
        if (ang < 0) ang += Math.PI * 2;

        // The rim
        if (d > R - 0.8) return RIM_EDGE;
        if (d > R - 3.3) {
            int lamp = (int) Math.round(ang / SLICE) % Gambling.SLICES;
            double arc = Math.abs(ang - Math.round(ang / SLICE) * SLICE) * d;
            if (arc < 1.0 && d > R - 2.6 && d < R - 1.2) return lampColor(lamp, st, true);
            if (arc < 1.9 && d > R - 3.0 && d < R - 0.9 && lampLit(lamp, st)) return lampColor(lamp, st, false);
            return speckle(RIM, (int) (ang * 40), (int) d, 7);
        }
        // A shadowed lip under the rim
        boolean lip = d > R - 4.3;

        // The hub
        if (d < 5.6) {
            if (d > 4.7) return IRON[2];
            if (x + y < -2.5) return IRON[1];
            return speckle(IRON, (int) x + 9, (int) y + 9, 3);
        }

        // The slices, turning with the wheel
        double phi = (ang - st.theta) % (Math.PI * 2);
        if (phi < 0) phi += Math.PI * 2;
        int slice = Math.min(Gambling.SLICES - 1, (int) (phi / SLICE));
        double frac = phi / SLICE - slice;
        double edge = Math.min(frac, 1 - frac) * SLICE * d;
        if (edge < 0.55) return d > R - 6 ? PEG : SPOKE;

        int pay = Gambling.WHEEL[slice];
        int[] pal = pay == 3 ? GOLD : pay == 2 ? EMERALD : (slice % 4 == 1 ? DEEPSLATE : DEEPSLATE_B);
        int c = speckle(pal, slice * 31 + (int) (frac * 5), (int) (d / 2.2), pay == 0 ? 11 : 13);
        // Darker towards the hub, like light falling across it
        float shade = 0.78f + 0.22f * (float) (d / R);
        if (lip) shade *= 0.72f;
        if (slice == st.landed && st.flash > 0) {
            float f = st.flash;
            return mix(scale(c, shade), pay == 0 ? 0xFF6060 : 0xFFFFFF, 0.55f * f);
        }
        if (st.landed >= 0 && slice != st.landed) shade *= 0.62f; // everything else dims once it stops
        return scale(c, shade);
    }

    private static boolean lampLit(int lamp, State st) {
        if (st.spinning) {
            int step = (int) (st.time * 0.9f);
            return (lamp + step) % 4 == 0;
        }
        if (st.landed < 0) return false;
        int pay = Gambling.WHEEL[st.landed];
        if (pay == 0) return false;
        return pay == 3 ? ((int) (st.time * 0.5f) + lamp) % 2 == 0 : ((int) (st.time * 0.25f)) % 2 == 0;
    }

    private static int lampColor(int lamp, State st, boolean core) {
        if (!lampLit(lamp, st)) {
            if (!st.spinning && st.landed >= 0 && Gambling.WHEEL[st.landed] == 0) return core ? LAMP_LOSE : RIM[2];
            return core ? LAMP_OFF : RIM[2];
        }
        if (!st.spinning && st.landed >= 0) {
            int pay = Gambling.WHEEL[st.landed];
            if (pay == 3) return core ? LAMP_JACKPOT : GOLD[1];
            if (pay == 2) return core ? LAMP_WIN : EMERALD[1];
        }
        return core ? LAMP_ON : LAMP_GLOW;
    }

    /** One of the palette's three tones, picked by a fixed hash so the texture stays put as it turns. */
    private static int speckle(int[] pal, int i, int j, int seed) {
        int h = i * 73856093 ^ j * 19349663 ^ seed * 83492791;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        int k = Math.floorMod(h, 10);
        return k < 6 ? pal[0] : k < 8 ? pal[1] : pal[2];
    }

    static int scale(int rgb, float k) {
        int r = MathHelper.clamp(Math.round(((rgb >> 16) & 255) * k), 0, 255);
        int g = MathHelper.clamp(Math.round(((rgb >> 8) & 255) * k), 0, 255);
        int b = MathHelper.clamp(Math.round((rgb & 255) * k), 0, 255);
        return (r << 16) | (g << 8) | b;
    }

    static int mix(int x, int y, float k) {
        int r = Math.round(MathHelper.lerp(k, (x >> 16) & 255, (y >> 16) & 255));
        int g = Math.round(MathHelper.lerp(k, (x >> 8) & 255, (y >> 8) & 255));
        int b = Math.round(MathHelper.lerp(k, x & 255, y & 255));
        return (r << 16) | (g << 8) | b;
    }

    /**
     * The pointer at the top: a redstone-red wedge with a dark outline, pointing down into the wheel, knocked
     * {@code kick} pixels sideways by a passing peg.
     */
    public static void paintPointer(Sink sink, int kick, float alpha) {
        int a = MathHelper.clamp(Math.round(alpha * 255), 0, 255) << 24;
        if (a == 0) return;
        int top = -R - 4;
        for (int row = 0; row < 8; row++) {
            int half = Math.max(0, 3 - row / 2);
            int y = top + row;
            for (int x = -half - 1; x <= half + 1; x++) {
                boolean outline = Math.abs(x) == half + 1 || row == 0 || (half == 0 && row == 7);
                int c = outline ? 0x3A0A0A : (x < 0 ? 0xE53935 : x == 0 ? 0xFF6B5E : 0xB71C1C);
                if (row == 1 && x == -half) c = 0xFF9A8A;
                sink.pixel(x + kick, y, a | c);
            }
        }
        // The rivet that holds it
        sink.pixel(kick, top + 1, a | 0xD0D0D0);
    }
}
