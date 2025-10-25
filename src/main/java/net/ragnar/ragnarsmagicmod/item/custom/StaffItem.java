package net.ragnar.ragnarsmagicmod.item.custom;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.SmallFireballEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.sound.SoundEvents;
import net.minecraft.stat.Stats;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.minecraft.sound.SoundCategory;


import java.util.EnumSet;

public class StaffItem extends Item {
    private static final String NBT_ID = "rmm_tome_id";
    private static final String NBT_TIER = "rmm_tome_tier";
    private static final String NBT_XP = "rmm_tome_xp";

    private final EnumSet<TomeTier> allowed;

    public StaffItem(Settings settings, EnumSet<TomeTier> allowed) {
        super(settings);
        this.allowed = allowed;
    }

    public boolean canAccept(TomeItem tome) {
        return allowed.contains(tome.getTier());
    }

    /* ---------- Helpers for CUSTOM_DATA (NBT storage) ---------- */
    private static NbtCompound readCustom(ItemStack stack) {
        NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
        return comp == null ? new NbtCompound() : comp.copyNbt();
    }

    private static void writeCustom(ItemStack stack, NbtCompound nbt) {
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
    }

    public void socket(ItemStack staff, TomeItem tome) {
        NbtCompound nbt = readCustom(staff);
        nbt.putString(NBT_ID, tome.getSpell().name());
        nbt.putString(NBT_TIER, tome.getTier().name());
        nbt.putInt(NBT_XP, tome.getXpCost());
        writeCustom(staff, nbt);
    }

    private SpellId getSocketed(ItemStack staff) {
        NbtCompound nbt = readCustom(staff);
        if (!nbt.contains(NBT_ID)) return null;
        return SpellId.valueOf(nbt.getString(NBT_ID));
    }

    private int getXpCost(ItemStack staff) {
        NbtCompound nbt = readCustom(staff);
        return nbt.getInt(NBT_XP);
    }

    private static boolean spendXp(PlayerEntity p, int cost) {
        if (p.isCreative()) return true;

        int have = getCurrentXpPoints(p);
        if (have < cost) return false;

        p.addExperience(-cost);
        return true;
    }
    private static int getCurrentXpPoints(PlayerEntity p) {
        int lvl = p.experienceLevel;
        float prog = p.experienceProgress; // 0..1 of current level

        int total = 0;
        for (int i = 0; i < lvl; i++) {
            total += xpToNextLevel(i);
        }
        total += Math.round(prog * xpToNextLevel(lvl));
        return total;
    }

    private static int xpToNextLevel(int level) {
        if (level >= 30) return 112 + 9 * (level - 30);
        if (level >= 15) return 37 + 5 * (level - 15);
        return 7 + 2 * level;
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        ItemStack staff = player.getStackInHand(hand);

        // ---------- Shift-right-click: clear socket ----------
        if (player.isSneaking()) {
            // read socket
            NbtCompound nbt = readCustom(staff);
            if (!nbt.contains(NBT_ID)) {
                if (!world.isClient) player.sendMessage(Text.literal("No tome socketed."), true);
                return TypedActionResult.success(staff);
            }

            // parse saved values
            SpellId id = SpellId.valueOf(nbt.getString(NBT_ID));
            TomeTier tier = TomeTier.valueOf(nbt.getString(NBT_TIER));

            // find the correct TomeItem via ModItems registry
            TomeItem tomeItem = ModItems.getTomeFor(id, tier);
            if (tomeItem != null) {
                ItemStack refund = new ItemStack(tomeItem);
                if (!player.getInventory().insertStack(refund)) {
                    player.dropItem(refund, false);
                }
            } else {
                if (!world.isClient) {
                    player.sendMessage(Text.literal("Unknown tome (" + id + ", " + tier + ") — please report"), true);
                }
            }

            // wipe socket
            nbt.remove(NBT_ID);
            nbt.remove(NBT_TIER);
            nbt.remove(NBT_XP);
            writeCustom(staff, nbt);

            if (!world.isClient) player.sendMessage(Text.literal("Socket cleared."), true);
            return TypedActionResult.success(staff);

        }

        // ---------- Cast ----------
        SpellId id = getSocketed(staff);
        if (id == null) {
            if (!world.isClient)
                player.sendMessage(Text.literal("No tome socketed."), true);
            return TypedActionResult.pass(staff);
        }
        
        int xpCost = getXpCost(staff);
        if (!spendXp(player, xpCost)) {
            if (!world.isClient) player.sendMessage(Text.literal("Not enough XP."), true);
            return TypedActionResult.success(staff, world.isClient);
        }

        net.ragnar.ragnarsmagicmod.item.spell.Spell spell = net.ragnar.ragnarsmagicmod.item.spell.Spells.get(id);
        if (spell != null && spell.cast(world, player, staff)) {
            player.incrementStat(Stats.USED.getOrCreateStat(this));
            staff.damage(1, player, EquipmentSlot.MAINHAND);
            player.getItemCooldownManager().set(this, 24);
            return TypedActionResult.success(staff, world.isClient);
        }
        return TypedActionResult.pass(staff);


    }
}
