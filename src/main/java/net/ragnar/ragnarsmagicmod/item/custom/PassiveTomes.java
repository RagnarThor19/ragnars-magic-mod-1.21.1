package net.ragnar.ragnarsmagicmod.item.custom;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemGroups;
import net.ragnar.ragnarsmagicmod.climbing.Climbing;
import net.ragnar.ragnarsmagicmod.fireresistance.FireResistance;
import net.ragnar.ragnarsmagicmod.gliding.Gliding;
import net.ragnar.ragnarsmagicmod.gourmet.Gourmet;
import net.ragnar.ragnarsmagicmod.grace.Grace;
import net.ragnar.ragnarsmagicmod.lifesteal.Lifesteal;
import net.ragnar.ragnarsmagicmod.swimming.Swimming;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.jumping.Jumping;
import net.ragnar.ragnarsmagicmod.knockback.Knockback;
import net.ragnar.ragnarsmagicmod.leaping.Leaping;
import net.ragnar.ragnarsmagicmod.lunging.Lunging;
import net.ragnar.ragnarsmagicmod.nightvision.NightVision;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Passive tomes (TomeTier.PASSIVE): Speed, Jumping, Lunging, Night Vision, Leaping, Climbing, Gliding,
 * Swimming, Fire Resistance, Grace, Lifesteal, the Gourmet, Knockback, Ground Pound,
 * Sidestep, Desperation, Reflection and Haste. They aren't cast; each one just
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
                entries.addAfter(net.ragnar.ragnarsmagicmod.blackhole.BlackHoles.TOME_OF_BLACK_HOLES, ModItems.TOME_OF_SPEED, Jumping.TOME_OF_JUMPING, Lunging.TOME_OF_LUNGING,
                        NightVision.TOME_OF_NIGHT_VISION, Leaping.TOME_OF_LEAPING, Climbing.TOME_OF_CLIMBING,
                        Gliding.TOME_OF_GLIDING, Swimming.TOME_OF_SWIMMING, FireResistance.TOME_OF_FIRE_RESISTANCE,
                        Grace.TOME_OF_GRACE, Lifesteal.TOME_OF_LIFESTEAL, Gourmet.TOME_OF_THE_GOURMET,
                        Knockback.TOME_OF_KNOCKBACK, net.ragnar.ragnarsmagicmod.groundpound.GroundPound.TOME_OF_GROUND_POUND,
                        net.ragnar.ragnarsmagicmod.sidestep.Sidestep.TOME_OF_SIDESTEP,
                        net.ragnar.ragnarsmagicmod.desperation.Desperation.TOME_OF_DESPERATION,
                        net.ragnar.ragnarsmagicmod.reflection.Reflection.TOME_OF_REFLECTION,
                        net.ragnar.ragnarsmagicmod.haste.Haste.TOME_OF_HASTE));
    }
}
