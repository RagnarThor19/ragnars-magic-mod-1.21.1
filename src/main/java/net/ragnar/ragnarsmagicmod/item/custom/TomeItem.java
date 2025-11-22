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

    public TomeItem(Settings settings, TomeTier tier, SpellId spell, int xpCost) {
        super(settings);
        this.tier = tier;
        this.spell = spell;
        this.xpCost = xpCost;
    }

    public TomeTier getTier() { return tier; }
    public SpellId getSpell() { return spell; }
    public int getXpCost() { return xpCost; }

    // Right-click while a staff is in OFF-HAND to socket it
    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        ItemStack tome = player.getStackInHand(hand);
        ItemStack off = player.getOffHandStack();

        // Check if the off-hand item is a Staff
        if (off.getItem() instanceof StaffItem staff) {

            // 1. Check if this staff supports this tome tier
            if (!staff.canAccept(this)) {
                if (!world.isClient) {
                    player.sendMessage(Text.literal("This staff cannot use that tome."), true);
                }
                // Return SUCCESS here to consume the click and prevent the staff from firing
                return TypedActionResult.success(tome);
            }

            // 2. Check if the staff ALREADY has a tome socketed
            if (staff.hasTome(off)) {
                if (!world.isClient) {
                    player.sendMessage(Text.literal("Socket occupied! Shift-Right-Click the staff to remove the old tome first."), true);
                }
                return TypedActionResult.success(tome);
            }

            // 3. Perform the socketing (Server only)
            if (!world.isClient) {
                staff.socket(off, this);
                if (!player.isCreative()) {
                    tome.decrement(1);
                }
                player.sendMessage(Text.literal("Tome socketed."), true);
            }

            // 4. Return SUCCESS on both Client and Server.
            // This tells the game "I handled the interaction", so it won't try to use the off-hand item.
            return TypedActionResult.success(tome);
        }

        // If no staff is in the off-hand, pass (allows normal behavior)
        return TypedActionResult.pass(tome);
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        // Tier line (colored)
        tooltip.add(Text.literal("Tier: " + tier.name())
                .formatted(colorFor(tier)));

        // XP cost
        tooltip.add(Text.literal("XP Cost: " + xpCost)
                .formatted(Formatting.GRAY));

        // Which staffs can use it
        String usable = switch (tier) {
            case BEGINNER -> "Golden, Diamond, Netherite";
            case ADVANCED -> "Diamond, Netherite";
            case MASTER -> "Netherite";
        };

        tooltip.add(Text.literal("Usable with: " + usable)
                .formatted(Formatting.DARK_GRAY));
    }

    private static Formatting colorFor(TomeTier t) {
        return switch (t) {
            case BEGINNER -> Formatting.GREEN;
            case ADVANCED -> Formatting.AQUA;
            case MASTER -> Formatting.LIGHT_PURPLE;
        };
    }
}