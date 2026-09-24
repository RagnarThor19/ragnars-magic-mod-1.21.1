package net.ragnar.ragnarsmagicmod.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.AttackIndicator;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.ColorHelper;
import net.minecraft.util.math.MathHelper;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;

import java.util.List;

/**
 * A small vanilla-styled hotbar next to the main hotbar that shows the tomes on the held staff,
 * with a sliding selection frame, per-spell cooldown sweeps and a fading spell name.
 */
public final class SpellHud {
    private static final Identifier HOTBAR = Identifier.ofVanilla("hud/hotbar");
    private static final Identifier HOTBAR_SELECTION = Identifier.ofVanilla("hud/hotbar_selection");
    private static final int SLOT = 20;

    // Selection frame animation (in slots), eased every frame
    private static float framePos;
    private static long lastFrameMs;
    private static int trackedHotbarSlot = -1;
    private static Hand trackedHand;

    // Spell name popup, mirrors the vanilla held item name
    private static Text spellName;
    private static int nameFade;

    private SpellHud() {}

    public static void init() {
        HudRenderCallback.EVENT.register(SpellHud::render);
    }

    public static void showSpellName(TomeItem tome) {
        if (tome == null) return;
        MinecraftClient client = MinecraftClient.getInstance();
        spellName = tome.getName().copy().formatted(TomeItem.colorFor(tome.getTier()));
        nameFade = (int) (40.0 * client.options.getNotificationDisplayTime().getValue());
    }

    /** True while the spell name is showing, so the vanilla item name can step aside. */
    public static boolean isShowingSpellName() {
        return nameFade > 0 && spellName != null;
    }

    static void tick() {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null || SpellSwitcher.findStaffHand(player) == null) {
            nameFade = 0;
            return;
        }
        if (nameFade > 0) nameFade--;
    }

    private static void render(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.options.hudHidden || player.isSpectator() || PossessionClient.isPossessing()) return;

        Hand hand = SpellSwitcher.findStaffHand(player);
        if (hand == null) {
            trackedHand = null;
            return;
        }

        ItemStack staffStack = player.getStackInHand(hand);
        StaffItem staff = (StaffItem) staffStack.getItem();
        List<TomeItem> tomes = StaffItem.getTomes(staffStack);
        int slots = Math.max(staff.getTomeSlots(), tomes.size());
        int selected = StaffItem.getSelectedIndex(staffStack);

        // Snap the frame when a different staff comes into hand, otherwise glide to the selection
        long now = Util.getMeasuringTimeMs();
        int hotbarSlot = player.getInventory().selectedSlot;
        if (hand != trackedHand || hotbarSlot != trackedHotbarSlot) {
            trackedHand = hand;
            trackedHotbarSlot = hotbarSlot;
            framePos = selected;
        } else {
            float dt = Math.min(0.1f, (now - lastFrameMs) / 1000f);
            framePos += (selected - framePos) * (1f - (float) Math.exp(-dt * 28f));
            if (Math.abs(selected - framePos) < 0.01f) framePos = selected;
        }
        lastFrameMs = now;

        int screenWidth = context.getScaledWindowWidth();
        int screenHeight = context.getScaledWindowHeight();
        int barWidth = SLOT * slots + 2;
        int y = screenHeight - 22;

        // Sit on the main-arm side of the hotbar (the offhand slot uses the other side),
        // leaving room for the hotbar attack indicator when it is enabled.
        int center = screenWidth / 2;
        int gap = 6 + (client.options.getAttackIndicator().getValue() == AttackIndicator.HOTBAR ? 22 : 0);
        int x = player.getMainArm() == Arm.RIGHT ? center + 91 + gap : center - 91 - gap - barWidth;
        x = MathHelper.clamp(x, 2, Math.max(2, screenWidth - barWidth - 2));

        RenderSystem.enableBlend();
        context.getMatrices().push();
        context.getMatrices().translate(0.0f, 0.0f, -90.0f);
        // Build an N-slot bar from the vanilla hotbar sprite: left part plus its 1px right edge
        context.drawGuiTexture(HOTBAR, 182, 22, 0, 0, x, y, 1 + SLOT * slots, 22);
        context.drawGuiTexture(HOTBAR, 182, 22, 181, 0, x + 1 + SLOT * slots, y, 1, 22);
        if (!tomes.isEmpty()) {
            context.getMatrices().push();
            context.getMatrices().translate(framePos * SLOT, 0.0f, 0.0f);
            context.drawGuiTexture(HOTBAR_SELECTION, x - 1, y - 1, 24, 23);
            context.getMatrices().pop();
        }
        context.getMatrices().pop();
        RenderSystem.disableBlend();

        for (int i = 0; i < tomes.size(); i++) {
            ItemStack tomeStack = new ItemStack(tomes.get(i));
            int itemX = x + 3 + i * SLOT;
            int itemY = y + 3;
            context.drawItem(player, tomeStack, itemX, itemY, i + 1);
            // Draws the vanilla cooldown sweep for that tome's own cooldown
            context.drawItemInSlot(client.textRenderer, tomeStack, itemX, itemY);
        }

        renderSpellName(context, client, screenWidth, screenHeight);
    }

    private static void renderSpellName(DrawContext context, MinecraftClient client, int screenWidth, int screenHeight) {
        if (!isShowingSpellName()) return;
        // Same placement and fade as InGameHud#renderHeldItemTooltip
        int alpha = Math.min(255, (int) (nameFade * 256.0f / 10.0f));
        if (alpha <= 0) return;
        int width = client.textRenderer.getWidth(spellName);
        int x = (screenWidth - width) / 2;
        int y = screenHeight - 59;
        if (client.interactionManager != null && !client.interactionManager.hasStatusBars()) y += 14;
        context.drawTextWithBackground(client.textRenderer, spellName, x, y, width, ColorHelper.Argb.withAlpha(alpha, -1));
    }
}
