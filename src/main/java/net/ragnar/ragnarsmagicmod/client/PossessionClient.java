package net.ragnar.ragnarsmagicmod.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.input.Input;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.ragnar.ragnarsmagicmod.network.PossessionPayloads;

import java.util.HashSet;
import java.util.Set;

/**
 * Client half of the Tome of Controlling: looks through the possessed mob, sends the movement keys and
 * clicks to the server instead of moving your own body, and hides the bodies of possessing players.
 */
public final class PossessionClient {
    // How long we wait for the host to show up (or come back) client side before giving up on it
    private static final int LOST_HOST_TICKS = 40;

    private static int hostId = -1;
    private static int ticksLeft;
    private static int totalTicks;
    private static int missingTicks;
    // After right clicking out, ignore the held use key so the staff doesn't fire straight away
    private static boolean suppressUse;

    // Keys captured from the player's input before it is cleared
    private static float forward;
    private static float sideways;
    private static boolean jump;
    private static boolean sneak;

    private static final Set<Integer> HIDDEN_PLAYERS = new HashSet<>();

    private PossessionClient() {}

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(PossessionPayloads.Possess.ID, (payload, context) -> {
            if (payload.entityId() >= 0) {
                hostId = payload.entityId();
                ticksLeft = totalTicks = payload.ticks();
                missingTicks = 0;
                Entity host = context.client().world == null ? null : context.client().world.getEntityById(hostId);
                if (host != null) context.client().setCameraEntity(host);
            } else {
                leave(context.client());
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(PossessionPayloads.Hidden.ID, (payload, context) -> {
            if (payload.hidden()) HIDDEN_PLAYERS.add(payload.playerId());
            else HIDDEN_PLAYERS.remove(payload.playerId());
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            hostId = -1;
            HIDDEN_PLAYERS.clear();
            suppressUse = false;
        });
        ClientTickEvents.END_CLIENT_TICK.register(PossessionClient::tick);
        HudRenderCallback.EVENT.register(PossessionClient::renderHud);
    }

    public static boolean isPossessing() {
        return hostId >= 0;
    }

    /** The mob we are looking through right now. */
    public static boolean isHost(Entity entity) {
        return hostId >= 0 && entity != null && entity.getId() == hostId;
    }

    public static boolean isHidden(Entity entity) {
        return !HIDDEN_PLAYERS.isEmpty() && HIDDEN_PLAYERS.contains(entity.getId());
    }

    private static void leave(MinecraftClient client) {
        hostId = -1;
        if (client.player != null) client.setCameraEntity(client.player);
        suppressUse = client.options.useKey.isPressed();
    }

    private static void tick(MinecraftClient client) {
        if (suppressUse && !client.options.useKey.isPressed()) suppressUse = false;
        if (hostId < 0 || client.player == null || client.world == null) return;

        if (ticksLeft > 0) ticksLeft--;
        Entity host = client.world.getEntityById(hostId);
        if (host == null || host.isRemoved()) {
            if (++missingTicks > LOST_HOST_TICKS) leave(client);
            else if (client.getCameraEntity() != client.player) client.setCameraEntity(client.player);
            return;
        }
        missingTicks = 0;
        if (client.getCameraEntity() != host) client.setCameraEntity(host);

        // Turn the host locally too so the crosshair target matches what we see
        float yaw = client.player.getYaw();
        float pitch = client.player.getPitch();
        host.setYaw(yaw);
        host.setPitch(pitch);
        if (host instanceof LivingEntity living) living.setHeadYaw(yaw);

        ClientPlayNetworking.send(new PossessionPayloads.Steer(forward, sideways, jump, sneak,
                client.options.sprintKey.isPressed(), yaw, pitch));
    }

    /** Called after the keyboard input ticks: keep the keys for the host and leave the body standing still. */
    public static void captureInput(Input input) {
        if (hostId < 0) return;
        forward = input.movementForward;
        sideways = input.movementSideways;
        jump = input.jumping;
        sneak = input.sneaking;
        input.movementForward = 0;
        input.movementSideways = 0;
        input.pressingForward = false;
        input.pressingBack = false;
        input.pressingLeft = false;
        input.pressingRight = false;
        input.jumping = false;
        input.sneaking = false;
    }

    /** Left click. Returns true when it was used by the host. */
    public static boolean onAttack() {
        if (hostId < 0) return false;
        ClientPlayNetworking.send(new PossessionPayloads.Act(false));
        return true;
    }

    /** Right click. Returns true when it must not reach the player's own items. */
    public static boolean onUse() {
        if (hostId >= 0) {
            ClientPlayNetworking.send(new PossessionPayloads.Act(true));
            return true;
        }
        return suppressUse;
    }

    private static void renderHud(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (hostId < 0 || client.world == null || client.options.hudHidden) return;
        Entity host = client.world.getEntityById(hostId);
        if (host == null) return;

        TextRenderer font = client.textRenderer;
        int center = context.getScaledWindowWidth() / 2;
        int y = 8;

        Text title = Text.literal("Controlling ").formatted(Formatting.LIGHT_PURPLE)
                .append(host.getDisplayName().copy().formatted(Formatting.WHITE));
        context.drawTextWithShadow(font, title, center - font.getWidth(title) / 2, y, 0xFFFFFF);
        y += 12;

        // Time left, as a draining bar
        int barWidth = 120;
        float frac = totalTicks > 0 ? Math.max(0f, ticksLeft / (float) totalTicks) : 0f;
        int left = center - barWidth / 2;
        context.fill(left - 1, y - 1, left + barWidth + 1, y + 4, 0xAA000000);
        int color = ticksLeft < 100 ? 0xFFE04060 : 0xFFA050F0;
        context.fill(left, y, left + Math.round(barWidth * frac), y + 3, color);
        y += 8;

        String info = (ticksLeft + 19) / 20 + "s";
        if (host instanceof LivingEntity living) {
            info = "❤ " + Math.round(living.getHealth()) + "/" + Math.round(living.getMaxHealth()) + "   " + info;
        }
        context.drawTextWithShadow(font, info, center - font.getWidth(info) / 2, y, 0xFFE0E0E0);
        y += 11;

        Text hint = Text.literal("Left click: ability  •  Right click: return").formatted(Formatting.GRAY);
        context.drawTextWithShadow(font, hint, center - font.getWidth(hint) / 2, y, 0xFFFFFF);
    }
}
