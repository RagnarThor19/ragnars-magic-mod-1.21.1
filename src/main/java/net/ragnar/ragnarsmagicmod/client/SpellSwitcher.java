package net.ragnar.ragnarsmagicmod.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.network.TelekinesisPayloads;
import net.ragnar.ragnarsmagicmod.network.SelectSpellPayload;
import org.lwjgl.glfw.GLFW;

/**
 * Client-side spell selection: hold the modifier and scroll, or tap the next/previous keys.
 * Also routes the wheel to the Tome of Telekinesis while it is holding something.
 */
public final class SpellSwitcher {
    private static final String CATEGORY = "key.category.ragnarsmagicmod";

    private static KeyBinding scrollModifier;
    private static KeyBinding nextSpell;
    private static KeyBinding previousSpell;

    private static double scrollAccumulator;
    // Set by the server while the Tome of Telekinesis is holding something
    private static boolean telekinesis;

    private SpellSwitcher() {}

    public static void init() {
        scrollModifier = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.ragnarsmagicmod.scroll_spells", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT, CATEGORY));
        nextSpell = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.ragnarsmagicmod.next_spell", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, CATEGORY));
        previousSpell = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.ragnarsmagicmod.previous_spell", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY));

        ClientPlayNetworking.registerGlobalReceiver(TelekinesisPayloads.Holding.ID, (payload, context) -> {
            telekinesis = payload.active();
            scrollAccumulator = 0;
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> telekinesis = false);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (nextSpell.wasPressed()) cycle(client, 1);
            while (previousSpell.wasPressed()) cycle(client, -1);
            SpellHud.tick();
        });
    }

    /** The hand holding a staff, preferring the main hand, or null if neither does. */
    public static Hand findStaffHand(ClientPlayerEntity player) {
        if (player.getMainHandStack().getItem() instanceof StaffItem) return Hand.MAIN_HAND;
        if (player.getOffHandStack().getItem() instanceof StaffItem) return Hand.OFF_HAND;
        return null;
    }

    /**
     * Called from the mouse scroll hook. Returns true when the scroll was used to switch
     * spells and must not also scroll the hotbar.
     */
    public static boolean onScroll(double vertical) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player != null && telekinesis && client.currentScreen == null && client.getOverlay() == null) {
            // While holding something with the Tome of Telekinesis, the wheel pushes it away / reels it in
            scrollAccumulator += vertical;
            int steps = (int) scrollAccumulator;
            scrollAccumulator -= steps;
            if (steps != 0) ClientPlayNetworking.send(new TelekinesisPayloads.Scroll(steps));
            return true;
        }
        if (player == null || player.isSpectator() || client.currentScreen != null || client.getOverlay() != null
                || !isModifierHeld(client) || findStaffHand(player) == null) {
            scrollAccumulator = 0;
            return false;
        }

        // Accumulate so smooth-scrolling trackpads step one spell at a time
        scrollAccumulator += vertical;
        int steps = (int) scrollAccumulator;
        scrollAccumulator -= steps;
        // Scrolling up moves left, matching the vanilla hotbar
        if (steps != 0) cycle(client, -steps);
        return true;
    }

    private static boolean isModifierHeld(MinecraftClient client) {
        InputUtil.Key key = KeyBindingHelper.getBoundKeyOf(scrollModifier);
        if (key.equals(InputUtil.UNKNOWN_KEY)) return false;
        long window = client.getWindow().getHandle();
        // Poll the physical key so the modifier still works when it shares a key with another binding
        if (key.getCategory() == InputUtil.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(window, key.getCode()) == GLFW.GLFW_PRESS;
        }
        return InputUtil.isKeyPressed(window, key.getCode());
    }

    private static void cycle(MinecraftClient client, int delta) {
        ClientPlayerEntity player = client.player;
        if (player == null || player.isSpectator()) return;
        Hand hand = findStaffHand(player);
        if (hand == null) return;

        ItemStack staff = player.getStackInHand(hand);
        int count = StaffItem.getTomes(staff).size();
        if (count == 0) return;

        int index = Math.floorMod(StaffItem.getSelectedIndex(staff) + delta, count);
        if (count > 1) {
            // Apply locally right away so the HUD responds instantly; the server confirms
            StaffItem.setSelectedIndex(staff, index);
            ClientPlayNetworking.send(new SelectSpellPayload(hand == Hand.OFF_HAND, index));
            client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ITEM_BOOK_PAGE_TURN, 1.6f, 0.35f));
        }
        SpellHud.showSpellName(StaffItem.getSelectedTome(staff));
    }
}
