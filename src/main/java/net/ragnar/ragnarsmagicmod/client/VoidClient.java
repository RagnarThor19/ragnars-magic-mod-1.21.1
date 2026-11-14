package net.ragnar.ragnarsmagicmod.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.LivingEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.util.VoidZone;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * The Tome of the Void, drawn on each client from the one message the server sends when it's cast.
 * <ul>
 *   <li>The ground inside the circle becomes the void - every block's top turned to the End portal's starfield,
 *       following the land block by block - as a crack-front of violet light races outward from the middle.</li>
 *   <li>Chunks of the real ground that was there tear loose and spiral in round the rim, tumbling and shrinking as
 *       they're swallowed at the centre.</li>
 *   <li>A rim of violet and soul-light; soul flames flickering on it; ender wisps rising from the middle; and with
 *       every drain pulse, souls streaming out of whatever's caught inside.</li>
 *   <li>At the end it snaps shut from the edge inward, swallowing the last of it, and a shockwave rings out.</li>
 * </ul>
 * Nothing here touches the world: the void is drawn just over the ground's real blocks.
 */
public final class VoidClient {
    private VoidClient() {}

    private static final RenderLayer GLOW = RenderLayer.of("ragnarsmagicmod_void_glow",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 16384, false, true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.LIGHTNING_TRANSPARENCY)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    private static final int R = VoidZone.RADIUS, OPEN = VoidZone.RUPTURE_TICKS, LIVE = VoidZone.ACTIVE_TICKS;
    private static final int CLOSE_TICKS = 7, SHOCK_TICKS = 14, THREAD_TICKS = 6;
    private static final int DEBRIS = 26, DEBRIS_LIFE = 46;

    /** One column of the floor: its top block, how far out it is, and what it's made of (for the debris). */
    private record Cell(BlockPos top, double dist, BlockState state) {}

    private static final class Rift {
        final Vec3d center, from;
        final int caster;
        final long seed, start;
        final List<Cell> cells = new ArrayList<>();
        /** The ground's height (top of the surface block) per column, for rings that hug the land. */
        final double[] heights = new double[(2 * R + 3) * (2 * R + 3)];
        final BlockPos origin;

        Rift(ClientWorld w, VoidZone.Opened p) {
            center = p.center();
            from = p.from();
            caster = p.caster();
            seed = p.seed();
            start = w.getTime();
            BlockPos o = p.origin();
            origin = o;
            java.util.Arrays.fill(heights, center.y);
            for (int x = -R; x <= R; x++) {
                for (int z = -R; z <= R; z++) {
                    if (x * x + z * z > R * R) continue;
                    BlockPos top = VoidZone.findSurface(w, o.add(x, 0, z));
                    if (top == null) continue;
                    BlockState s = w.getBlockState(top);
                    cells.add(new Cell(top, Math.sqrt(x * x + z * z), s.isAir() ? Blocks.DIRT.getDefaultState() : s));
                }
            }
            for (int x = -R - 1; x <= R + 1; x++) {
                for (int z = -R - 1; z <= R + 1; z++) {
                    BlockPos top = VoidZone.findSurface(w, o.add(x, 0, z));
                    if (top != null) heights[(x + R + 1) * (2 * R + 3) + (z + R + 1)] = top.getY() + 1;
                }
            }
        }

        /** The ground's height at a world x/z near the rift. */
        double groundAt(double x, double z) {
            int ix = MathHelper.clamp(MathHelper.floor(x) - origin.getX(), -R - 1, R + 1);
            int iz = MathHelper.clamp(MathHelper.floor(z) - origin.getZ(), -R - 1, R + 1);
            return heights[(ix + R + 1) * (2 * R + 3) + (iz + R + 1)];
        }

        float age(ClientWorld w, float td) {
            return w.getTime() - start + td;
        }
    }

