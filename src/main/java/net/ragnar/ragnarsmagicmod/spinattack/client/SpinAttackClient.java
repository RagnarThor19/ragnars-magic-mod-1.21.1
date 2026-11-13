package net.ragnar.ragnarsmagicmod.spinattack.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Arm;
import net.ragnar.ragnarsmagicmod.spinattack.SpinAttack;

import java.util.HashMap;
import java.util.Map;

/**
 * How a spin attack looks: to everyone who can see the spinner (the spinner too, in third person) the whole body
 * whirls round three times, each turn quick to start and easing off, with both arms flung out like a tornado. The
 * turning is done in SpinAttackRenderMixin, the arms in {@link #pose}. In first person the spinner's view whirls round
 * the same way (CameraMixin) - only the view, so where they face and what others see aren't touched.
 */
public final class SpinAttackClient {
    private SpinAttackClient() {}

    private record Spin(long start, boolean offHand) {}

    private static final Map<Integer, Spin> SPINS = new HashMap<>();
    private static final int LENGTH = SpinAttack.SPINS * SpinAttack.SPIN_TICKS;

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(SpinAttack.SpinPayload.ID, (payload, context) -> {
            var world = context.client().world;
            if (world != null) SPINS.put(payload.playerId(), new Spin(world.getTime(), payload.offHand()));
        });
    }

    /** How far round {@code entity} has turned in its spin attack, in degrees, or 0 if it isn't spinning. */
    public static float angle(Entity entity, float tickDelta) {
        if (SPINS.isEmpty()) return 0f;
        Spin spin = SPINS.get(entity.getId());
        var world = MinecraftClient.getInstance().world;
        if (spin == null || world == null) return 0f;
        float t = world.getTime() - spin.start() + tickDelta;
        if (t >= LENGTH || t < 0) {
            if (t >= LENGTH) SPINS.remove(entity.getId());
            return 0f;
        }
        int turn = (int) (t / SpinAttack.SPIN_TICKS);
        float local = (t - turn * SpinAttack.SPIN_TICKS) / SpinAttack.SPIN_TICKS;
        float eased = 1f - (1f - local) * (1f - local); // quick to start, easing off: a whirl, not a turntable
        return 360f * (turn + eased);
    }

    /** Both arms flung straight out to the sides for the length of the spin, like a tornado, the sword arm a touch higher. */
    public static void pose(LivingEntity entity, BipedEntityModel<?> model, float tickDelta) {
        if (SPINS.isEmpty()) return;
        Spin spin = SPINS.get(entity.getId());
        if (spin == null || angle(entity, tickDelta) == 0f) return;
        boolean swordLeft = spin.offHand() == (entity.getMainArm() == Arm.RIGHT);
        out(model.rightArm, false, !swordLeft);
        out(model.leftArm, true, swordLeft);
    }

    private static void out(ModelPart arm, boolean left, boolean sword) {
        float lift = sword ? 1.5f : 1.4f; // a little over horizontal for the blade
        arm.pitch = sword ? -0.2f : 0f;
        arm.yaw = 0f;
        arm.roll = left ? -lift : lift;
    }
}
