package net.ragnar.ragnarsmagicmod.item.custom;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemGroups;
import net.ragnar.ragnarsmagicmod.basalt.Basalt;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.jumping.Jumping;
import net.ragnar.ragnarsmagicmod.leaping.Leaping;
import net.ragnar.ragnarsmagicmod.lunging.Lunging;
import net.ragnar.ragnarsmagicmod.nightvision.NightVision;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Passive tomes (TomeTier.PASSIVE): Speed, Jumping, Lunging, Night Vision and Leaping. They aren't cast; each one just
 * works while it's anywhere in your inventory (hotbar, backpack, off hand or armour slots) or on any staff you carry
 * (any staff takes them, whatever its tier). Rarer than advanced
 * tomes: they turn up wherever advanced tomes do, at {@code ModLootTableModifiers.PASSIVE_SHARE} of the chance.
 * <p>
 * Staffs that held one of them from before they were passive read them back as the passive tome (see StaffItem).
 */
public final class PassiveTomes {
    private PassiveTomes() {}

    /** True if {@code player} has {@code tome} working for them: in their inventory, or on a staff they carry. */
    public static boolean has(PlayerEntity player, TomeItem tome) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            if (inv.getStack(i).isOf(tome)) return true;
        }
        return !StaffItem.findStaffWith(player, tome).isEmpty();
    }

    /** Every passive tome there is. */
    public static List<TomeItem> all() {
        List<TomeItem> tomes = new ArrayList<>();
        for (Map<TomeTier, TomeItem> byTier : ModItems.TOMES.values()) {
            TomeItem t = byTier.get(TomeTier.PASSIVE);
            if (t != null) tomes.add(t);
        }
        return tomes;
    }

    /** Their own section of the Tools tab, after all the master tomes. Call after every tome has registered. */
    public static void register() {
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries ->
                entries.addAfter(Basalt.TOME_OF_BASALT, ModItems.TOME_OF_SPEED, Jumping.TOME_OF_JUMPING, Lunging.TOME_OF_LUNGING,
                        NightVision.TOME_OF_NIGHT_VISION, Leaping.TOME_OF_LEAPING));
    }
}
