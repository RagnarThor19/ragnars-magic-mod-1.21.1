package net.ragnar.ragnarsmagicmod.sleep.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.FlyingItemEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.sleep.Sleep;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Tome of Sleep Darts / Sleep Potions, client side. Everyone sees a sleeper nod off and slump to one side (see
 * SleepRendererMixin) with Z's drifting up off its head; the server hangs a mob's head itself, and a sleeping player's
 * own client lets their head drop, so others see that too. A sleeping player's screen goes dark, with the odd blink
 * while drowsy, and they can't move or look around (SleepInputMixin, SleepLookMixin).
 */
public final class SleepClient {
    private SleepClient() {}

    /** How long one Z takes to drift up and fade. */
    private static final int Z_LIFE = 46;
    /** How far a sleeping player lets their head hang, in degrees of pitch. */
    private static final float HEAD_DROOP = 55f;
    private static final int Z_COLOR = 0xE2D8FF;

    private static final class Doze {
        int phase, age, left;
        /** Which way it slumps: -1 or 1. */
        final float side;

        Doze(int phase, int left, int entityId) {
            this.phase = phase;
            this.left = left;
            this.side = (entityId & 1) == 0 ? 1f : -1f;
        }
    }

    private static final class Floater {
        final double x, y, z;
        final float size, wobble;
        int age;

