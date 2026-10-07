package net.ragnar.ragnarsmagicmod.dragon.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.input.Input;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.ragnar.ragnarsmagicmod.dragon.Dragon;
import net.ragnar.ragnarsmagicmod.dragon.DragonMissileEntity;

/**
 * Client half of the Tome of the Dragon (see Dragon): while you fly the dragon you watch it from just behind, your movement
 * keys and aim go to it instead of your body, and a click sets it off. When it goes off you're snapped back to your
 * own eyes with a purple flash.
 */
public final class DragonClient {
    private DragonClient() {}

    /** How long we wait for the dragon to show up client side before giving up on it. */
    private static final int LOST_TICKS = 40;
    private static final int WARN_TICKS = 30;
    private static final int FLASH_TICKS = 10;
    private static final Identifier VIGNETTE = Identifier.ofVanilla("textures/misc/vignette.png");

    private static int dragonId = -1;
    private static int ticksLeft, totalTicks, missing, age;
    private static Perspective perspectiveBefore;
    private static boolean suppressUse;
    private static int flash;

    // Keys captured from the player's input before it is cleared
    private static float forward, sideways;
    private static boolean up, down;

    public static void init() {
        EntityRendererRegistry.register(Dragon.MISSILE, DragonMissileRenderer::new);
        DragonMissileEntity.PILOTED_HERE = DragonClient::isDragon;
        DragonBlasts.init();
        ClientPlayNetworking.registerGlobalReceiver(Dragon.PilotPayload.ID, (payload, context) -> {
            MinecraftClient client = context.client();
            if (payload.entityId() >= 0) {
                dragonId = payload.entityId();
                ticksLeft = totalTicks = payload.ticks();
                missing = age = 0;
                if (perspectiveBefore == null) perspectiveBefore = client.options.getPerspective();
                client.options.setPerspective(Perspective.THIRD_PERSON_BACK); // watched from just behind, like the fairy
                Entity dragon = client.world == null ? null : client.world.getEntityById(dragonId);
                if (dragon != null) client.setCameraEntity(dragon);
            } else {
                leave(client);
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            dragonId = -1;
            flash = 0;
            suppressUse = false;
            if (perspectiveBefore != null) client.options.setPerspective(perspectiveBefore);
            perspectiveBefore = null;
        });
        ClientTickEvents.END_CLIENT_TICK.register(DragonClient::tick);
        HudRenderCallback.EVENT.register(DragonClient::renderHud);
    }

    public static boolean isPiloting() {
        return dragonId >= 0;
    }

    /** The dragon we're looking through right now. */
    public static boolean isDragon(Entity entity) {
        return dragonId >= 0 && entity != null && entity.getId() == dragonId;
    }

    private static void leave(MinecraftClient client) {
        boolean was = dragonId >= 0;
        dragonId = -1;
        if (client.player != null) client.setCameraEntity(client.player);
        if (perspectiveBefore != null) client.options.setPerspective(perspectiveBefore);
        perspectiveBefore = null;
        suppressUse = client.options.useKey.isPressed();
        if (was) flash = FLASH_TICKS;
    }

    private static void tick(MinecraftClient client) {
        if (suppressUse && !client.options.useKey.isPressed()) suppressUse = false;
        if (flash > 0 && !client.isPaused()) flash--;
        if (dragonId < 0 || client.player == null || client.world == null) return;
        if (client.isPaused()) return;

        age++;
        if (ticksLeft > 0) ticksLeft--;
        Entity dragon = client.world.getEntityById(dragonId);
        if (dragon == null || dragon.isRemoved()) {
            if (++missing > LOST_TICKS) leave(client);
            return;
        }
        missing = 0;
        if (client.getCameraEntity() != dragon) client.setCameraEntity(dragon);

        ClientPlayNetworking.send(new Dragon.SteerPayload(forward, sideways, up, down,
                client.player.getYaw(), client.player.getPitch()));
    }

    /** Called after the keyboard input ticks: keep the keys for the dragon and leave the body standing still. */
    public static void captureInput(Input input) {
        if (dragonId < 0) return;
        forward = input.movementForward;
        sideways = input.movementSideways;
        up = input.jumping;
        down = input.sneaking;
        input.movementForward = 0;
        input.movementSideways = 0;
        input.pressingForward = false;
        input.pressingBack = false;
        input.pressingLeft = false;
        input.pressingRight = false;
        input.jumping = false;
        input.sneaking = false;
    }

    /** A click while flying sets the dragon off. Returns true when the click must not reach anything else. */
    public static boolean onClick() {
        if (dragonId >= 0) {
            ClientPlayNetworking.send(Dragon.DetonatePayload.INSTANCE);
            return true;
        }
        return false;
    }

    /** Right click: sets it off while flying, and is ignored until let go just after (so the staff doesn't fire). */
    public static boolean onUse() {
        return onClick() || suppressUse;
    }

    // ---------------------------------------------------------------------
    // On screen
    // ---------------------------------------------------------------------

    private static void renderHud(DrawContext context, RenderTickCounter counter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.options.hudHidden) return;
        float tickDelta = counter.getTickDelta(false);
        int w = context.getScaledWindowWidth();
        int h = context.getScaledWindowHeight();

        // Purple at the edges: a pulse as time runs out, and a flash as you snap back to your body
        float edge = 0f;
        if (dragonId >= 0 && ticksLeft <= WARN_TICKS) {
            edge = (1f - ticksLeft / (float) WARN_TICKS) * (0.6f + 0.4f * MathHelper.sin((age + tickDelta) * 1.1f));
        }
        if (flash > 0) edge = Math.max(edge, (flash - tickDelta) / FLASH_TICKS);
        if (edge > 0f) vignette(context, w, h, edge);

        if (dragonId < 0) return;
        boolean ending = ticksLeft <= WARN_TICKS;
        float alpha = ending && (ticksLeft / 3) % 2 == 0 ? 0.5f : 1f;
        int a = (int) (alpha * 255) << 24;
        int color = ending ? 0xFF5060 : 0xD080FF;
        int cx = w / 2;
        int y = h / 2 + 10;
        int half = 16;
        float left = Math.max(0f, ticksLeft - tickDelta);
        context.fill(cx - half - 1, y - 1, cx + half + 1, y + 2, (int) (alpha * 0x88) << 24);
        context.fill(cx - half, y, cx - half + Math.round(2 * half * left / Math.max(1, totalTicks)), y + 1, a | color);

        Text hint = Text.literal("W boost • S brake • Space/Shift climb/dive • Click to detonate").formatted(Formatting.GRAY);
        context.drawTextWithShadow(client.textRenderer, hint, cx - client.textRenderer.getWidth(hint) / 2, h - 72, 0xFFFFFF);
    }

    private static void vignette(DrawContext context, int w, int h, float k) {
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(com.mojang.blaze3d.platform.GlStateManager.SrcFactor.ONE,
                com.mojang.blaze3d.platform.GlStateManager.DstFactor.ONE);
        context.setShaderColor(0.6f * k, 0.15f * k, 0.8f * k, 1f);
        context.drawTexture(VIGNETTE, 0, 0, -90, 0f, 0f, w, h, w, h);
        context.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }
}
