package net.ragnar.ragnarsmagicmod.sight.client;

import net.minecraft.client.render.VertexConsumer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.ragnar.ragnarsmagicmod.sight.OreKind;
import org.joml.Matrix4f;

import java.util.function.LongPredicate;

/**
 * Drawing for the Tome of Sight, all in {@link SightClient#XRAY}: glowing ribbons and panes that show through the
 * world. Every point is relative to the camera, so each ribbon can turn to face it.
 */
final class SightDraw {
    private SightDraw() {}

    private static final float WAVE_R = 1f, WAVE_G = 0.86f, WAVE_B = 0.52f;

    /**
     * The sonar shell, {@code t} ticks in: a slowly turning globe of latitude and longitude lines with a bright band
     * at its equator, and a faint echo trailing behind it. Fades as it nears the edge.
     */
    static void wave(VertexConsumer vc, Matrix4f pose, Vec3d c, float t) {
        double r = SightClient.waveRadius(t);
        float p = MathHelper.clamp(t / SightClient.WAVE_TICKS, 0f, 1f);
        float alpha = 0.55f * (float) Math.pow(1f - p, 0.6) + 0.12f;
        alpha *= MathHelper.clamp((SightClient.WAVE_TICKS + 8 - t) / 8f, 0f, 1f);
        if (alpha <= 0.01f) return;
        float w = 0.035f + 0.01f * (float) r;
        double turn = t * 0.035;

        for (int lat = -75; lat <= 75; lat += 15) {
            boolean equator = lat == 0;
            latitude(vc, pose, c, r, Math.toRadians(lat), turn, equator ? w * 3f : w, alpha * (equator ? 1f : 0.55f));
        }
        for (int i = 0; i < 16; i++) meridian(vc, pose, c, r, turn + Math.PI * 2 * i / 16, w, alpha * 0.4f);
        // The echo
        if (r > 2) {
            for (int lat = -60; lat <= 60; lat += 30) latitude(vc, pose, c, r * 0.8, Math.toRadians(lat), turn, w * 0.7f, alpha * 0.2f);
        }
    }

    private static void latitude(VertexConsumer vc, Matrix4f pose, Vec3d c, double r, double lat, double turn, float w, float a) {
        int n = 48;
        Vec3d prev = null;
        for (int i = 0; i <= n; i++) {
            double lon = turn + Math.PI * 2 * i / n;
            Vec3d pt = c.add(sphere(r, lat, lon));
            if (prev != null) ribbon(vc, pose, prev, pt, w, WAVE_R, WAVE_G, WAVE_B, a);
            prev = pt;
        }
    }

    private static void meridian(VertexConsumer vc, Matrix4f pose, Vec3d c, double r, double lon, float w, float a) {
        int n = 24;
        Vec3d prev = null;
        for (int i = 0; i <= n; i++) {
            double lat = -Math.PI / 2 * 0.92 + Math.PI * 0.92 * i / n;
            Vec3d pt = c.add(sphere(r, lat, lon));
            if (prev != null) ribbon(vc, pose, prev, pt, w, WAVE_R, WAVE_G, WAVE_B, a);
            prev = pt;
        }
    }

    private static Vec3d sphere(double r, double lat, double lon) {
        double cl = Math.cos(lat);
        return new Vec3d(r * cl * Math.cos(lon), r * Math.sin(lat), r * cl * Math.sin(lon));
    }

    /**
     * One ore block, {@code scale} times its size: a faint pane over each side that isn't up against more of the same
     * vein, and bright edges around those panes - leaving out the seams between flat neighbouring panes, so a vein
     * reads as one glowing shape.
     */
    static void ore(VertexConsumer vc, Matrix4f pose, Vec3d c, float scale, BlockPos pos, LongPredicate isOre,
                    OreKind kind, float bright) {
        float h = 0.5f * scale + 0.005f;
        float r = kind.red(), g = kind.green(), b = kind.blue();
        for (Direction d : Direction.values()) {
            if (isOre.test(pos.offset(d).asLong())) continue;
            Vec3i n = d.getVector();
            Vec3i u = d.getAxis() == Direction.Axis.Y ? new Vec3i(1, 0, 0) : new Vec3i(0, 1, 0);
            Vec3i v = n.crossProduct(u);
            Vec3d nd = Vec3d.of(n), ud = Vec3d.of(u), vd = Vec3d.of(v);
            Vec3d f = c.add(nd.multiply(h));
            Vec3d p0 = f.add(ud.multiply(-h)).add(vd.multiply(-h));
            Vec3d p1 = f.add(ud.multiply(h)).add(vd.multiply(-h));
            Vec3d p2 = f.add(ud.multiply(h)).add(vd.multiply(h));
            Vec3d p3 = f.add(ud.multiply(-h)).add(vd.multiply(h));
            float fa = 0.16f * bright;
            vertex(vc, pose, p0, r, g, b, fa);
            vertex(vc, pose, p1, r, g, b, fa);
            vertex(vc, pose, p2, r, g, b, fa);
            vertex(vc, pose, p3, r, g, b, fa);
            float ea = 0.95f * bright;
            if (!seam(pos, n, new Vec3i(-v.getX(), -v.getY(), -v.getZ()), isOre)) ribbon(vc, pose, p0, p1, 0.06f, r, g, b, ea);
            if (!seam(pos, n, u, isOre)) ribbon(vc, pose, p1, p2, 0.06f, r, g, b, ea);
            if (!seam(pos, n, v, isOre)) ribbon(vc, pose, p2, p3, 0.06f, r, g, b, ea);
            if (!seam(pos, n, new Vec3i(-u.getX(), -u.getY(), -u.getZ()), isOre)) ribbon(vc, pose, p3, p0, 0.06f, r, g, b, ea);
        }
    }

