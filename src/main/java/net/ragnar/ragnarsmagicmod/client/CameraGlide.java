package net.ragnar.ragnarsmagicmod.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * After a teleport (Tome of Clones swap, Tome of Blinking), the camera glides from where it was into the new spot
 * instead of snapping there. Read by CameraMixin.
 */
public final class CameraGlide {
    private CameraGlide() {}

    private static Vec3d from;
    private static int ticks;
    private static int age = -1; // -1 when not gliding

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!client.isPaused() && age >= 0 && ++age >= ticks) age = -1;
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> age = -1);
    }

    /** Starts a glide from where the camera is right now (call before the new position has been drawn). */
    public static void start(int durationTicks) {
        from = MinecraftClient.getInstance().gameRenderer.getCamera().getPos();
        ticks = Math.max(1, durationTicks);
        age = 0;
    }

    /** Where the camera should be this frame, given where it would normally be. */
    public static Vec3d apply(Vec3d cameraPos, float tickDelta) {
        if (age < 0 || from == null) return cameraPos;
        float t = MathHelper.clamp((age + tickDelta) / ticks, 0f, 1f);
        float eased = 1f - (1f - t) * (1f - t) * (1f - t);
        return from.lerp(cameraPos, eased);
    }
}
