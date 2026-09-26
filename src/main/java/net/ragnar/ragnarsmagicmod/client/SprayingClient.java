package net.ragnar.ragnarsmagicmod.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.item.spell.SprayingSpell;
import net.ragnar.ragnarsmagicmod.network.SprayPayload;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Tome of Spraying, client side. Draws each caster's halo of golden arrows - a ring square to their view, slowly
 * turning, every arrow pointing at whatever they're aiming at, a small golden diamond glowing behind each - and
 * follows the server's firing order so each slot empties as its arrow leaves and re-forms a moment later.
 * The caster also feels each shot as a small kick of the view.
 */
public final class SprayingClient {
    private SprayingClient() {}

    private static final int REFILL_TICKS = 6;   // a fired slot stays empty this long...
    private static final int GROW_TICKS = 3;     // ...then the arrow grows back in over this long
    private static final int FADE_TICKS = 6;
    private static final float ARROW_SCALE = 0.7f;
    private static final float RECOIL_PITCH = 0.225f; // per shot: half of what it was at 10 shots/s, so the total kick is the same
    private static final float RECOIL_YAW = 0.1f;
    // Your own halo is only a block from your eyes in first person, so it's drawn smaller there
    private static final float FIRST_PERSON_SCALE = 0.5f;

    private static final RenderLayer GLOW = RenderLayer.of("ragnarsmagicmod_spray_glow",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 4096, false, true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.LIGHTNING_TRANSPARENCY)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    private static final class Spray {
        final int summon, fire;
        int age;

        Spray(int summon, int fire) {
            this.summon = summon;
            this.fire = fire;
        }
    }

    private static final Map<Integer, Spray> SPRAYS = new HashMap<>();
    private static final ItemStack ARROW = SprayingSpell.arrowStack();
    private static Vec3d cam = Vec3d.ZERO;
    private static Matrix4f pose = new Matrix4f();

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(SprayPayload.ID, (payload, context) -> {
            if (payload.summonTicks() <= 0 && payload.fireTicks() <= 0) {
                Spray s = SPRAYS.get(payload.entityId());
                if (s != null) s.age = Math.max(s.age, s.summon + s.fire);
            } else {
                SPRAYS.put(payload.entityId(), new Spray(payload.summonTicks(), payload.fireTicks()));
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> SPRAYS.clear());
        ClientTickEvents.END_CLIENT_TICK.register(SprayingClient::tick);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(SprayingClient::render);
    }

    private static void tick(MinecraftClient client) {
        if (client.world == null) {
            SPRAYS.clear();
            return;
        }
        if (client.isPaused()) return;
        Iterator<Map.Entry<Integer, Spray>> it = SPRAYS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Spray> e = it.next();
            Spray s = e.getValue();
            // Recoil: each shot kicks your view up a touch, with a little sideways jitter
            int f = s.age - s.summon;
            if (f >= 0 && f < s.fire && f % SprayingSpell.FIRE_INTERVAL == 0 && client.player != null && e.getKey() == client.player.getId()) {
                ClientPlayerEntity p = client.player;
                p.setPitch(MathHelper.clamp(p.getPitch() - RECOIL_PITCH, -90f, 90f));
                p.setYaw(p.getYaw() + (p.getRandom().nextFloat() - 0.5f) * 2f * RECOIL_YAW);
                ScreenShake.kick(0.05f, 2);
            }
            if (++s.age > s.summon + s.fire + FADE_TICKS) it.remove();
        }
    }

    /** How big slot {@code i}'s arrow is right now: 0 while it's gone, 1 when it's ready. */
    private static float slotScale(Spray s, int i, float age) {
        if (age < s.summon) {
            // They form one after another around the ring
            float appear = i * (s.summon - GROW_TICKS) / (float) SprayingSpell.SLOTS;
            return MathHelper.clamp((age - appear) / GROW_TICKS, 0f, 1f);
        }
        float end = s.summon + s.fire;
        if (age >= end) return MathHelper.clamp(1f - (age - end) / FADE_TICKS, 0f, 1f);

        // When did this slot last fire? Shot k leaves from slot (k * STEP) % SLOTS, and STEP is its own inverse mod 12
        int shotsSoFar = (int) ((age - s.summon) / SprayingSpell.FIRE_INTERVAL);
        int first = (i * SprayingSpell.SLOT_STEP) % SprayingSpell.SLOTS;
        if (first > shotsSoFar) return 1f;
        int last = first + ((shotsSoFar - first) / SprayingSpell.SLOTS) * SprayingSpell.SLOTS;
        float since = age - (s.summon + last * SprayingSpell.FIRE_INTERVAL);
        if (since < REFILL_TICKS) return 0f;
        return MathHelper.clamp((since - REFILL_TICKS) / GROW_TICKS, 0f, 1f);
    }

