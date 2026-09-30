package net.ragnar.ragnarsmagicmod.client.building;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.ragnar.ragnarsmagicmod.util.building.BlockMixer;
import net.ragnar.ragnarsmagicmod.util.building.BuildPlanner;
import net.ragnar.ragnarsmagicmod.util.building.BuildSettings;
import net.ragnar.ragnarsmagicmod.util.building.BuildShape;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * The Tome of Building menu: pick a shape, set its size and fill, and mix up to four blocks from your inventory
 * with a pattern. The world stays visible behind it so the outline updates live.
 */
public class BuildingScreen extends Screen {
    private static final int PW = 400, PH = 228;
    private static final int GRID_COLS = 12, GRID_ROWS = 3, CELL = 18;

    private int left, top;
    // The palette column starts here
    private int x1;

    private record SizeRow(String label, int y, IntSupplier value) {}

    private final List<SizeRow> sizeRows = new ArrayList<>();
    private final Map<Item, Integer> inventoryBlocks = new LinkedHashMap<>();

    public BuildingScreen() {
        super(Text.literal("Tome of Building"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    protected void init() {
        left = (width - PW) / 2;
        top = Math.max(2, (height - PH) / 2);
        x1 = left + 162;
        sizeRows.clear();
        BuildSettings s = BuildingClient.settings();

        // --- Shapes
        BuildShape[] shapes = BuildShape.values();
        for (int i = 0; i < shapes.length; i++) {
            BuildShape shape = shapes[i];
            int x = left + 8 + (i % 3) * 48, y = top + 32 + (i / 3) * 20;
            addDrawableChild(ButtonWidget.builder(Text.literal(shape.label), b -> {
                        s.shape = shape;
                        changed();
                    })
                    .dimensions(x, y, 46, 18)
                    .tooltip(Tooltip.of(Text.literal(shape.label).formatted(Formatting.GOLD).append("\n")
                            .append(Text.literal(shape.description).formatted(Formatting.GRAY))))
                    .build());
        }

        // --- Solid or hollow
        String fillName = s.hollow ? s.shape.hollowLabel : s.shape.solidLabel;
        addDrawableChild(ButtonWidget.builder(Text.literal("Fill: " + fillName), b -> {
                    s.hollow = !s.hollow;
                    changed();
                })
                .dimensions(left + 8, top + 94, 142, 18)
                .tooltip(Tooltip.of(Text.literal("Switch between " + s.shape.solidLabel + " and " + s.shape.hollowLabel + ".")))
                .build());

        // --- Sizes
        int y = top + 118;
        if (s.shape.usesWidth()) {
            sizeRow(s.shape.widthLabel, y, () -> s.width, v -> s.width = v, s.shape.maxWidth);
            y += 20;
        }
        if (s.shape.usesHeight()) {
            sizeRow(s.shape.heightLabel, y, () -> s.height, v -> s.height = v, s.shape.maxHeight);
            y += 20;
        }
        if (s.shape.usesLength()) {
            sizeRow(s.shape.lengthLabel, y, () -> s.length, v -> s.length = v, s.shape.maxLength);
        }

        // --- Block mix
        boolean layered = s.pattern == BlockMixer.Pattern.LAYERED;
        for (int i = 0; i < s.palette.size(); i++) {
            BuildSettings.Entry e = s.palette.get(i);
            int ry = top + 32 + i * 20;
            addDrawableChild(ButtonWidget.builder(Text.literal("-"), b -> {
                        e.weight = Math.max(1, e.weight - 1);
                        changed();
                    })
                    .dimensions(x1 + 98, ry, 14, 18).tooltip(Tooltip.of(weightTip())).build()).active = e.weight > 1;
            addDrawableChild(ButtonWidget.builder(Text.literal("+"), b -> {
                        e.weight = Math.min(BuildSettings.MAX_WEIGHT, e.weight + 1);
                        changed();
                    })
                    .dimensions(x1 + 126, ry, 14, 18).tooltip(Tooltip.of(weightTip())).build()).active = e.weight < BuildSettings.MAX_WEIGHT;
            ButtonWidget layer = addDrawableChild(ButtonWidget.builder(Text.literal(e.layer.label), b -> {
                        e.layer = BlockMixer.Layer.byIndex(e.layer.ordinal() + 1);
                        changed();
                    })
                    .dimensions(x1 + 144, ry, 56, 18)
                    .tooltip(Tooltip.of(layered
                            ? Text.literal("Where this block is most common: bottom, middle or top. It still blends into its neighbours.")
                            : Text.literal("Only used by the Layered pattern.").formatted(Formatting.GRAY)))
                    .build());
            layer.active = layered;
            addDrawableChild(ButtonWidget.builder(Text.literal("✕"), b -> {
                        s.palette.remove(e);
                        changed();
                    })
                    .dimensions(x1 + 206, ry, 18, 18).tooltip(Tooltip.of(Text.literal("Remove from the mix"))).build());
        }

        // --- Pattern
        addDrawableChild(ButtonWidget.builder(Text.literal("Pattern: " + s.pattern.label), b -> {
                    s.pattern = BlockMixer.Pattern.byIndex(s.pattern.ordinal() + 1);
                    changed();
                })
                .dimensions(x1, top + 116, 110, 18)
                .tooltip(Tooltip.of(Text.literal("Click to cycle Random → Layered → Checker.\n").append(
                        Text.literal(s.pattern.description).formatted(Formatting.GRAY))))
                .build());

        collectInventoryBlocks();
    }

    private static Text weightTip() {
        return Text.literal("Weight: how common this block is compared to the others (1–" + BuildSettings.MAX_WEIGHT + ").");
    }

    private void sizeRow(String label, int y, IntSupplier get, IntConsumer set, int max) {
        sizeRows.add(new SizeRow(label, y, get));
        Tooltip tip = Tooltip.of(Text.literal(label + ": " + BuildShape.MIN_SIZE + " to " + max + ".\nTip: hold ["
                + BuildingClient.keyName(false).getString() + "] and scroll in the world to resize everything at once."));
        addDrawableChild(ButtonWidget.builder(Text.literal("-"), b -> {
                    set.accept(get.getAsInt() - 1);
                    changed();
                })
                .dimensions(left + 84, y, 18, 18).tooltip(tip).build()).active = get.getAsInt() > BuildShape.MIN_SIZE;
        addDrawableChild(ButtonWidget.builder(Text.literal("+"), b -> {
                    set.accept(get.getAsInt() + 1);
                    changed();
                })
                .dimensions(left + 132, y, 18, 18).tooltip(tip).build()).active = get.getAsInt() < max;
    }

    private void changed() {
        BuildingClient.onSettingsChanged();
        clearAndInit();
    }

    /** Every block you could build with, from your hotbar, inventory and off hand, with how many you have. */
    private void collectInventoryBlocks() {
        inventoryBlocks.clear();
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) return;
        List<ItemStack> stacks = new ArrayList<>(player.getInventory().main);
        stacks.addAll(player.getInventory().offHand);
        for (ItemStack stack : stacks) {
            if (stack.isEmpty() || !BuildSettings.isBuildable(stack.getItem())) continue;
            inventoryBlocks.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        // Blocks in the mix that you've run out of still show, so they can be removed
        for (BuildSettings.Entry e : BuildingClient.settings().palette) inventoryBlocks.putIfAbsent(e.item, 0);
    }

    // ---------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // No blur or full-screen dim: the outline in the world should stay visible
        context.fill(left, top, left + PW, top + PH, 0xF0141418);
        context.drawBorder(left, top, PW, PH, 0xFF5A4A7A);
        context.fill(left + 156, top + 20, left + 157, top + PH - 6, 0x40FFFFFF);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        BuildSettings s = BuildingClient.settings();

        context.drawText(textRenderer, title.copy().formatted(Formatting.LIGHT_PURPLE), left + 8, top + 7, 0xFFFFFFFF, true);
        Text sum = BuildingClient.summary(s).formatted(Formatting.GRAY);
        context.drawText(textRenderer, sum, left + PW - 8 - textRenderer.getWidth(sum), top + 7, 0xFFFFFFFF, false);

        // Left: shape, fill and sizes
        context.drawText(textRenderer, Text.literal("Shape"), left + 8, top + 22, 0xFFE0C060, false);
        int sel = s.shape.ordinal();
        int sx = left + 8 + (sel % 3) * 48, sy = top + 32 + (sel / 3) * 20;
        context.drawBorder(sx - 1, sy - 1, 48, 20, 0xFFFFD040);
        for (SizeRow row : sizeRows) {
            context.drawText(textRenderer, Text.literal(row.label()), left + 8, row.y() + 5, 0xFFDDDDDD, false);
            String v = String.valueOf(row.value().getAsInt());
            context.drawText(textRenderer, v, left + 117 - textRenderer.getWidth(v) / 2, row.y() + 5, 0xFFFFFFFF, true);
        }
        List<OrderedText> status = textRenderer.wrapLines(BuildingClient.status(BuildingClient.currentPlan()), 142);
        for (int i = 0; i < Math.min(2, status.size()); i++) {
            context.drawText(textRenderer, status.get(i), left + 8, top + 182 + i * 10, 0xFFFFFFFF, false);
        }
        context.drawText(textRenderer, Text.literal("Right-click: build").formatted(Formatting.DARK_GRAY), left + 8, top + 206, 0xFFFFFFFF, false);
        context.drawText(textRenderer, Text.literal("Scroll: resize").formatted(Formatting.DARK_GRAY), left + 8, top + 216, 0xFFFFFFFF, false);

        // Right: the mix
        context.drawText(textRenderer, Text.literal("Block mix"), x1, top + 22, 0xFFE0C060, false);
        if (!s.palette.isEmpty()) {
            context.drawText(textRenderer, Text.literal("Weight"), x1 + 101, top + 22, 0xFF909090, false);
            context.drawText(textRenderer, Text.literal("Layer"), x1 + 158, top + 22, 0xFF909090, false);
        }
        for (int i = 0; i < s.palette.size(); i++) {
            BuildSettings.Entry e = s.palette.get(i);
            int ry = top + 32 + i * 20;
            context.drawItem(new ItemStack(e.item), x1, ry + 1);
            Text name = e.item.getName();
            context.drawText(textRenderer, net.minecraft.util.Language.getInstance().reorder(textRenderer.trimToWidth(name, 74)), x1 + 20, ry + 5, 0xFFFFFFFF, false);
            String w = String.valueOf(e.weight);
            context.drawText(textRenderer, w, x1 + 119 - textRenderer.getWidth(w) / 2, ry + 5, 0xFFFFFFFF, true);
        }
        if (s.palette.isEmpty()) {
            List<OrderedText> lines = textRenderer.wrapLines(Text.literal(
                    "Click blocks below to add them to the mix (up to " + BuildSettings.MAX_PALETTE + "). "
                            + "With none chosen, the block in your off hand is used.").formatted(Formatting.GRAY), 226);
            for (int i = 0; i < lines.size(); i++) context.drawText(textRenderer, lines.get(i), x1, top + 36 + i * 10, 0xFFFFFFFF, false);
        }
        List<OrderedText> desc = textRenderer.wrapLines(Text.literal(s.pattern.description).formatted(Formatting.GRAY), 228);
        for (int i = 0; i < Math.min(2, desc.size()); i++) {
            context.drawText(textRenderer, desc.get(i), x1, top + 138 + i * 10, 0xFFFFFFFF, false);
        }

        // Your blocks
        boolean full = s.palette.size() >= BuildSettings.MAX_PALETTE;
        context.drawText(textRenderer, Text.literal(full ? "Your blocks (mix full: remove one first)"
                : "Your blocks: click to add or remove"), x1, top + 160, 0xFFE0C060, false);
        Item hovered = null;
        int i = 0;
        for (Map.Entry<Item, Integer> e : inventoryBlocks.entrySet()) {
            if (i >= GRID_COLS * GRID_ROWS) break;
            int cx = x1 + (i % GRID_COLS) * CELL, cy = top + 170 + (i / GRID_COLS) * CELL;
            boolean inMix = s.hasItem(e.getKey());
            boolean hover = mouseX >= cx && mouseX < cx + CELL && mouseY >= cy && mouseY < cy + CELL;
            context.fill(cx, cy, cx + CELL - 1, cy + CELL - 1, inMix ? 0x6060A060 : hover ? 0x50FFFFFF : 0x30FFFFFF);
            context.drawItem(new ItemStack(e.getKey()), cx + 1, cy + 1);
            String count = compact(e.getValue());
            context.getMatrices().push();
            context.getMatrices().translate(0, 0, 200);
            // Totals can pass 64, so draw them small to keep neighbouring counts apart
            context.getMatrices().push();
            context.getMatrices().translate(cx + CELL - 1, cy + CELL - 1, 0);
            context.getMatrices().scale(0.75f, 0.75f, 1f);
            context.drawText(textRenderer, count, -textRenderer.getWidth(count), -8,
                    e.getValue() == 0 ? 0xFFFF6060 : 0xFFFFFFFF, true);
            context.getMatrices().pop();
            if (inMix) context.drawBorder(cx - 1, cy - 1, CELL + 1, CELL + 1, 0xFF70E070);
            else if (full) context.fill(cx, cy, cx + CELL - 1, cy + CELL - 1, 0x90101014);
            context.getMatrices().pop();
            if (hover) hovered = e.getKey();
            i++;
        }
        if (inventoryBlocks.isEmpty()) {
            context.drawText(textRenderer, Text.literal("You're not carrying any blocks.").formatted(Formatting.GRAY),
                    x1, top + 174, 0xFFFFFFFF, false);
        }

        if (hovered != null) {
            boolean inMix = s.hasItem(hovered);
            context.drawTooltip(textRenderer, List.of(hovered.getName(), Text.literal(inMix ? "Click to remove from the mix"
                    : full ? "The mix is full" : "Click to add to the mix").formatted(Formatting.GRAY)), mouseX, mouseY);
        }
    }

    private static String compact(int n) {
        return n >= 1000 ? (n / 1000) + "k" : String.valueOf(n);
    }

    // ---------------------------------------------------------------------
    // Input
    // ---------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        BuildSettings s = BuildingClient.settings();
        int i = 0;
        for (Item item : inventoryBlocks.keySet()) {
            if (i >= GRID_COLS * GRID_ROWS) break;
            int cx = x1 + (i % GRID_COLS) * CELL, cy = top + 170 + (i / GRID_COLS) * CELL;
            if (mouseX >= cx && mouseX < cx + CELL && mouseY >= cy && mouseY < cy + CELL) {
                if (s.hasItem(item)) {
                    s.palette.removeIf(e -> e.item == item);
                } else if (s.palette.size() < BuildSettings.MAX_PALETTE) {
                    // New blocks default to the next layer up, so a quick three-block mix is already layered sensibly
                    BlockMixer.Layer layer = BlockMixer.Layer.byIndex(Math.min(2, s.palette.size()));
                    s.palette.add(new BuildSettings.Entry(item, 1, layer));
                } else {
                    return true;
                }
                client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0f));
                changed();
                return true;
            }
            i++;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) return true;
        // Scrolling anywhere in the menu resizes, just like the resize key does in the world
        int steps = (int) Math.signum(verticalAmount);
        if (steps != 0) {
            BuildingClient.resize(client, steps);
            clearAndInit();
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (BuildingClient.menuKey.matchesKey(keyCode, scanCode)) {
            close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void tick() {
        // Keep counts fresh while blocks are being used up behind the menu
        collectInventoryBlocks();
        if (!BuildingClient.isActive(client.player)) close();
    }
}
