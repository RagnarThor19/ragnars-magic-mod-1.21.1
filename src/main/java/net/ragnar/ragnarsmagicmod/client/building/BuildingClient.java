package net.ragnar.ragnarsmagicmod.client.building;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Arm;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.client.SpellSwitcher;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.item.spell.BuildingSpell;
import net.ragnar.ragnarsmagicmod.network.BuildingPayloads;
import net.ragnar.ragnarsmagicmod.util.building.BuildPlanner;
import net.ragnar.ragnarsmagicmod.util.building.BuildSettings;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client side of the Tome of Building: the saved settings, the keys, the faint outline of what will be built,
 * the blocks popping in, and the small HUD line that says what you're about to build.
 */
public final class BuildingClient {
    private BuildingClient() {}

    private static final String CATEGORY = "key.category.ragnarsmagicmod";
    private static final String FILE = "ragnarsmagicmod-building.dat";

    private static final RenderLayer FILL = RenderLayer.of("ragnarsmagicmod_build_preview",
            VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS, 8192, false, true,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.TRANSLUCENT_TRANSPARENCY)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .build(false));

    static KeyBinding menuKey;
    private static KeyBinding resizeKey;

    private static BuildSettings settings;
    private static int settingsVersion;
    private static double scrollAccumulator;

    // Client tick counter that drives the animations
    private static long ticks;

    // The plan for the outline, rebuilt when the aim, the settings or the tick changes
    private static BuildPlanner.Plan plan;
    private static BuildPlanner.Target planTarget;
    private static long planTick = -1;
    private static int planVersion = -1;

    // HUD: control hints fade out a few seconds after the tome is readied; size flashes after resizing
    private static boolean wasActive;
    private static int hintTicks;
    private static int sizeFlashTicks;

    private static final class Ghost {
        final BlockState state;
        final long start;
        final boolean own;
        boolean done;

        Ghost(BlockState state, long start, boolean own) {
            this.state = state;
            this.start = start;
            this.own = own;
        }
    }

    private static final Map<BlockPos, Ghost> GHOSTS = new HashMap<>();

    /** An item icon lifting out of the hotbar as its block is used. */
    private record Fly(Item item, long start, int x, int y) {}

    private static final List<Fly> FLIES = new ArrayList<>();
    private static final int FLY_TICKS = 10;

    public static void init() {
        menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.ragnarsmagicmod.building_menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_B, CATEGORY));
        resizeKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.ragnarsmagicmod.building_resize", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_Z, CATEGORY));

        BuildingSpell.clientCast = player -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (player != client.player || !ClientPlayNetworking.canSend(BuildingPayloads.Request.ID)) return;
            BuildPlanner.Target t = BuildPlanner.target(player.getWorld(), player, 1.0f);
            if (t == null) return; // the server explains
            ClientPlayNetworking.send(new BuildingPayloads.Request(t.anchor(), t.face().getId(), t.forward().getId(),
                    settings().toNbt()));
        };

        ClientPlayNetworking.registerGlobalReceiver(BuildingPayloads.Animate.ID, (payload, context) -> {
            for (BuildingPayloads.Ghost g : payload.ghosts()) {
                GHOSTS.put(g.pos().toImmutable(), new Ghost(Block.getStateFromRawId(g.stateId()), ticks + g.delay(), payload.own()));
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            GHOSTS.clear();
            FLIES.clear();
            plan = null;
        });

        ClientTickEvents.END_CLIENT_TICK.register(BuildingClient::tick);
        WorldRenderEvents.AFTER_ENTITIES.register(BuildingClient::renderWorld);
        HudRenderCallback.EVENT.register(BuildingClient::renderHud);
    }

    // ---------------------------------------------------------------------
    // Settings
    // ---------------------------------------------------------------------

    public static BuildSettings settings() {
        if (settings == null) {
            Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE);
            try {
                if (Files.exists(path)) settings = BuildSettings.fromNbt(NbtIo.read(path));
            } catch (Exception e) {
                RagnarsMagicMod.LOGGER.warn("Couldn't read Tome of Building settings", e);
            }
            if (settings == null) settings = new BuildSettings();
        }
        return settings;
    }

    /** Call after changing the settings: saves them and refreshes the outline. */
    public static void onSettingsChanged() {
        settings().clamp();
        settingsVersion++;
        NbtCompound nbt = settings.toNbt();
        try {
            Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE);
            Files.createDirectories(path.getParent());
            NbtIo.write(nbt, path);
        } catch (Exception e) {
            RagnarsMagicMod.LOGGER.warn("Couldn't save Tome of Building settings", e);
        }
    }

    /** The plan behind the outline right now, or null when not aiming at anything. */
    public static BuildPlanner.Plan currentPlan() {
        return plan;
    }

    // ---------------------------------------------------------------------
    // Input
    // ---------------------------------------------------------------------

    /** True while the held staff has the Tome of Building selected. */
    public static boolean isActive(ClientPlayerEntity player) {
        if (player == null || player.isSpectator()) return false;
        Hand hand = SpellSwitcher.findStaffHand(player);
        return hand != null && StaffItem.getSelectedTome(player.getStackInHand(hand)) == ModItems.TOME_OF_BUILDING;
    }

    /** Called from the mouse scroll hook. Returns true when the wheel resized the shape. */
    public static boolean onScroll(double vertical) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen != null || client.getOverlay() != null || !isActive(client.player) || !isResizeHeld(client)) {
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
        BuildSettings s = settings();
        boolean changed = false;
        for (int i = 0; i < Math.abs(steps); i++) changed |= s.resize(Integer.signum(steps));
        sizeFlashTicks = 30;
        if (changed) {
            onSettingsChanged();
            float pitch = 0.7f + 0.1f * Math.max(s.effectiveWidth(), Math.max(s.effectiveHeight(), s.effectiveLength()));
            client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK.value(), pitch, 0.25f));
        }
    }

    private static boolean isResizeHeld(MinecraftClient client) {
        InputUtil.Key key = KeyBindingHelper.getBoundKeyOf(resizeKey);
        if (key.equals(InputUtil.UNKNOWN_KEY)) return false;
        long window = client.getWindow().getHandle();
        // Poll the physical key, so it still works if another binding shares it
        if (key.getCategory() == InputUtil.Type.MOUSE) return GLFW.glfwGetMouseButton(window, key.getCode()) == GLFW.GLFW_PRESS;
        return InputUtil.isKeyPressed(window, key.getCode());
    }

    static Text keyName(boolean menu) {
        return (menu ? menuKey : resizeKey).getBoundKeyLocalizedText();
    }

    private static void tick(MinecraftClient client) {
        ticks++;
        ClientPlayerEntity player = client.player;
        boolean active = isActive(player);
        while (menuKey.wasPressed()) {
            if (active && client.currentScreen == null) client.setScreen(new BuildingScreen());
        }
        if (active && !wasActive) hintTicks = 20 * 6;
        wasActive = active;
        if (hintTicks > 0) hintTicks--;
        if (sizeFlashTicks > 0) sizeFlashTicks--;

        ClientWorld world = client.world;
        if (world == null) {
            GHOSTS.clear();
            return;
        }
        // A block has finished growing: lift its item out of the hotbar, then let the real block take over
        Set<Item> flewThisTick = new HashSet<>();
        Iterator<Map.Entry<BlockPos, Ghost>> it = GHOSTS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Ghost> e = it.next();
            Ghost g = e.getValue();
            long end = g.start + BuildingSpell.GROW_TICKS;
            if (ticks < end) continue;
            if (!g.done) {
                g.done = true;
                Item item = g.state.getBlock().asItem();
                if (g.own && player != null && !player.isCreative() && flewThisTick.add(item)) addFly(client, player, item);
            }
            if (world.getBlockState(e.getKey()).getBlock() == g.state.getBlock() || ticks > end + 10) it.remove();
        }
        FLIES.removeIf(f -> ticks - f.start() > FLY_TICKS);
    }

    private static void addFly(MinecraftClient client, ClientPlayerEntity player, Item item) {
        if (FLIES.size() >= 16) return;
        int w = client.getWindow().getScaledWidth(), h = client.getWindow().getScaledHeight();
        int x = w / 2 - 8, y = h - 19;
        boolean found = false;
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().main.get(i).isOf(item)) {
                x = w / 2 - 90 + i * 20 + 2;
                found = true;
                break;
            }
        }
        if (!found && player.getOffHandStack().isOf(item)) {
            x = player.getMainArm() == Arm.RIGHT ? w / 2 - 91 - 26 : w / 2 + 91 + 10;
        }
        FLIES.add(new Fly(item, ticks, x, y));
    }

    // ---------------------------------------------------------------------
    // World rendering: the outline and the growing blocks
    // ---------------------------------------------------------------------

    private static void renderWorld(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        ClientWorld world = context.world();
        if (player == null || world == null) return;

        float tickDelta = context.tickCounter().getTickDelta(false);
        Vec3d cam = context.camera().getPos();
        MatrixStack matrices = context.matrixStack() != null ? context.matrixStack() : new MatrixStack();
        VertexConsumerProvider.Immediate buffers = client.getBufferBuilders().getEntityVertexConsumers();

        renderGhosts(client, world, matrices, buffers, cam, tickDelta);

        boolean showOutline = isActive(player) && !client.options.hudHidden
                && (client.currentScreen == null || client.currentScreen instanceof BuildingScreen);
        if (!showOutline) {
            plan = null;
            return;
        }
        BuildPlanner.Target target = BuildPlanner.target(world, player, tickDelta);
        if (target == null) {
            plan = null;
            return;
        }
        if (plan == null || !target.equals(planTarget) || planTick != ticks || planVersion != settingsVersion) {
            plan = BuildPlanner.plan(world, player, settings(), target);
            planTarget = target;
            planTick = ticks;
            planVersion = settingsVersion;
        }
        renderOutline(player, matrices, buffers, cam, tickDelta);
    }

    private static void renderGhosts(MinecraftClient client, ClientWorld world, MatrixStack matrices,
                                     VertexConsumerProvider.Immediate buffers, Vec3d cam, float tickDelta) {
        if (GHOSTS.isEmpty()) return;
        float now = ticks + tickDelta;
        boolean drew = false;
        for (Map.Entry<BlockPos, Ghost> e : GHOSTS.entrySet()) {
            BlockPos pos = e.getKey();
            Ghost g = e.getValue();
            float p = (now - g.start) / BuildingSpell.GROW_TICKS;
            if (p <= 0f) continue;
            // Once the real block is there it takes over
            if (world.getBlockState(pos).getBlock() == g.state.getBlock()) continue;
            float scale = easeOutBack(Math.min(1f, p));
            matrices.push();
            matrices.translate(pos.getX() - cam.x + 0.5, pos.getY() - cam.y + 0.5, pos.getZ() - cam.z + 0.5);
            matrices.scale(scale, scale, scale);
            matrices.translate(-0.5, -0.5, -0.5);
            client.getBlockRenderManager().renderBlockAsEntity(g.state, matrices, buffers,
                    WorldRenderer.getLightmapCoordinates(world, pos), OverlayTexture.DEFAULT_UV);
            matrices.pop();
            drew = true;
        }
        if (drew) buffers.draw();
    }

    /** Grows to full size with a tiny overshoot, so blocks "pop" in. */
    private static float easeOutBack(float t) {
        float c1 = 1.3f, c3 = c1 + 1f;
        float x = t - 1f;
        return 1f + c3 * x * x * x + c1 * x * x;
    }

    private static void renderOutline(ClientPlayerEntity player, MatrixStack matrices,
                                      VertexConsumerProvider.Immediate buffers, Vec3d cam, float tickDelta) {
        BuildPlanner.Plan p = plan;
        if (p == null) return;
        boolean ok = p.enough() && !p.palette().isEmpty();
        float r = ok ? 0.62f : 1.0f, g = ok ? 0.86f : 0.42f, b = ok ? 1.0f : 0.36f;
        float time = player.age + tickDelta;
        float pulse = 0.5f + 0.5f * MathHelper.sin(time * 0.15f);

        // Cells still growing in from a build don't need outlining
        Set<BlockPos> cells = new HashSet<>();
        for (BuildPlanner.Cell c : p.free()) if (!GHOSTS.containsKey(c.pos())) cells.add(c.pos());

        matrices.push();
        Matrix4f m = matrices.peek().getPositionMatrix();

        // Faint glass on the outside faces, and the edges of each block on the surface
        VertexConsumer fill = buffers.getBuffer(FILL);
        Set<Edge> edges = new HashSet<>();
        List<float[]> edgeList = new ArrayList<>();
        float fillAlpha = 0.07f + 0.04f * pulse;
        for (BlockPos pos : cells) {
            for (Direction d : Direction.values()) {
                if (cells.contains(pos.offset(d))) continue;
                face(fill, m, pos, d, cam, r, g, b, fillAlpha);
                collectEdges(pos, d, edges, edgeList);
            }
        }
        buffers.draw(FILL);

        VertexConsumer lines = buffers.getBuffer(RenderLayer.getLines());
        MatrixStack.Entry entry = matrices.peek();
        float lineAlpha = 0.38f + 0.14f * pulse;
        for (float[] e : edgeList) {
            float x0 = (float) (e[0] - cam.x), y0 = (float) (e[1] - cam.y), z0 = (float) (e[2] - cam.z);
            float dx = e[3], dy = e[4], dz = e[5];
            lines.vertex(m, x0, y0, z0).color(r, g, b, lineAlpha).normal(entry, dx, dy, dz);
            lines.vertex(m, x0 + dx, y0 + dy, z0 + dz).color(r, g, b, lineAlpha).normal(entry, dx, dy, dz);
        }
        // Spots the shape wants but something is already there: a faint red box, so you know it'll be skipped
        for (BuildPlanner.Cell c : p.blocked()) {
            Box box = new Box(c.pos()).offset(-cam.x, -cam.y, -cam.z).expand(0.004);
            WorldRenderer.drawBox(matrices, lines, box, 1f, 0.3f, 0.3f, 0.18f);
        }
        buffers.draw(RenderLayer.getLines());
        matrices.pop();
    }

    /** One face of a block, pushed out a hair so it never flickers against a neighbouring block. */
    private static void face(VertexConsumer vc, Matrix4f m, BlockPos pos, Direction d, Vec3d cam,
                             float r, float g, float b, float a) {
        float e = 0.003f;
        float x0 = (float) (pos.getX() - cam.x) - e, y0 = (float) (pos.getY() - cam.y) - e, z0 = (float) (pos.getZ() - cam.z) - e;
        float x1 = x0 + 1 + 2 * e, y1 = y0 + 1 + 2 * e, z1 = z0 + 1 + 2 * e;
        switch (d) {
            case DOWN -> quad(vc, m, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, r, g, b, a);
            case UP -> quad(vc, m, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, r, g, b, a);
            case NORTH -> quad(vc, m, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, r, g, b, a);
            case SOUTH -> quad(vc, m, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, r, g, b, a);
            case WEST -> quad(vc, m, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, r, g, b, a);
            case EAST -> quad(vc, m, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, r, g, b, a);
        }
    }

    private static void quad(VertexConsumer vc, Matrix4f m, float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz, float r, float g, float b, float a) {
        vc.vertex(m, ax, ay, az).color(r, g, b, a);
        vc.vertex(m, bx, by, bz).color(r, g, b, a);
        vc.vertex(m, cx, cy, cz).color(r, g, b, a);
        vc.vertex(m, dx, dy, dz).color(r, g, b, a);
    }

    /** The four edges of a block face, each edge only once even when two faces share it. */
    private record Edge(int x, int y, int z, int axis) {}

    private static void collectEdges(BlockPos pos, Direction d, Set<Edge> seen, List<float[]> out) {
        int[] c = {pos.getX(), pos.getY(), pos.getZ()};
        int axis = d.getAxis().ordinal();
        int plane = c[axis] + (d.getDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0);
        int a1 = (axis + 1) % 3, a2 = (axis + 2) % 3;
        for (int along : new int[]{a1, a2}) {
            int other = along == a1 ? a2 : a1;
            for (int off = 0; off <= 1; off++) {
                int[] start = c.clone();
                start[axis] = plane;
                start[other] += off;
                if (!seen.add(new Edge(start[0], start[1], start[2], along))) continue;
                float[] e = {start[0], start[1], start[2], 0, 0, 0};
                e[3 + along] = 1;
                out.add(e);
            }
        }
    }

    // ---------------------------------------------------------------------
    // HUD
    // ---------------------------------------------------------------------

    /** "Wall 5×3 · Solid · Random" */
    static MutableText summary(BuildSettings s) {
        StringBuilder size = new StringBuilder();
        if (s.shape.usesWidth()) size.append(s.width);
        if (s.shape.usesHeight()) size.append(size.isEmpty() ? "" : "×").append(s.height);
        if (s.shape.usesLength()) size.append(size.isEmpty() ? "" : "×").append(s.length);
        return Text.literal(s.shape.label + " " + size + " · " + (s.hollow ? s.shape.hollowLabel : s.shape.solidLabel)
                + " · " + s.pattern.label);
    }

    /** "25 blocks", "needs 25 · you have 12", or what's missing. */
    static Text status(BuildPlanner.Plan p) {
        if (p == null) return Text.literal("aim at a block").formatted(Formatting.GRAY);
        if (p.palette().isEmpty()) {
            return Text.literal("no blocks chosen · press [").append(keyName(true)).append("]").formatted(Formatting.YELLOW);
        }
        if (p.needed() == 0) return Text.literal("no room here").formatted(Formatting.GRAY);
        String blocks = p.needed() + (p.needed() == 1 ? " block" : " blocks");
        if (p.unlimited()) return Text.literal(blocks).formatted(Formatting.GREEN);
        if (!p.enough()) return Text.literal("needs " + p.needed() + " · you have " + p.available()).formatted(Formatting.RED);
        return Text.literal(blocks + " · you have " + p.available()).formatted(Formatting.GREEN);
    }

    private static void renderHud(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.options.hudHidden) return;
        float tickDelta = tickCounter.getTickDelta(false);
        renderFlies(context, tickDelta);
        if (!isActive(player) || client.currentScreen instanceof BuildingScreen) return;

        int w = context.getScaledWindowWidth(), h = context.getScaledWindowHeight();
        // Always above the action bar (where build messages show) and the held item name, which never move
        int y = h - 88;

        MutableText line = summary(settings()).formatted(sizeFlashTicks > 0 ? Formatting.YELLOW : Formatting.WHITE)
                .append(Text.literal("  ")).append(status(plan));
        int lw = client.textRenderer.getWidth(line);
        context.drawTextWithBackground(client.textRenderer, line, (w - lw) / 2, y, lw, 0xFFFFFFFF);

        if (hintTicks > 0) {
            int alpha = Math.min(255, hintTicks * 12);
            if (alpha > 8) {
                Text hint = Text.literal("[").append(keyName(true)).append("] Build menu   [").append(keyName(false))
                        .append(" + Scroll] Size   [Right-click] Build");
                int hw = client.textRenderer.getWidth(hint);
                context.drawTextWithShadow(client.textRenderer, hint, (w - hw) / 2, y - 12, (alpha << 24) | 0xBBBBBB);
            }
        }
    }

    private static void renderFlies(DrawContext context, float tickDelta) {
        if (FLIES.isEmpty()) return;
        for (Fly f : FLIES) {
            float t = MathHelper.clamp((ticks - f.start() + tickDelta) / FLY_TICKS, 0f, 1f);
            float ease = 1f - (1f - t) * (1f - t);
            float scale = 1f - t * 0.75f;
            context.getMatrices().push();
            context.getMatrices().translate(f.x() + 8, f.y() + 8 - ease * 22f, 200f);
            context.getMatrices().scale(scale, scale, 1f);
            context.drawItem(new ItemStack(f.item()), -8, -8);
            context.getMatrices().pop();
        }
    }
}
