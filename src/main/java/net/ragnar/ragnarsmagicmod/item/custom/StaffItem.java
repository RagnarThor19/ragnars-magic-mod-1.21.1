package net.ragnar.ragnarsmagicmod.item.custom;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.stat.Stats;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.enchantment.ModEnchantments;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;

import java.util.EnumSet;
import java.util.List;

public class StaffItem extends Item {
    private static final String NBT_ID = "rmm_tome_id";
    private static final String NBT_TIER = "rmm_tome_tier";
    private static final String NBT_XP = "rmm_tome_xp";

    private final EnumSet<TomeTier> allowed;

    public StaffItem(Settings settings, EnumSet<TomeTier> allowed) {
        super(settings);
        this.allowed = allowed;
    }

    @Override
    public boolean isEnchantable(ItemStack stack) {
        return true;
    }

    @Override
    public int getEnchantability() {
        return 15;
    }

    public boolean canAccept(TomeItem tome) {
        return allowed.contains(tome.getTier());
    }

    private static NbtCompound readCustom(ItemStack stack) {
        NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
        return comp == null ? new NbtCompound() : comp.copyNbt();
    }

    private static void writeCustom(ItemStack stack, NbtCompound nbt) {
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
    }

    public boolean hasTome(ItemStack staff) {
        return readCustom(staff).contains(NBT_ID);
    }

    public ItemStack getSocketedTomeStack(ItemStack staff) {
        NbtCompound nbt = readCustom(staff);
        if (!nbt.contains(NBT_ID) || !nbt.contains(NBT_TIER)) return ItemStack.EMPTY;
        try {
            SpellId id = SpellId.valueOf(nbt.getString(NBT_ID));
            TomeTier tier = TomeTier.valueOf(nbt.getString(NBT_TIER));
            TomeItem item = ModItems.getTomeFor(id, tier);
            return item != null ? new ItemStack(item) : ItemStack.EMPTY;
        } catch (IllegalArgumentException e) {
            return ItemStack.EMPTY;
        }
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
        try {
            return SpellId.valueOf(nbt.getString(NBT_ID));
        } catch (IllegalArgumentException e) {
            return null;
        }
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
        float prog = p.experienceProgress;
        int total = 0;
        for (int i = 0; i < lvl; i++) total += xpToNextLevel(i);
        total += Math.round(prog * xpToNextLevel(lvl));
        return total;
    }

    private static int xpToNextLevel(int level) {
        if (level >= 30) return 112 + 9 * (level - 30);
        if (level >= 15) return 37 + 5 * (level - 15);
        return 7 + 2 * level;
    }

