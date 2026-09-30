package net.ragnar.ragnarsmagicmod.util.building;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure shape maths for the Tome of Building. Cells are in local space: u across (to your right),
 * v up, w away from you, each starting at 0 inside the shape's bounding box.
 */
public final class ShapeGeometry {
    private ShapeGeometry() {}

    /** Size of the bounding box as {u, v, w}. */
    public static int[] bounds(BuildShape shape, int width, int height, int length) {
        return switch (shape) {
            case WALL -> new int[]{width, height, 1};
            case FLOOR -> new int[]{width, 1, length};
            case BOX -> new int[]{width, height, length};
            case CIRCLE -> new int[]{width, 1, width};
            case CYLINDER -> new int[]{width, height, width};
            case SPHERE -> new int[]{width, width, width};
            case DOME -> new int[]{width, (width + 1) / 2, width};
            case PYRAMID -> new int[]{width, (width + 1) / 2, width};
            case STAIRS -> new int[]{width, length, length};
        };
    }

    /** Every cell of the shape, as {u, v, w}. Never empty for sizes of at least 1. */
    public static List<int[]> cells(BuildShape shape, int width, int height, int length, boolean hollow) {
        int[] b = bounds(shape, width, height, length);
        List<int[]> out = new ArrayList<>();
        for (int v = 0; v < b[1]; v++) {
            for (int w = 0; w < b[2]; w++) {
                for (int u = 0; u < b[0]; u++) {
                    if (contains(shape, width, height, length, hollow, u, v, w)) out.add(new int[]{u, v, w});
                }
            }
        }
        return out;
    }

    public static boolean contains(BuildShape shape, int width, int height, int length, boolean hollow, int u, int v, int w) {
        int[] b = bounds(shape, width, height, length);
        if (u < 0 || v < 0 || w < 0 || u >= b[0] || v >= b[1] || w >= b[2]) return false;
        return switch (shape) {
            case WALL -> !hollow || edge(u, width) || edge(v, height);
            case FLOOR -> !hollow || edge(u, width) || edge(w, length);
            case BOX -> !hollow || edge(u, width) || edge(v, height) || edge(w, length);
            case CIRCLE, CYLINDER -> inDisk(width, u, w) && (!hollow || ringEdge(width, u, w));
            case SPHERE -> inBall(width, u, v, w, false)
                    && (!hollow || !inBall(width, u + 1, v, w, false) || !inBall(width, u - 1, v, w, false)
                    || !inBall(width, u, v + 1, w, false) || !inBall(width, u, v - 1, w, false)
                    || !inBall(width, u, v, w + 1, false) || !inBall(width, u, v, w - 1, false));
            // The dome is the top half of a ball centred on its bottom layer; its floor stays open when hollow
            case DOME -> inBall(width, u, v, w, true)
                    && (!hollow || !inBall(width, u + 1, v, w, true) || !inBall(width, u - 1, v, w, true)
                    || !inBall(width, u, v + 1, w, true) || !inBall(width, u, v, w + 1, true) || !inBall(width, u, v, w - 1, true));
            case PYRAMID -> inPyramid(width, u, v, w)
                    && (!hollow || !inPyramid(width, u, v + 1, w) || u == v || w == v || u == width - 1 - v || w == width - 1 - v);
            case STAIRS -> hollow ? v == w : v <= w;
        };
    }

    private static boolean edge(int i, int size) {
        return i == 0 || i == size - 1;
    }

    /** A disk that looks round at small sizes: 3 is a plus, 5 is 3-5-5-5-3, 7 is 3-5-7-7-7-5-3. */
    public static boolean inDisk(int diameter, int u, int w) {
        if (u < 0 || w < 0 || u >= diameter || w >= diameter) return false;
        double c = (diameter - 1) / 2.0;
        double du = u - c, dw = w - c;
        double r2 = diameter * diameter / 4.0 - (diameter >= 3 ? 0.3 : 0.0);
        return du * du + dw * dw <= r2;
    }

    private static boolean ringEdge(int diameter, int u, int w) {
        return !inDisk(diameter, u + 1, w) || !inDisk(diameter, u - 1, w)
                || !inDisk(diameter, u, w + 1) || !inDisk(diameter, u, w - 1);
    }

    /**
     * A ball of the given diameter. With {@code dome} set the centre sits on the middle of layer 0 and only
     * layers at or above it count, so layer 0 is the widest ring.
     */
    private static boolean inBall(int diameter, int u, int v, int w, boolean dome) {
        if (u < 0 || w < 0 || u >= diameter || w >= diameter || v < 0) return false;
        double c = (diameter - 1) / 2.0;
        double du = u - c, dw = w - c;
        double dv = dome ? v : v - c;
        if (!dome && v >= diameter) return false;
        double r2 = diameter * diameter / 4.0;
        return du * du + dv * dv + dw * dw <= r2;
    }

    private static boolean inPyramid(int base, int u, int v, int w) {
        if (v < 0) return false;
        int lo = v, hi = base - 1 - v;
        if (lo > hi) return false;
        return u >= lo && u <= hi && w >= lo && w <= hi;
    }
}
