package net.ragnar.ragnarsmagicmod.terraform.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtIo;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.client.SpellSwitcher;
import net.ragnar.ragnarsmagicmod.client.building.BuildingClient;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.terraform.Terraform;
import net.ragnar.ragnarsmagicmod.terraform.TerraformPlanner;
import net.ragnar.ragnarsmagicmod.terraform.TerraformSettings;
import net.ragnar.ragnarsmagicmod.terraform.TerraformSpell;
import net.ragnar.ragnarsmagicmod.terraform.Terrain;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Client side of the Tome of Terraforming: the saved settings, the menu and size keys (shared with the Tome of
 * Building's), the undo key, the preview of the new ground, and the HUD line.
 * <p>
 * The preview is the new surface drawn over the land, faintly: green where the ground will rise, amber where it'll be
 * cut, with walls where steps and cliffs will stand, and the rest of the brush just tinted so you can see its reach.
 */
public final class TerraformClient {
    private TerraformClient() {}

    private static final String FILE = "ragnarsmagicmod-terraforming.dat";
    private static final int REPLAN_EVERY = 10;

    private static final RenderLayer FILL = RenderLayer.of("ragnarsmagicmod_terraform_preview",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 65536, false, true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.TRANSLUCENT_TRANSPARENCY)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    private static KeyBinding undoKey;
    private static TerraformSettings settings;
    private static int settingsVersion;
    private static double scrollAccumulator;
    private static long ticks;
    private static int earth;

    private static TerraformPlanner.Plan plan;
    private static TerraformPlanner.Target planTarget;
    private static long planTick = -1;
    private static int planVersion = -1;

    private static boolean wasActive;
    private static int hintTicks, sizeFlashTicks;

    public static void init() {
        undoKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.ragnarsmagicmod.terraform_undo", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_U, "key.category.ragnarsmagicmod"));

        TerraformSpell.clientCast = player -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (player != client.player || !ClientPlayNetworking.canSend(Terraform.Request.ID)) return;
            TerraformPlanner.Target t = TerraformPlanner.target(player.getWorld(), player, 1.0f);
            if (t == null) return; // the server explains
            ClientPlayNetworking.send(new Terraform.Request(t.anchor(), settings().toNbt()));
            plan = null; // the land is about to change: work the preview out afresh
        };
        ClientPlayNetworking.registerGlobalReceiver(Terraform.Earth.ID, (payload, context) -> earth = payload.amount());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            plan = null;
            earth = 0;
        });

        ClientTickEvents.END_CLIENT_TICK.register(TerraformClient::tick);
        WorldRenderEvents.AFTER_ENTITIES.register(TerraformClient::renderWorld);
        HudRenderCallback.EVENT.register(TerraformClient::renderHud);
    }

    // ---------------------------------------------------------------------
    // Settings
    // ---------------------------------------------------------------------

    public static TerraformSettings settings() {
        if (settings == null) {
            Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE);
            try {
                if (Files.exists(path)) settings = TerraformSettings.fromNbt(NbtIo.read(path));
            } catch (Exception e) {
                RagnarsMagicMod.LOGGER.warn("Couldn't read Tome of Terraforming settings", e);
            }
            if (settings == null) settings = new TerraformSettings();
        }
        return settings;
    }

    /** Call after changing the settings: saves them and refreshes the preview. */
    public static void onSettingsChanged() {
        settings().clamp();
        settingsVersion++;
        try {
            Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE);
            Files.createDirectories(path.getParent());
            NbtIo.write(settings.toNbt(), path);
        } catch (Exception e) {
            RagnarsMagicMod.LOGGER.warn("Couldn't save Tome of Terraforming settings", e);
        }
    }

    public static int earth() {
        return earth;
    }

    public static TerraformPlanner.Plan currentPlan() {
        return plan;
    }

    public static void sendUndo() {
        if (ClientPlayNetworking.canSend(Terraform.Undo.ID)) ClientPlayNetworking.send(new Terraform.Undo());
        plan = null;
    }

    // ---------------------------------------------------------------------
    // Input
    // ---------------------------------------------------------------------

    /** True while the held staff has the Tome of Terraforming selected. */
    public static boolean isActive(ClientPlayerEntity player) {
        if (player == null || player.isSpectator()) return false;
        Hand hand = SpellSwitcher.findStaffHand(player);
        return hand != null && StaffItem.getSelectedTome(player.getStackInHand(hand)) == Terraform.TOME_OF_TERRAFORMING;
    }

    /** The menu key (shared with the Tome of Building's) was pressed: open the menu if this tome is ready. */
    public static boolean openMenu(MinecraftClient client) {
        if (!isActive(client.player) || client.currentScreen != null) return false;
        client.setScreen(new TerraformScreen());
        return true;
    }

    /** Called from the scroll hook: with the size key held, the wheel grows or shrinks the brush. */
    public static boolean onScroll(double vertical) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen != null || client.getOverlay() != null || !isActive(client.player)
                || !BuildingClient.isResizeKeyHeld(client)) {
            scrollAccumulator = 0;
            return false;
        }
        scrollAccumulator += vertical;
        int steps = (int) scrollAccumulator;
        scrollAccumulator -= steps;
        if (steps != 0) resize(client, steps);
        return true;
    }

    static void resize(MinecraftClient client, int steps) {
        TerraformSettings s = settings();
        boolean changed = false;
        for (int i = 0; i < Math.abs(steps); i++) changed |= s.resize(Integer.signum(steps));
        sizeFlashTicks = 30;
        if (changed) {
            onSettingsChanged();
            client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK.value(), 0.6f + 0.05f * s.radius, 0.25f));
        }
    }

    private static void tick(MinecraftClient client) {
        ticks++;
        boolean active = isActive(client.player);
        while (undoKey.wasPressed()) if (active && client.currentScreen == null) sendUndo();
        if (active && !wasActive) hintTicks = 20 * 6;
        wasActive = active;
        if (hintTicks > 0) hintTicks--;
        if (sizeFlashTicks > 0) sizeFlashTicks--;
    }

    // ---------------------------------------------------------------------
    // The preview
    // ---------------------------------------------------------------------

    private static void renderWorld(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || context.world() == null) return;
        boolean show = isActive(player) && !client.options.hudHidden
                && (client.currentScreen == null || client.currentScreen instanceof TerraformScreen);
        if (!show) {
            plan = null;
            return;
        }
        float tickDelta = context.tickCounter().getTickDelta(false);
        TerraformPlanner.Target target = TerraformPlanner.target(context.world(), player, tickDelta);
        if (target == null) {
            plan = null;
            return;
        }
        if (plan == null || !target.equals(planTarget) || planVersion != settingsVersion || ticks - planTick >= REPLAN_EVERY) {
            plan = TerraformPlanner.plan(context.world(), player, settings(), target);
            planTarget = target;
            planTick = ticks;
            planVersion = settingsVersion;
        }

        Vec3d cam = context.camera().getPos();
        MatrixStack matrices = context.matrixStack() != null ? context.matrixStack() : new MatrixStack();
        VertexConsumerProvider.Immediate buffers = client.getBufferBuilders().getEntityVertexConsumers();
        float pulse = 0.5f + 0.5f * MathHelper.sin((player.age + tickDelta) * 0.15f);
        Matrix4f m = matrices.peek().getPositionMatrix();
        VertexConsumer vc = buffers.getBuffer(FILL);

        Map<Long, Integer> heights = new HashMap<>();
        for (TerraformPlanner.Column c : plan.columns()) heights.put(key(c.x(), c.z()), c.newTop());
        for (TerraformPlanner.Column c : plan.columns()) {
            float r, g, b, a;
            if (c.newTop() > c.oldTop()) {
                r = 0.45f; g = 1.0f; b = 0.5f; a = 0.22f + 0.08f * pulse;
            } else if (c.newTop() < c.oldTop()) {
                r = 1.0f; g = 0.62f; b = 0.25f; a = 0.22f + 0.08f * pulse;
            } else {
                r = 0.85f; g = 0.92f; b = 1.0f; a = 0.07f;
            }
            float y = c.newTop() + 1.02f;
            float x0 = (float) (c.x() - cam.x), z0 = (float) (c.z() - cam.z), yy = (float) (y - cam.y);
            quad(vc, m, x0, yy, z0, x0, yy, z0 + 1, x0 + 1, yy, z0 + 1, x0 + 1, yy, z0, r, g, b, a);
            if (!c.changed()) continue;
            // Walls down to lower neighbours, so steps and cliffs show as they'll stand
            int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] s : sides) {
                Integer other = heights.get(key(c.x() + s[0], c.z() + s[1]));
                int below = other == null ? c.oldTop() : other;
                if (below >= c.newTop()) continue;
                float lo = (float) (below + 1.02f - cam.y), hi = yy;
                if (s[0] == 1) quad(vc, m, x0 + 1, lo, z0, x0 + 1, hi, z0, x0 + 1, hi, z0 + 1, x0 + 1, lo, z0 + 1, r, g, b, a * 0.8f);
                else if (s[0] == -1) quad(vc, m, x0, lo, z0, x0, lo, z0 + 1, x0, hi, z0 + 1, x0, hi, z0, r, g, b, a * 0.8f);
                else if (s[1] == 1) quad(vc, m, x0, lo, z0 + 1, x0 + 1, lo, z0 + 1, x0 + 1, hi, z0 + 1, x0, hi, z0 + 1, r, g, b, a * 0.8f);
                else quad(vc, m, x0, lo, z0, x0, hi, z0, x0 + 1, hi, z0, x0 + 1, lo, z0, r, g, b, a * 0.8f);
            }
        }
        buffers.draw(FILL);
    }

    private static void quad(VertexConsumer vc, Matrix4f m, float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz, float r, float g, float b, float a) {
        vc.vertex(m, ax, ay, az).color(r, g, b, a);
        vc.vertex(m, bx, by, bz).color(r, g, b, a);
        vc.vertex(m, cx, cy, cz).color(r, g, b, a);
        vc.vertex(m, dx, dy, dz).color(r, g, b, a);
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    // ---------------------------------------------------------------------
    // HUD
    // ---------------------------------------------------------------------

    /** "Flatten · 11 across · Soft" (plus the strength, for modes that have one). */
    static MutableText summary(TerraformSettings s) {
        String strength = s.mode.hasStrength() ? " · " + s.mode.strengthLabel + " " + s.strength() : "";
        String shape = s.mode == net.ragnar.ragnarsmagicmod.terraform.TerraformMode.RAMP ? s.diameter() + " wide"
                : s.diameter() + " across" + (s.square ? " (square)" : "");
        return Text.literal(s.mode.label + " · " + shape + strength + " · " + (s.softEdge ? "Soft" : "Hard"));
    }

    /** What the cast will move, and whether there's earth enough for it. */
    static Text status(ClientPlayerEntity player, TerraformPlanner.Plan p) {
        if (p == null) return Text.literal("aim at the ground").formatted(Formatting.GRAY);
        if (p.changed() == 0) return Text.literal("nothing to change here").formatted(Formatting.GRAY);
        MutableText t = Text.literal("▲" + p.fill() + " ▼" + p.cut());
        if (player.isCreative()) return t.formatted(Formatting.GREEN);
        int need = p.net() - earth;
        if (need > 0 && need > countEarthItems(player)) {
            return t.append(Text.literal(" · needs " + need + " more earth")).formatted(Formatting.RED);
        }
        return t.formatted(Formatting.GREEN);
    }

    static int countEarthItems(ClientPlayerEntity player) {
        int n = 0;
        for (ItemStack s : player.getInventory().main) if (!s.isEmpty() && Terrain.isEarthItem(s.getItem())) n += s.getCount();
        for (ItemStack s : player.getInventory().offHand) if (!s.isEmpty() && Terrain.isEarthItem(s.getItem())) n += s.getCount();
        return n;
    }

    private static void renderHud(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.options.hudHidden || !isActive(player) || client.currentScreen instanceof TerraformScreen) return;
        int w = context.getScaledWindowWidth(), h = context.getScaledWindowHeight();
        int y = h - 88;
        MutableText line = summary(settings()).formatted(sizeFlashTicks > 0 ? Formatting.YELLOW : Formatting.WHITE)
                .append(Text.literal("  ")).append(status(player, plan));
        if (!player.isCreative()) line.append(Text.literal("  Earth " + String.format("%,d", earth)).formatted(Formatting.GOLD));
        int lw = client.textRenderer.getWidth(line);
        context.drawTextWithBackground(client.textRenderer, line, (w - lw) / 2, y, lw, 0xFFFFFFFF);
        if (hintTicks > 0) {
            int alpha = Math.min(255, hintTicks * 12);
            if (alpha > 8) {
                Text hint = Text.literal("[").append(BuildingClient.keyName(true)).append("] Terraform menu   [")
                        .append(BuildingClient.keyName(false)).append(" + Scroll] Size   [")
                        .append(undoKey.getBoundKeyLocalizedText()).append("] Undo   [Right-click] Shape");
                int hw = client.textRenderer.getWidth(hint);
                context.drawTextWithShadow(client.textRenderer, hint, (w - hw) / 2, y - 12, (alpha << 24) | 0xBBBBBB);
            }
        }
    }

    static Text undoKeyName() {
        return undoKey.getBoundKeyLocalizedText();
    }
}
