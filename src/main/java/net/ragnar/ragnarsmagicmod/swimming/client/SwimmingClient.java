package net.ragnar.ragnarsmagicmod.swimming.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.custom.PassiveTomes;
import net.ragnar.ragnarsmagicmod.swimming.Swimming;

/**
 * Client half of the Tome of Swimming (see Swimming): a soft dolphin whoosh as you set off sprint-swimming, and a thin
 * trail of bubbles off your feet while you keep going. Nothing else; the water sounds are vanilla's.
 */
public final class SwimmingClient {
    private SwimmingClient() {}

    private static boolean wasSwimming;

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(SwimmingClient::tick);
    }

    private static void tick(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p == null || client.isPaused()) return;
        boolean swimming = p.isSwimming() && p.isSubmergedInWater() && PassiveTomes.has(p, Swimming.TOME_OF_SWIMMING);
        if (swimming && !wasSwimming) p.playSound(SoundEvents.ENTITY_DOLPHIN_SWIM, 0.35f, 1.2f);
        wasSwimming = swimming;
        if (!swimming || p.age % 2 != 0) return;

        // A few bubbles shed behind you
        World w = p.getWorld();
        Vec3d back = p.getRotationVector().multiply(-0.9);
        for (int i = 0; i < 2; i++) {
            w.addParticle(ParticleTypes.BUBBLE, p.getX() + back.x + (w.random.nextDouble() - 0.5) * 0.3,
                    p.getY() + 0.2 + back.y + (w.random.nextDouble() - 0.5) * 0.3,
                    p.getZ() + back.z + (w.random.nextDouble() - 0.5) * 0.3, 0, 0.02, 0);
        }
    }
}
