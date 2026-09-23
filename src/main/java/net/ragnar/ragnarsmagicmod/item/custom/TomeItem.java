package net.ragnar.ragnarsmagicmod.item.custom;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;

import java.util.List;

public class TomeItem extends Item {
    private final TomeTier tier;
    private final SpellId spell;
    private final int xpCost;

    // Default cooldown (20 ticks = 1 second)
    private int cooldown = 20;

    public TomeItem(Settings settings, TomeTier tier, SpellId spell, int xpCost) {
        super(settings);
        this.tier = tier;
        this.spell = spell;
        this.xpCost = xpCost;
    }

    /**
     * Sets the cooldown for this specific tome.
     * @param ticks Cooldown in ticks (20 ticks = 1 second)
     * @return The TomeItem itself, for chaining.
     */
    public TomeItem setCooldown(int ticks) {
        this.cooldown = ticks;
        return this;
    }

    public int getCooldown() {
        return cooldown;
    }

    public TomeTier getTier() { return tier; }
    public SpellId getSpell() { return spell; }
    public int getXpCost() { return xpCost; }

    // Right-click while a staff is in OFF-HAND to equip it onto the staff
    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        ItemStack tome = player.getStackInHand(hand);
        ItemStack off = player.getOffHandStack();

        if (hand == Hand.MAIN_HAND && off.getItem() instanceof StaffItem staff) {
            if (!world.isClient) {
                StaffItem.InsertResult result = staff.insertTome(off, this);
                if (result == StaffItem.InsertResult.OK) {
                    if (!player.isCreative()) tome.decrement(1);
                    player.sendMessage(Text.literal("Tome equipped."), true);
                } else {
                    player.sendMessage(StaffItem.insertFailMessage(result), true);
                }
            }
            // SUCCESS consumes the click so the staff doesn't also cast
            return TypedActionResult.success(tome);
        }

        return TypedActionResult.pass(tome);
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        tooltip.add(Text.literal("Tier: " + tier.name()).formatted(colorFor(tier)));
        tooltip.add(Text.literal("XP Cost: " + xpCost).formatted(Formatting.GRAY));

        // Show cooldown in tooltip
        float seconds = cooldown / 20.0f;
        tooltip.add(Text.literal("Cooldown: " + String.format("%.1f", seconds) + "s").formatted(Formatting.BLUE));

        String usable = switch (tier) {
            case BEGINNER -> "Golden, Diamond, Netherite";
            case ADVANCED -> "Diamond, Netherite";
            case MASTER -> "Netherite";
        };

        tooltip.add(Text.literal("Usable with: " + usable).formatted(Formatting.DARK_GRAY));
    }

    public static Formatting colorFor(TomeTier t) {
        return switch (t) {
            case BEGINNER -> Formatting.GREEN;
            case ADVANCED -> Formatting.AQUA;
            case MASTER -> Formatting.LIGHT_PURPLE;
        };
    }
}