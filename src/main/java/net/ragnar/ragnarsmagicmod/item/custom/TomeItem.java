package net.ragnar.ragnarsmagicmod.item.custom;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;

public class TomeItem extends Item {
    private final TomeTier tier;
    private final SpellId spell;
    private final int xpCost;

    public TomeItem(Settings settings, TomeTier tier, SpellId spell, int xpCost) {
        super(settings);
        this.tier = tier; this.spell = spell; this.xpCost = xpCost;
    }

    public TomeTier getTier() { return tier; }
    public SpellId getSpell() { return spell; }
    public int getXpCost() { return xpCost; }

    // Right-click while a staff is in OFF-HAND to socket it
    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        ItemStack tome = player.getStackInHand(hand);
        ItemStack off = player.getOffHandStack();
        if (!world.isClient && off.getItem() instanceof StaffItem staff) {
            if (!staff.canAccept(this)) {
                player.sendMessage(Text.literal("This staff cannot use that tome."), true);
                return TypedActionResult.success(tome);
            }
            staff.socket(off, this);
            if (!player.isCreative()) tome.decrement(1);
            player.sendMessage(Text.literal("Tome socketed."), true);
            return TypedActionResult.success(tome);
        }
        return TypedActionResult.pass(tome);
    }
}