    // Helper to get enchantment level in 1.21
    private int getEnchLevel(World world, ItemStack stack, net.minecraft.registry.RegistryKey<Enchantment> key) {
        var registry = world.getRegistryManager().get(RegistryKeys.ENCHANTMENT);
        var entry = registry.getEntry(key);
        return entry.map(e -> EnchantmentHelper.getLevel(e, stack)).orElse(0);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        ItemStack staff = player.getStackInHand(hand);

        if (player.isSneaking()) {
            NbtCompound nbt = readCustom(staff);
            if (!nbt.contains(NBT_ID)) {
                if (!world.isClient) player.sendMessage(Text.literal("No tome socketed."), true);
                return TypedActionResult.success(staff);
            }
            SpellId id = SpellId.valueOf(nbt.getString(NBT_ID));
            TomeTier tier = TomeTier.valueOf(nbt.getString(NBT_TIER));
            TomeItem tomeItem = ModItems.getTomeFor(id, tier);
            if (tomeItem != null) {
                ItemStack refund = new ItemStack(tomeItem);
                if (!player.getInventory().insertStack(refund)) player.dropItem(refund, false);
            }
            nbt.remove(NBT_ID); nbt.remove(NBT_TIER); nbt.remove(NBT_XP);
            writeCustom(staff, nbt);
            if (!world.isClient) player.sendMessage(Text.literal("Socket cleared."), true);
            return TypedActionResult.success(staff);
        }

        SpellId id = getSocketed(staff);
        if (id == null) {
            if (!world.isClient) player.sendMessage(Text.literal("No tome socketed."), true);
            return TypedActionResult.pass(staff);
        }

        // --- RESERVE LOGIC ---
        int baseCost = getXpCost(staff);
        int reserveLevel = getEnchLevel(world, staff, ModEnchantments.RESERVE);
        int xpCost = baseCost;
        if (reserveLevel > 0) {
            float multiplier = switch (reserveLevel) {
                case 1 -> 0.90f; // 10% off
                case 2 -> 0.85f; // 15% off
                default -> 0.75f; // 25% off
            };
            xpCost = Math.max(1, (int)(baseCost * multiplier));
        }

        if (!spendXp(player, xpCost)) {
            if (!world.isClient) player.sendMessage(Text.literal("Not enough XP."), true);
            return TypedActionResult.success(staff, world.isClient);
        }

        net.ragnar.ragnarsmagicmod.item.spell.Spell spell = net.ragnar.ragnarsmagicmod.item.spell.Spells.get(id);
        if (spell != null && spell.cast(world, player, staff)) {
            player.incrementStat(Stats.USED.getOrCreateStat(this));
            staff.damage(1, player, EquipmentSlot.MAINHAND);

            // --- QUICKCAST LOGIC ---
            int cooldown = 24;
            NbtCompound nbt = readCustom(staff);
            if (nbt.contains(NBT_TIER)) {
                try {
                    TomeTier tier = TomeTier.valueOf(nbt.getString(NBT_TIER));
                    TomeItem tome = ModItems.getTomeFor(id, tier);
                    if (tome != null) cooldown = tome.getCooldown();
                } catch (Exception ignored) {}
            }

            int quickcastLevel = getEnchLevel(world, staff, ModEnchantments.QUICKCAST);
            if (quickcastLevel > 0) {
                float multiplier = switch (quickcastLevel) {
                    case 1 -> 0.90f; // 10% faster
                    case 2 -> 0.85f; // 15% faster
                    default -> 0.80f; // 20% faster
                };
                cooldown = Math.max(2, (int)(cooldown * multiplier));
            }

            player.getItemCooldownManager().set(this, cooldown);
            return TypedActionResult.success(staff, world.isClient);
        }
        return TypedActionResult.pass(staff);
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        NbtCompound nbt = readCustom(stack);
        if (nbt.contains(NBT_ID) && nbt.contains(NBT_TIER)) {
            try {
                SpellId id = SpellId.valueOf(nbt.getString(NBT_ID));
                TomeTier tier = TomeTier.valueOf(nbt.getString(NBT_TIER));
                TomeItem tome = ModItems.getTomeFor(id, tier);

                tooltip.add(Text.literal("Socketed: ").formatted(Formatting.GRAY));
                if (tome != null) {
                    tooltip.add(tome.getName().copy().formatted(Formatting.GOLD));
                } else {
                    tooltip.add(Text.literal(id.name()).formatted(Formatting.RED));
                }

                if (nbt.contains(NBT_XP)) {
                    // Note: Tooltip usually doesn't have access to "World" to check enchant levels cleanly,
                    // so we just show base cost here.
                    tooltip.add(Text.literal("Cost: " + nbt.getInt(NBT_XP) + " XP").formatted(Formatting.BLUE));
                }
            } catch (Exception e) {
                tooltip.add(Text.literal("Corrupt Socket Data").formatted(Formatting.RED));
            }
        } else {
            tooltip.add(Text.literal("No Tome Socketed").formatted(Formatting.DARK_GRAY).formatted(Formatting.ITALIC));
        }
        super.appendTooltip(stack, context, tooltip, type);
    }
}