    private static final List<Rift> RIFTS = new ArrayList<>();

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(VoidZone.Opened.ID, (p, ctx) -> {
            ClientWorld w = ctx.client().world;
            if (w != null) RIFTS.add(new Rift(w, p));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> RIFTS.clear());
        ClientTickEvents.END_CLIENT_TICK.register(VoidClient::tick);
        WorldRenderEvents.AFTER_ENTITIES.register(VoidClient::render);
    }

    /** How far out the void reaches at {@code age}: opening behind the crack-front, then closing from the edge in. */
    private static float reach(float age) {
        if (age < OPEN) return R * (age / OPEN);
        float closing = age - OPEN - LIVE;
        if (closing < 0) return R + 0.5f;
        return Math.max(0f, (R + 0.5f) * (1f - closing / CLOSE_TICKS));
    }

    // ---------------------------------------------------------------------
    // Particles (all made here, so none cross the network)
    // ---------------------------------------------------------------------

    private static void tick(MinecraftClient client) {
        ClientWorld w = client.world;
        if (w == null) {
            RIFTS.clear();
            return;
        }
        for (Iterator<Rift> it = RIFTS.iterator(); it.hasNext(); ) {
            Rift rift = it.next();
            int age = (int) (w.getTime() - rift.start);
            if (age > OPEN + LIVE + CLOSE_TICKS + SHOCK_TICKS + 4) {
                it.remove();
                continue;
            }
            Random r = new Random(rift.seed + age * 7919L);
            Vec3d c = rift.center;
            if (age < OPEN) {
                // Dust kicked up where the crack-front is
                float front = R * (age / (float) OPEN);
                for (int i = 0; i < 4; i++) {
                    double a = r.nextDouble() * Math.PI * 2;
                    Cell cell = nearest(rift, c.x + Math.cos(a) * front, c.z + Math.sin(a) * front);
                    if (cell == null) continue;
                    w.addParticle(ParticleTypes.SMOKE, cell.top().getX() + 0.5, cell.top().getY() + 1.05, cell.top().getZ() + 0.5, 0, 0.05, 0);
                }
            } else if (age < OPEN + LIVE) {
                // Soul flames flickering on the rim, wisps rising from the middle
                for (int i = 0; i < 2; i++) {
                    double a = r.nextDouble() * Math.PI * 2;
                    Cell cell = nearest(rift, c.x + Math.cos(a) * (R - 0.3), c.z + Math.sin(a) * (R - 0.3));
                    if (cell != null) w.addParticle(ParticleTypes.SOUL_FIRE_FLAME, cell.top().getX() + r.nextDouble(), cell.top().getY() + 1.05, cell.top().getZ() + r.nextDouble(), 0, 0.02, 0);
                }
                if (age % 2 == 0) w.addParticle(ParticleTypes.REVERSE_PORTAL, c.x + (r.nextDouble() - 0.5) * 0.8, c.y + 0.1, c.z + (r.nextDouble() - 0.5) * 0.8, 0, 0.15, 0);
                // Each drain pulse tears a soul out of everything caught in it
                if ((age - OPEN) % VoidZone.PULSE_EVERY == 0 && age > OPEN) {
                    Box zone = new Box(c, c).expand(R, 3, R);
                    for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, zone, e -> e.isAlive() && e.getId() != rift.caster)) {
                        Vec3d from = e.getBoundingBox().getCenter();
                        if (Math.hypot(from.x - c.x, from.z - c.z) > R) continue;
                        Vec3d to = c.add(0, 0.4, 0);
                        for (int i = 0; i < 7; i++) {
                            Vec3d p = from.lerp(to, i / 7.0);
                            Vec3d v = to.subtract(from).multiply(0.04);
                            w.addParticle(ParticleTypes.SCULK_SOUL, p.x, p.y, p.z, v.x, v.y, v.z);
                        }
                    }
                }
            } else if (age == OPEN + LIVE + CLOSE_TICKS) {
                // Shut: it blows out
                w.addParticle(ParticleTypes.SONIC_BOOM, c.x, c.y + 0.8, c.z, 0, 0, 0);
                for (int i = 0; i < 40; i++) {
                    w.addParticle(ParticleTypes.REVERSE_PORTAL, c.x, c.y + 0.4, c.z,
                            (r.nextDouble() - 0.5) * 1.6, r.nextDouble() * 0.8, (r.nextDouble() - 0.5) * 1.6);
                }
                for (int i = 0; i < 48; i++) {
                    double a = i * Math.PI * 2 / 48;
                    w.addParticle(ParticleTypes.SOUL_FIRE_FLAME, c.x, c.y + 0.3, c.z, Math.cos(a) * 0.6, 0.03, Math.sin(a) * 0.6);
                }
                for (int i = 0; i < 30; i++) {
                    w.addParticle(ParticleTypes.SCULK_SOUL, c.x + (r.nextDouble() - 0.5) * 2, c.y + 0.5 + r.nextDouble(), c.z + (r.nextDouble() - 0.5) * 2,
                            (r.nextDouble() - 0.5) * 0.4, 0.1 + r.nextDouble() * 0.2, (r.nextDouble() - 0.5) * 0.4);
                }
            }
        }
    }

    private static Cell nearest(Rift rift, double x, double z) {
        Cell best = null;
        double bd = Double.MAX_VALUE;
        for (Cell c : rift.cells) {
            double d = (c.top().getX() + 0.5 - x) * (c.top().getX() + 0.5 - x) + (c.top().getZ() + 0.5 - z) * (c.top().getZ() + 0.5 - z);
            if (d < bd) {
                bd = d;
                best = c;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------

    private static void render(WorldRenderContext ctx) {
        if (RIFTS.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld w = ctx.world();
        if (w == null) return;
        float td = ctx.tickCounter().getTickDelta(false);
        Vec3d cam = ctx.camera().getPos();
        MatrixStack ms = ctx.matrixStack() != null ? ctx.matrixStack() : new MatrixStack();
        Matrix4f m = ms.peek().getPositionMatrix();
        VertexConsumerProvider.Immediate buffers = client.getBufferBuilders().getEntityVertexConsumers();

        // The void itself: the End's starfield where the ground was
        VertexConsumer portal = buffers.getBuffer(RenderLayer.getEndPortal());
        for (Rift rift : RIFTS) {
            float reach = reach(rift.age(w, td));
            for (Cell c : rift.cells) {
                if (c.dist() > reach) continue;
                float y = (float) (c.top().getY() + 1.015 - cam.y);
                float x0 = (float) (c.top().getX() - cam.x), z0 = (float) (c.top().getZ() - cam.z);
                portal.vertex(m, x0, y, z0);
                portal.vertex(m, x0, y, z0 + 1);
                portal.vertex(m, x0 + 1, y, z0 + 1);
                portal.vertex(m, x0 + 1, y, z0);
            }
        }
        buffers.draw(RenderLayer.getEndPortal());

        // Light: the crack-front, the rim, the thread from the staff, the shockwave
        VertexConsumer glow = buffers.getBuffer(GLOW);
        for (Rift rift : RIFTS) {
            float age = rift.age(w, td);
            float reach = reach(age);
            float pulse = 0.6f + 0.4f * MathHelper.sin(age * 0.3f);
            if (age < OPEN) {
                // The crack-front: a bright edge racing outward, with a softer glow just behind it
                groundRing(glow, m, rift, cam, reach + 0.25f, 0.5f, 0.75f, 0.45f, 1f, 0.85f);
                groundRing(glow, m, rift, cam, Math.max(0.2f, reach - 0.4f), 0.9f, 0.4f, 0.2f, 0.8f, 0.3f);
            } else if (age < OPEN + LIVE) {
                // The lip of the void: violet light with a thread of soul-light inside it, breathing
                groundRing(glow, m, rift, cam, R + 0.45f, 0.4f, 0.45f, 0.12f, 0.9f, 0.35f * pulse);
                groundRing(glow, m, rift, cam, R + 0.1f, 0.14f, 0.15f, 0.7f, 0.9f, 0.45f * pulse);
            } else if (reach > 0.05f) {
                // Snapping shut: the lip comes rushing in, brighter as it goes
                float k = 1 - reach / (R + 0.5f);
                groundRing(glow, m, rift, cam, reach + 0.2f, 0.45f, 0.7f, 0.45f, 1f, 0.6f + 0.4f * k);
            }
            if (age < THREAD_TICKS) {
                float k = 1 - age / THREAD_TICKS;
                thread(glow, m, rift.from.subtract(cam), rift.center.add(0, 0.3, 0).subtract(cam), 0.12f, 0.6f, 0.3f, 1f, 0.8f * k);
            }
            float since = age - OPEN - LIVE - CLOSE_TICKS;
            if (since >= 0 && since < SHOCK_TICKS) {
                float t = since / SHOCK_TICKS;
                ring(glow, m, rift.center.add(0, 0.2, 0).subtract(cam), 1 + t * (R + 4), 0.8f, 0.55f, 0.35f, 1f, 0.8f * (1 - t));
            }
        }
        buffers.draw(GLOW);

        // The ground itself, torn up in chunks and dragged round into the middle
        for (Rift rift : RIFTS) drawDebris(client, w, ms, buffers, rift, rift.age(w, td), cam);
        buffers.draw();
    }

    private static void drawDebris(MinecraftClient client, ClientWorld w, MatrixStack ms, VertexConsumerProvider buffers,
                                   Rift rift, float age, Vec3d cam) {
        if (rift.cells.isEmpty() || age < 4) return;
        float end = OPEN + LIVE + CLOSE_TICKS;
        if (age > end) return;
        Random r = new Random(rift.seed);
        for (int i = 0; i < DEBRIS; i++) {
            float offset = r.nextFloat() * DEBRIS_LIFE;
            double angle0 = r.nextDouble() * Math.PI * 2;
            double startR = R * (0.75 + r.nextDouble() * 0.3);
            float size = 0.28f + r.nextFloat() * 0.3f;
            float spin = 2 + r.nextFloat() * 5;
            Cell source = rift.cells.get(r.nextInt(rift.cells.size()));
            // Each chunk goes round on a loop of its own; while the rift is opening, they only rise from where it's open
            float local = (age + offset) % DEBRIS_LIFE;
            float p = local / DEBRIS_LIFE;
            if (age > OPEN + LIVE) p = Math.min(1f, p + (age - OPEN - LIVE) / CLOSE_TICKS); // closing: all swallowed
            double rad = startR * Math.pow(1 - p, 1.3);
            if (age < OPEN && rad > reach(age)) continue;
            double ang = angle0 + p * 3.8;
            double y = rift.center.y + 0.2 + 1.3 * Math.sin(Math.PI * Math.min(1, p * 1.15)) - p * p * 0.6;
            float s = size * (1 - p * p) * Math.min(1, local / 4f);
            if (s <= 0.02f) continue;
            double x = rift.center.x + Math.cos(ang) * rad, z = rift.center.z + Math.sin(ang) * rad;
            ms.push();
            ms.translate(x - cam.x, y - cam.y, z - cam.z);
            ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees((age + offset) * spin));
            ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees((age + offset) * spin * 0.7f));
            ms.scale(s, s, s);
            ms.translate(-0.5f, -0.5f, -0.5f);
            int light = WorldRenderer.getLightmapCoordinates(w, BlockPos.ofFloored(x, y + 1, z));
            client.getBlockRenderManager().renderBlockAsEntity(source.state(), ms, buffers, light, OverlayTexture.DEFAULT_UV);
            ms.pop();
        }
    }

    /** A ring of light lying on the land, rising and falling with the ground's height. */
    private static void groundRing(VertexConsumer vc, Matrix4f m, Rift rift, Vec3d cam, float radius, float width,
                                   float r, float g, float b, float a) {
        int n = Math.max(24, (int) (radius * 14));
        float ri = Math.max(0, radius - width / 2), ro = radius + width / 2;
        for (int k = 0; k < n; k++) {
            double a0 = k * Math.PI * 2 / n, a1 = (k + 1) * Math.PI * 2 / n;
            // Each piece lies flat at the height of the ground under its middle, so it steps with the blocks
            double am = (a0 + a1) / 2;
            double y = rift.groundAt(rift.center.x + Math.cos(am) * radius, rift.center.z + Math.sin(am) * radius) + 0.04;
            Vec3d[] p = {at(rift, a0, ri, y), at(rift, a0, ro, y), at(rift, a1, ro, y), at(rift, a1, ri, y)};
            v(vc, m, p[0].subtract(cam), r, g, b, a * 0.15f);
            v(vc, m, p[1].subtract(cam), r, g, b, a);
            v(vc, m, p[2].subtract(cam), r, g, b, a);
            v(vc, m, p[3].subtract(cam), r, g, b, a * 0.15f);
        }
    }

    private static Vec3d at(Rift rift, double ang, double rad, double y) {
        return new Vec3d(rift.center.x + Math.cos(ang) * rad, y, rift.center.z + Math.sin(ang) * rad);
    }

    private static void ring(VertexConsumer vc, Matrix4f m, Vec3d c, float radius, float width, float r, float g, float b, float a) {
        int n = 48;
        for (int k = 0; k < n; k++) {
            double a0 = k * Math.PI * 2 / n, a1 = (k + 1) * Math.PI * 2 / n;
            float ri = radius - width / 2, ro = radius + width / 2;
            v(vc, m, c.add(Math.cos(a0) * ri, 0, Math.sin(a0) * ri), r, g, b, a * 0.3f);
            v(vc, m, c.add(Math.cos(a0) * ro, 0, Math.sin(a0) * ro), r, g, b, a);
            v(vc, m, c.add(Math.cos(a1) * ro, 0, Math.sin(a1) * ro), r, g, b, a);
            v(vc, m, c.add(Math.cos(a1) * ri, 0, Math.sin(a1) * ri), r, g, b, a * 0.3f);
        }
    }

    /** A strip of light from {@code a} to {@code b}, turned to face the camera. */
    private static void thread(VertexConsumer vc, Matrix4f m, Vec3d a, Vec3d b, float width, float r, float g, float bl, float alpha) {
        Vec3d d = b.subtract(a);
        if (d.lengthSquared() < 1e-6) return;
        Vec3d side = d.crossProduct(a.add(b).multiply(0.5)).normalize().multiply(width / 2);
        v(vc, m, a.subtract(side), r, g, bl, alpha);
        v(vc, m, b.subtract(side), r, g, bl, alpha);
        v(vc, m, b.add(side), r, g, bl, alpha);
        v(vc, m, a.add(side), r, g, bl, alpha);
    }

    private static void quad(VertexConsumer vc, Matrix4f m, float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz, float r, float g, float b, float a) {
        vc.vertex(m, ax, ay, az).color(r, g, b, a);
        vc.vertex(m, bx, by, bz).color(r, g, b, a);
        vc.vertex(m, cx, cy, cz).color(r, g, b, a);
        vc.vertex(m, dx, dy, dz).color(r, g, b, a);
    }

    private static void v(VertexConsumer vc, Matrix4f m, Vec3d p, float r, float g, float b, float a) {
        vc.vertex(m, (float) p.x, (float) p.y, (float) p.z).color(r, g, b, MathHelper.clamp(a, 0f, 1f));
    }
}
