package net.ragnar.ragnarsmagicmod.terraform.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.ragnar.ragnarsmagicmod.client.building.BuildingClient;
import net.ragnar.ragnarsmagicmod.terraform.TerraformMode;
import net.ragnar.ragnarsmagicmod.terraform.TerraformSettings;

import java.util.List;

/** The Tome of Terraforming's menu: what to do to the land, how big, how hard, how soft at the edge - and undo. */
public class TerraformScreen extends Screen {
    private static final int PW = 300, PH = 214;
    private int left, top;

    public TerraformScreen() {
        super(Text.literal("Tome of Terraforming"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    protected void init() {
        left = (width - PW) / 2;
        top = Math.max(2, (height - PH) / 2);
        TerraformSettings s = TerraformClient.settings();

        // --- Modes, two rows of four
        TerraformMode[] modes = TerraformMode.values();
        for (int i = 0; i < modes.length; i++) {
            TerraformMode mode = modes[i];
            int x = left + 8 + (i % 4) * 71, y = top + 24 + (i / 4) * 20;
            ButtonWidget b = addDrawableChild(ButtonWidget.builder(
                            Text.literal(mode.label).formatted(mode == s.mode ? Formatting.YELLOW : Formatting.WHITE), w -> {
                                s.mode = mode;
                                changed();
                            })
                    .dimensions(x, y, 69, 18)
                    .tooltip(Tooltip.of(Text.literal(mode.label).formatted(Formatting.GOLD).append("\n")
                            .append(Text.literal(mode.description).formatted(Formatting.GRAY))))
                    .build());
            b.active = mode != s.mode;
        }

        // --- Size
        int y = top + 104;
        Tooltip sizeTip = Tooltip.of(Text.literal("How far the brush reaches: " + (TerraformSettings.MIN_RADIUS * 2 + 1) + " to "
                + (TerraformSettings.MAX_RADIUS * 2 + 1) + " blocks across.\nTip: hold [" + BuildingClient.keyName(false).getString()
                + "] and scroll in the world to resize."));
        addDrawableChild(ButtonWidget.builder(Text.literal("-"), w -> {
            s.resize(-1);
            changed();
        }).dimensions(left + 90, y, 18, 18).tooltip(sizeTip).build()).active = s.radius > TerraformSettings.MIN_RADIUS;
        addDrawableChild(ButtonWidget.builder(Text.literal("+"), w -> {
            s.resize(1);
            changed();
        }).dimensions(left + 142, y, 18, 18).tooltip(sizeTip).build()).active = s.radius < TerraformSettings.MAX_RADIUS;

        // --- Strength (each mode its own)
        if (s.mode.hasStrength()) {
            int sy = y + 22;
            Tooltip tip = Tooltip.of(Text.literal(s.mode.strengthLabel + ": " + s.mode.minStrength + " to " + s.mode.maxStrength
                    + (s.mode == TerraformMode.SMOOTH ? " (more passes: smoother)" : " blocks")));
            addDrawableChild(ButtonWidget.builder(Text.literal("-"), w -> {
                s.setStrength(s.strength() - 1);
                changed();
            }).dimensions(left + 90, sy, 18, 18).tooltip(tip).build()).active = s.strength() > s.mode.minStrength;
            addDrawableChild(ButtonWidget.builder(Text.literal("+"), w -> {
                s.setStrength(s.strength() + 1);
                changed();
            }).dimensions(left + 142, sy, 18, 18).tooltip(tip).build()).active = s.strength() < s.mode.maxStrength;
        }

        // --- Shape and edge
        int rx = left + 176;
        ButtonWidget shape = addDrawableChild(ButtonWidget.builder(Text.literal("Shape: " + (s.square ? "Square" : "Circle")), w -> {
                    s.square = !s.square;
                    changed();
                }).dimensions(rx, y, 116, 18)
                .tooltip(Tooltip.of(Text.literal("Round brush, or square (good for laying out building plots).")))
                .build());
        shape.active = s.mode != TerraformMode.RAMP;
        addDrawableChild(ButtonWidget.builder(Text.literal("Edge: " + (s.softEdge ? "Soft" : "Hard")), w -> {
                    s.softEdge = !s.softEdge;
                    changed();
                }).dimensions(rx, y + 22, 116, 18)
                .tooltip(Tooltip.of(Text.literal("Soft: blends gently into the land around it, so it never looks pasted on.\n"
                        + "Hard: full strength right up to the edge.")))
                .build());

        // --- Undo
        addDrawableChild(ButtonWidget.builder(Text.literal("Undo last"), w -> {
                    TerraformClient.sendUndo();
                    close();
                }).dimensions(rx, y + 50, 116, 18)
                .tooltip(Tooltip.of(Text.literal("Puts back the land your last terraforming changed (for 5 minutes after).\nKey: ["
                        + TerraformClient.undoKeyName().getString() + "]")))
                .build());
    }

    private void changed() {
        TerraformClient.onSettingsChanged();
        clearAndInit();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        TerraformSettings s = TerraformClient.settings();
        var tr = textRenderer;
        context.drawCenteredTextWithShadow(tr, title.copy().formatted(Formatting.GOLD), left + PW / 2, top + 8, 0xFFFFFF);

        // What the chosen mode does
        List<OrderedText> lines = tr.wrapLines(Text.literal(s.mode.description).formatted(Formatting.GRAY), PW - 16);
        for (int i = 0; i < Math.min(2, lines.size()); i++) context.drawTextWithShadow(tr, lines.get(i), left + 8, top + 70 + i * 10, 0xFFFFFF);

        int y = top + 104;
        context.drawTextWithShadow(tr, Text.literal(s.mode == TerraformMode.RAMP ? "Width" : "Size"), left + 8, y + 5, 0xDDDDDD);
        Text size = Text.literal(String.valueOf(s.diameter()));
        context.drawCenteredTextWithShadow(tr, size, left + 125, y + 5, 0xFFFF55);
        if (s.mode.hasStrength()) {
            context.drawTextWithShadow(tr, Text.literal(s.mode.strengthLabel), left + 8, y + 27, 0xDDDDDD);
            context.drawCenteredTextWithShadow(tr, Text.literal(String.valueOf(s.strength())), left + 125, y + 27, 0xFFFF55);
        }

        // The earth reserve, and what the aimed cast would take
        MinecraftClient client = MinecraftClient.getInstance();
        int by = top + PH - 26;
        if (client.player != null) {
            Text status = Text.literal("Here: ").formatted(Formatting.GRAY).append(TerraformClient.status(client.player, TerraformClient.currentPlan()));
            context.drawTextWithShadow(tr, status, left + 8, by, 0xFFFFFF);
            if (!client.player.isCreative()) {
                Text reserve = Text.literal("Earth reserve: " + String.format("%,d", TerraformClient.earth())).formatted(Formatting.GOLD)
                        .append(Text.literal("  (+ " + TerraformClient.countEarthItems(client.player) + " in your inventory)").formatted(Formatting.GRAY));
                context.drawTextWithShadow(tr, reserve, left + 8, by + 12, 0xFFFFFF);
            } else {
                context.drawTextWithShadow(tr, Text.literal("Creative: no earth needed").formatted(Formatting.GRAY), left + 8, by + 12, 0xFFFFFF);
            }
        }
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        super.renderBackground(context, mouseX, mouseY, delta);
        context.fill(left, top, left + PW, top + PH, 0xC0101418);
        context.drawBorder(left, top, PW, PH, 0xFF6B8E5A);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (BuildingClient.isMenuKey(keyCode, scanCode)) {
            close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