        Floater(double x, double y, double z, float size, float wobble) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.size = size;
            this.wobble = wobble;
        }
    }

    private static final Map<Integer, Doze> DOZES = new HashMap<>();
    private static final List<Floater> FLOATERS = new ArrayList<>();
    /** How dark the screen is (0 to 1), and what it was last tick, for the local player. */
    private static float dark, prevDark;

    public static void init() {
        EntityRendererRegistry.register(Sleep.DART, SleepDartRenderer::new);
        EntityRendererRegistry.register(Sleep.POTION, FlyingItemEntityRenderer::new);

        ClientPlayNetworking.registerGlobalReceiver(Sleep.StatePayload.ID, (payload, context) -> {
            if (payload.phase() == Sleep.AWAKE) {
                DOZES.remove(payload.entityId());
                Sleep.CLIENT.remove(payload.entityId());
                return;
            }
            Doze d = DOZES.get(payload.entityId());
            if (d == null) {
                DOZES.put(payload.entityId(), new Doze(payload.phase(), payload.ticks(), payload.entityId()));
            } else {
                if (d.phase != payload.phase()) d.age = 0;
                d.phase = payload.phase();
                d.left = payload.ticks();
            }
            Sleep.CLIENT.put(payload.entityId(), payload.phase());
        });
        ClientTickEvents.END_CLIENT_TICK.register(SleepClient::tick);
        WorldRenderEvents.AFTER_ENTITIES.register(SleepClient::render);
        HudRenderCallback.EVENT.register(SleepClient::hud);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            DOZES.clear();
            FLOATERS.clear();
            Sleep.CLIENT.clear();
            dark = prevDark = 0f;
        });
    }

    private static void tick(MinecraftClient client) {
        ClientWorld world = client.world;
        if (world == null || client.isPaused()) return;
        ClientPlayerEntity me = client.player;

        Iterator<Map.Entry<Integer, Doze>> it = DOZES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Doze> entry = it.next();
            Doze d = entry.getValue();
            d.age++;
            // Never trust a lost "woke up" message to keep someone asleep forever
            if (--d.left < -40) {
                it.remove();
                Sleep.CLIENT.remove(entry.getKey());
                continue;
            }
            Entity e = world.getEntityById(entry.getKey());
            if (e == null) continue;
            boolean firstPerson = e == me && client.options.getPerspective().isFirstPerson();
            if (!firstPerson) spawnZs(world, e, d);
            if (e == me) droop(me, d);
        }

        Iterator<Floater> fit = FLOATERS.iterator();
        while (fit.hasNext()) {
            if (++fit.next().age >= Z_LIFE) fit.remove();
        }

        prevDark = dark;
        Doze mine = me == null ? null : DOZES.get(me.getId());
        float target = 0f;
        if (mine != null && mine.phase == Sleep.ASLEEP) target = 0.86f;
        else if (mine != null) {
            // Heavy eyelids: blinks that come slower and last longer as it wears on
            float k = Math.min(1f, mine.age / (float) Sleep.DROWSY_TICKS);
            float blink = (float) Math.pow(Math.max(0f, MathHelper.sin(mine.age * 0.28f)), 6);
            target = k * (0.18f + 0.55f * blink);
        }
        // Eyes close slowly, but snap open when you're woken
        dark = target > dark ? MathHelper.lerp(0.25f, dark, target) : MathHelper.lerp(0.5f, dark, target);
    }

    private static void spawnZs(ClientWorld world, Entity e, Doze d) {
        int every = d.phase == Sleep.ASLEEP ? 15 : 0;
        boolean spawn = every > 0 ? d.age % every == 1 : d.age == Sleep.DROWSY_TICKS / 2;
        if (!spawn) return;
        float big = MathHelper.clamp(e.getHeight() / 1.8f, 0.6f, 2.2f);
        float size = (d.phase == Sleep.ASLEEP ? 0.8f + world.random.nextFloat() * 0.5f : 0.55f) * big;
        Vec3d look = Vec3d.fromPolar(0, e.getHeadYaw());
        double x = e.getX() + look.x * e.getWidth() * 0.3 + d.side * 0.15;
        double z = e.getZ() + look.z * e.getWidth() * 0.3;
        FLOATERS.add(new Floater(x, e.getY() + e.getHeight() + 0.15, z, size, world.random.nextFloat() * MathHelper.TAU));
    }

    /** A sleeping player's head drops by itself; a drowsy one's keeps sagging while they fight it. */
    private static void droop(ClientPlayerEntity me, Doze d) {
        if (d.phase == Sleep.ASLEEP) {
            me.setPitch(MathHelper.lerp(0.12f, me.getPitch(), HEAD_DROOP));
        } else if (me.getPitch() < HEAD_DROOP) {
            me.setPitch(me.getPitch() + 0.35f + 0.5f * Math.max(0f, MathHelper.sin(d.age * 0.28f)));
        }
    }

    /** Called from SleepRendererMixin with the model about to be drawn: nod it, or slump it over. */
    public static void slump(LivingEntity entity, MatrixStack matrices, float tickDelta) {
        if (DOZES.isEmpty() || entity.deathTime > 0) return;
        Doze d = DOZES.get(entity.getId());
        if (d == null) return;
        float t = d.age + tickDelta;
        float forward, sideways;
        if (d.phase == Sleep.DROWSY) {
            // Nodding off: slow forward dips, deeper as it goes, and a little sway
            float k = Math.min(1f, t / Sleep.DROWSY_TICKS);
            float nod = (float) Math.pow(Math.max(0f, MathHelper.sin(t * 0.28f)), 2);
            forward = nod * 9f * k;
            sideways = d.side * 3f * k * MathHelper.sin(t * 0.14f);
        } else {
            // Slumped over to one side, breathing slowly
            float k = Math.min(1f, t / 8f);
            forward = (7f + 1.5f * MathHelper.sin(t * 0.12f)) * k;
            sideways = d.side * 11f * k;
        }
        // Here the model's front faces -Z and up is +Y
        matrices.multiply(RotationAxis.NEGATIVE_X.rotationDegrees(forward));
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(sideways));
    }

    private static void render(WorldRenderContext context) {
        if (FLOATERS.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        float tickDelta = context.tickCounter().getTickDelta(false);
        Camera camera = context.camera();
        Vec3d cam = camera.getPos();
        MatrixStack matrices = context.matrixStack() != null ? context.matrixStack() : new MatrixStack();
        VertexConsumerProvider.Immediate buffers = client.getBufferBuilders().getEntityVertexConsumers();
        TextRenderer text = client.textRenderer;
        float half = text.getWidth("Z") / 2f;

        for (Floater f : FLOATERS) {
            float t = f.age + tickDelta;
            float alpha = Math.min(1f, t / 5f) * MathHelper.clamp((Z_LIFE - t) / 16f, 0f, 1f);
            int a = (int) (alpha * 255);
            if (a < 10) continue; // the font draws anything fainter than this fully opaque
            double x = f.x + MathHelper.sin(t * 0.13f + f.wobble) * 0.18 * f.size;
            double y = f.y + t * 0.024 * f.size;
            double z = f.z + MathHelper.cos(t * 0.11f + f.wobble) * 0.1 * f.size;
            float s = 0.028f * f.size * (0.6f + 0.7f * t / Z_LIFE);

            matrices.push();
            matrices.translate(x - cam.x, y - cam.y, z - cam.z);
            matrices.multiply(camera.getRotation());
            matrices.scale(s, -s, s);
            matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(MathHelper.sin(t * 0.09f + f.wobble) * 14f));
            text.draw("Z", -half, -4f, (a << 24) | Z_COLOR, true, matrices.peek().getPositionMatrix(), buffers,
                    TextRenderer.TextLayerType.NORMAL, 0, LightmapTextureManager.MAX_LIGHT_COORDINATE);
            matrices.pop();
        }
        buffers.draw();
    }

    private static void hud(DrawContext context, RenderTickCounter counter) {
        float d = MathHelper.lerp(counter.getTickDelta(false), prevDark, dark);
        if (d < 0.01f) return;
        int a = MathHelper.clamp((int) (d * 255), 0, 255);
        context.fill(0, 0, context.getScaledWindowWidth(), context.getScaledWindowHeight(), (a << 24) | 0x0A0716);
    }
}