    private static void render(WorldRenderContext context) {
        if (SPRAYS.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;
        float tickDelta = context.tickCounter().getTickDelta(false);
        cam = context.camera().getPos();
        MatrixStack matrices = context.matrixStack() != null ? context.matrixStack() : new MatrixStack();
        pose = matrices.peek().getPositionMatrix();
        VertexConsumerProvider.Immediate buffers = client.getBufferBuilders().getEntityVertexConsumers();

        // Two passes: arrows first, then the glowing runes (the shared buffers keep one layer open at a time)
        for (int pass = 0; pass < 2; pass++) {
            VertexConsumer glow = pass == 1 ? buffers.getBuffer(GLOW) : null;
            for (Map.Entry<Integer, Spray> entry : SPRAYS.entrySet()) {
                Entity e = client.world.getEntityById(entry.getKey());
                if (!(e instanceof LivingEntity caster)) continue;
                Spray s = entry.getValue();
                float age = s.age + tickDelta;
                Vec3d eye = caster.getCameraPosVec(tickDelta);
                Vec3d look = caster.getRotationVec(tickDelta);
                Vec3d aim = SprayingSpell.aimPoint(client.world, caster, eye, look);
                boolean firstPerson = caster == client.getCameraEntity() && client.options.getPerspective().isFirstPerson();
                float size = firstPerson ? FIRST_PERSON_SCALE : 1f;
                for (int i = 0; i < SprayingSpell.SLOTS; i++) {
                    float k = slotScale(s, i, age) * size;
                    if (k <= 0.01f) continue;
                    Vec3d p = SprayingSpell.slotPosition(eye, look, i, age);
                    if (pass == 0) drawArrow(matrices, buffers, p, aim.subtract(p), k, client);
                    else rune(glow, p.subtract(look.multiply(0.12)), look, 0.09f * k, age, i);
                }
            }
            if (pass == 0) buffers.draw();
            else buffers.draw(GLOW);
        }
    }

    private static void drawArrow(MatrixStack ms, VertexConsumerProvider buffers, Vec3d at, Vec3d dir, float k, MinecraftClient client) {
        AffineTransformation t = SprayingSpell.transformFor(dir, ARROW_SCALE * k, false);
        ms.push();
        ms.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
        ms.multiplyPositionMatrix(t.getMatrix());
        client.getItemRenderer().renderItem(ARROW, ModelTransformationMode.NONE, LightmapTextureManager.MAX_LIGHT_COORDINATE,
                OverlayTexture.DEFAULT_UV, ms, buffers, client.world, 0);
        ms.pop();
    }

    /** A small golden diamond glowing behind a slot, square to the caster's view, pulsing. */
    private static void rune(VertexConsumer vc, Vec3d c, Vec3d look, float half, float age, int i) {
        Vec3d right = look.crossProduct(new Vec3d(0, 1, 0));
        right = right.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : right.normalize();
        Vec3d up = right.crossProduct(look).normalize();
        float pulse = 0.7f + 0.3f * MathHelper.sin(age * 0.4f + i);
        float a = 0.55f * pulse;
        Vec3d r = right.multiply(half), u = up.multiply(half);
        vertex(vc, c.add(u), 1f, 0.85f, 0.35f, a);
        vertex(vc, c.add(r), 1f, 0.85f, 0.35f, a);
        vertex(vc, c.subtract(u), 1f, 0.85f, 0.35f, a);
        vertex(vc, c.subtract(r), 1f, 0.85f, 0.35f, a);
    }

    private static void vertex(VertexConsumer vc, Vec3d p, float r, float g, float b, float a) {
        vc.vertex(pose, (float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z)).color(r, g, b, a);
    }
}