    /**
     * True if the edge of the face facing {@code n}, on its {@code side}, is just a seam: the vein carries on that way
     * and its face there lies flat with this one.
     */
    private static boolean seam(BlockPos pos, Vec3i n, Vec3i side, LongPredicate isOre) {
        BlockPos next = pos.add(side);
        return isOre.test(next.asLong()) && !isOre.test(next.add(n).asLong());
    }

    /** A thin ring facing the camera, {@code size} across: the ripple when an ore is found. */
    static void ring(VertexConsumer vc, Matrix4f pose, Vec3d c, Vec3d right, Vec3d up, float size, float width,
                     OreKind kind, float alpha) {
        int n = 24;
        float in = Math.max(0f, size - width);
        for (int i = 0; i < n; i++) {
            double a0 = Math.PI * 2 * i / n, a1 = Math.PI * 2 * (i + 1) / n;
            Vec3d d0 = right.multiply(Math.cos(a0)).add(up.multiply(Math.sin(a0)));
            Vec3d d1 = right.multiply(Math.cos(a1)).add(up.multiply(Math.sin(a1)));
            vertex(vc, pose, c.add(d0.multiply(in)), kind.red(), kind.green(), kind.blue(), alpha);
            vertex(vc, pose, c.add(d0.multiply(size)), kind.red(), kind.green(), kind.blue(), alpha);
            vertex(vc, pose, c.add(d1.multiply(size)), kind.red(), kind.green(), kind.blue(), alpha);
            vertex(vc, pose, c.add(d1.multiply(in)), kind.red(), kind.green(), kind.blue(), alpha);
        }
    }

    /** A soft glow facing the camera: a few turned squares stacked up. */
    static void glow(VertexConsumer vc, Matrix4f pose, Vec3d c, Vec3d right, Vec3d up, float size, OreKind kind, float alpha) {
        for (int layer = 0; layer < 3; layer++) {
            float s = size * (1f - layer * 0.28f);
            double turn = layer * Math.PI / 6;
            Vec3d x = right.multiply(Math.cos(turn) * s).add(up.multiply(Math.sin(turn) * s));
            Vec3d y = up.multiply(Math.cos(turn) * s).subtract(right.multiply(Math.sin(turn) * s));
            float a = alpha * (0.35f + layer * 0.3f);
            vertex(vc, pose, c.subtract(x).subtract(y), kind.red(), kind.green(), kind.blue(), a);
            vertex(vc, pose, c.add(x).subtract(y), kind.red(), kind.green(), kind.blue(), a);
            vertex(vc, pose, c.add(x).add(y), kind.red(), kind.green(), kind.blue(), a);
            vertex(vc, pose, c.subtract(x).add(y), kind.red(), kind.green(), kind.blue(), a);
        }
    }

    /** A flat strip from {@code a} to {@code b}, {@code w} wide, turned to face the camera. */
    private static void ribbon(VertexConsumer vc, Matrix4f pose, Vec3d a, Vec3d b, float w,
                               float r, float g, float bl, float alpha) {
        Vec3d side = b.subtract(a).crossProduct(a.add(b).multiply(0.5));
        if (side.lengthSquared() < 1e-10) return;
        side = side.normalize().multiply(w * 0.5);
        vertex(vc, pose, a.subtract(side), r, g, bl, alpha);
        vertex(vc, pose, a.add(side), r, g, bl, alpha);
        vertex(vc, pose, b.add(side), r, g, bl, alpha);
        vertex(vc, pose, b.subtract(side), r, g, bl, alpha);
    }

    private static void vertex(VertexConsumer vc, Matrix4f pose, Vec3d p, float r, float g, float b, float a) {
        vc.vertex(pose, (float) p.x, (float) p.y, (float) p.z).color(r, g, b, MathHelper.clamp(a, 0f, 1f));
    }
}
