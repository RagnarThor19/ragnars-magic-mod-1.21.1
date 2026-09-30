package net.ragnar.ragnarsmagicmod.util.building;

import java.util.Random;

/**
 * Picks which palette block goes in each cell. Pure logic so it can be tested without the game.
 */
public final class BlockMixer {
    private BlockMixer() {}

    public enum Pattern {
        RANDOM("Random", "Blocks are scattered at random. Higher weight = more common."),
        LAYERED("Layered", "Blocks gather at their layer, blending softly. Flat shapes: near to far."),
        CHECKER("Checker", "Blocks alternate like a checkerboard.");

        public final String label;
        public final String description;

        Pattern(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public static Pattern byIndex(int i) {
            Pattern[] all = values();
            return all[Math.floorMod(i, all.length)];
        }
    }

    public enum Layer {
        BOTTOM("Bottom", 0f), MIDDLE("Middle", 0.5f), TOP("Top", 1f);

        public final String label;
        public final float center;

        Layer(String label, float center) {
            this.label = label;
            this.center = center;
        }

        public static Layer byIndex(int i) {
            Layer[] all = values();
            return all[Math.floorMod(i, all.length)];
        }
    }

    // How far each layer bleeds into its neighbours, and how much each cell's height is jittered
    private static final double LAYER_SPREAD = 0.20;
    private static final double LAYER_JITTER = 0.25;

    /**
     * Chooses a palette index per cell.
     *
     * @param heights   0..1 position of each cell along the layer axis (0 = bottom / near)
     * @param parity    per-cell integer used for the checker pattern (e.g. u + v + w)
     * @param weights   weight (1+) per palette entry
     * @param layers    preferred layer per palette entry
     * @param available how many of each entry may be used, or null for unlimited
     * @return palette index per cell, or null if the palette cannot cover every cell
     */
    public static int[] assign(float[] heights, int[] parity, Pattern pattern, int[] weights, Layer[] layers,
                               int[] available, Random random) {
        int n = heights.length, k = weights.length;
        if (k == 0) return n == 0 ? new int[0] : null;
        int[] left = available == null ? null : available.clone();
        if (left != null) {
            long total = 0;
            for (int a : left) total += Math.max(0, a);
            if (total < n) return null;
        }

        int[] out = new int[n];
        double[] chance = new double[k];
        for (int i = 0; i < n; i++) {
            int pick = -1;
            if (pattern == Pattern.CHECKER) {
                int start = Math.floorMod(parity[i], k);
                for (int step = 0; step < k && pick < 0; step++) {
                    int j = (start + step) % k;
                    if (left == null || left[j] > 0) pick = j;
                }
            } else {
                double t = heights[i];
                if (pattern == Pattern.LAYERED) t += (random.nextDouble() - 0.5) * LAYER_JITTER;
                double sum = 0;
                for (int j = 0; j < k; j++) {
                    double c = Math.max(1, weights[j]);
                    if (pattern == Pattern.LAYERED) {
                        double d = t - layers[j].center;
                        // A small floor keeps a sprinkle of every block everywhere, so layers never look ruled
                        c *= Math.exp(-(d * d) / (2 * LAYER_SPREAD * LAYER_SPREAD)) + 0.03;
                    }
                    if (left != null && left[j] <= 0) c = 0;
                    chance[j] = c;
                    sum += c;
                }
                if (sum > 0) {
                    double r = random.nextDouble() * sum;
                    for (int j = 0; j < k; j++) {
                        r -= chance[j];
                        if (r < 0 && chance[j] > 0) { pick = j; break; }
                    }
                    // Rounding can leave r a hair above zero; fall back to the last usable entry
                    if (pick < 0) for (int j = k - 1; j >= 0 && pick < 0; j--) if (chance[j] > 0) pick = j;
                }
            }
            if (pick < 0) return null;
            out[i] = pick;
            if (left != null) left[pick]--;
        }
        return out;
    }
}
