package net.ragnar.ragnarsmagicmod.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.DustColorTransitionParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.network.ReckoningPayload;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Tome of Reckoning, client side: draws the wave as a thin soul-teal ripple hugging the ground. Particles are
 * short-lived, so it reads as a crisp moving front rather than a smear - which is what you have to time a jump to.
 */
public final class ReckoningClient {
    private ReckoningClient() {}

    private static final ParticleEffect FRONT = new DustColorTransitionParticleEffect(
            new Vector3f(0.30f, 0.95f, 0.85f), new Vector3f(0.02f, 0.10f, 0.12f), 0.9f);
    private static final ParticleEffect WAKE = new DustColorTransitionParticleEffect(
            new Vector3f(0.08f, 0.35f, 0.38f), new Vector3f(0.0f, 0.02f, 0.03f), 0.7f);
    private static final double SPACING = 0.5; // one mote every half block around the ring

    private record Wave(Vec3d center, float speed, float radius, long start) {}

    private static final List<Wave> WAVES = new ArrayList<>();

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(ReckoningPayload.ID, (payload, context) -> {
            ClientWorld world = context.client().world;
            if (world == null) return;
            WAVES.add(new Wave(new Vec3d(payload.x(), payload.y(), payload.z()), payload.speed(), payload.radius(), world.getTime()));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> WAVES.clear());
        ClientTickEvents.END_CLIENT_TICK.register(ReckoningClient::tick);
    }

    private static void tick(MinecraftClient client) {
        if (WAVES.isEmpty() || client.world == null || client.isPaused()) return;
        ClientWorld world = client.world;
        Iterator<Wave> it = WAVES.iterator();
        while (it.hasNext()) {
            Wave w = it.next();
            double r = (world.getTime() - w.start()) * w.speed();
            if (r > w.radius()) {
                it.remove();
                continue;
            }
            ring(client, world, w.center(), r, FRONT, 3 + world.random.nextInt(3), 0.012);
            if (r > 1.0) ring(client, world, w.center(), r - 0.6, WAKE, 4 + world.random.nextInt(4), 0.0);
            // A few souls slip loose from the front and drift up
            for (int i = 0; i < 2 + (int) (r / 6); i++) {
                double a = world.random.nextDouble() * Math.PI * 2;
                Vec3d p = onGround(world, w.center(), r, a);
                if (p != null) world.addParticle(ParticleTypes.SCULK_SOUL, p.x, p.y + 0.1, p.z, 0, 0.02, 0);
            }
        }
    }

    private static void ring(MinecraftClient client, ClientWorld world, Vec3d c, double r, ParticleEffect effect, int life, double rise) {
        int points = Math.max(8, (int) (Math.PI * 2 * r / SPACING));
        double offset = world.random.nextDouble() * Math.PI * 2 / points;
        for (int i = 0; i < points; i++) {
            Vec3d p = onGround(world, c, r, offset + i * Math.PI * 2 / points);
            if (p == null) continue;
            Particle particle = client.particleManager.addParticle(effect, p.x, p.y + 0.06, p.z, 0, rise, 0);
            if (particle != null) particle.setMaxAge(life);
        }
    }

    /** The point {@code r} out at angle {@code a}, dropped onto the floor (or null over a drop or into a wall). */
    private static Vec3d onGround(ClientWorld world, Vec3d c, double r, double a) {
        double x = c.x + Math.cos(a) * r, z = c.z + Math.sin(a) * r;
        int baseY = (int) Math.floor(c.y);
        for (int dy = 3; dy >= -4; dy--) {
            BlockPos below = BlockPos.ofFloored(x, baseY + dy - 1, z);
            BlockPos at = below.up();
            if (!world.getBlockState(below).getCollisionShape(world, below).isEmpty()
                    && world.getBlockState(at).getCollisionShape(world, at).isEmpty()) {
                double top = world.getBlockState(below).getCollisionShape(world, below).getMax(net.minecraft.util.math.Direction.Axis.Y);
                return new Vec3d(x, below.getY() + top, z);
            }
        }
        return null;
    }
}
