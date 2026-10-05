package net.ragnar.ragnarsmagicmod.beaming.client;

import net.minecraft.client.render.VertexConsumer;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/** Drawing for the Tome of Beaming: square bars and square rings at any angle. Points are relative to the camera. */
final class BeamDraw {
    private BeamDraw() {}

    /** A square bar from {@code a} to {@code b}, {@code half} * 2 across, turned {@code roll} radians about its length. */
    static void bar(VertexConsumer vc, Matrix4f pose, Vec3d a, Vec3d b, float half, float roll,
                    float r, float g, float bl, float alpha) {
        Vec3d d = b.subtract(a);
        double len = d.length();
        if (len < 1e-5) return;
        Vec3d dir = d.multiply(1 / len);
        Vec3d[] uv = axes(dir, roll);
        Vec3d u = uv[0].multiply(half), v = uv[1].multiply(half);
        Vec3d[] c = {
                a.subtract(u).subtract(v), a.add(u).subtract(v), a.add(u).add(v), a.subtract(u).add(v),
                b.subtract(u).subtract(v), b.add(u).subtract(v), b.add(u).add(v), b.subtract(u).add(v)};
        quad(vc, pose, c[0], c[1], c[5], c[4], r, g, bl, alpha);
        quad(vc, pose, c[1], c[2], c[6], c[5], r, g, bl, alpha);
        quad(vc, pose, c[2], c[3], c[7], c[6], r, g, bl, alpha);
        quad(vc, pose, c[3], c[0], c[4], c[7], r, g, bl, alpha);
        quad(vc, pose, c[0], c[1], c[2], c[3], r, g, bl, alpha);
        quad(vc, pose, c[4], c[5], c[6], c[7], r, g, bl, alpha);
    }

    /** A hollow square ring around {@code center}, square to {@code dir}, {@code size} from middle to edge. */
    static void frame(VertexConsumer vc, Matrix4f pose, Vec3d center, Vec3d dir, float size, float thick, float roll,
                      float r, float g, float b, float alpha) {
        Vec3d[] uv = axes(dir, roll);
        Vec3d u = uv[0].multiply(size), v = uv[1].multiply(size);
        Vec3d c0 = center.subtract(u).subtract(v), c1 = center.add(u).subtract(v), c2 = center.add(u).add(v), c3 = center.subtract(u).add(v);
        bar(vc, pose, c0, c1, thick, 0f, r, g, b, alpha);
        bar(vc, pose, c1, c2, thick, 0f, r, g, b, alpha);
        bar(vc, pose, c2, c3, thick, 0f, r, g, b, alpha);
        bar(vc, pose, c3, c0, thick, 0f, r, g, b, alpha);
    }

    /** Two axes square to {@code dir} and each other, turned {@code roll} radians about it. */
    private static Vec3d[] axes(Vec3d dir, float roll) {
        Vec3d u0 = Math.abs(dir.y) < 0.95 ? dir.crossProduct(new Vec3d(0, 1, 0)) : dir.crossProduct(new Vec3d(1, 0, 0));
        u0 = u0.normalize();
        Vec3d v0 = dir.crossProduct(u0).normalize();
        double cs = Math.cos(roll), sn = Math.sin(roll);
        return new Vec3d[]{u0.multiply(cs).add(v0.multiply(sn)), v0.multiply(cs).subtract(u0.multiply(sn))};
    }

    private static void quad(VertexConsumer vc, Matrix4f pose, Vec3d p0, Vec3d p1, Vec3d p2, Vec3d p3,
                             float r, float g, float b, float a) {
        vertex(vc, pose, p0, r, g, b, a);
        vertex(vc, pose, p1, r, g, b, a);
        vertex(vc, pose, p2, r, g, b, a);
        vertex(vc, pose, p3, r, g, b, a);
    }

    private static void vertex(VertexConsumer vc, Matrix4f pose, Vec3d p, float r, float g, float b, float a) {
        vc.vertex(pose, (float) p.x, (float) p.y, (float) p.z).color(r, g, b, MathHelper.clamp(a, 0f, 1f));
    }
}
