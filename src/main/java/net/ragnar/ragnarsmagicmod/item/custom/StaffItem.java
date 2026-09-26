package net.ragnar.ragnarsmagicmod.item.custom;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.StackReference;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.screen.slot.Slot;
import net.minecraft.sound.SoundEvents;
import net.minecraft.stat.Stats;
import net.minecraft.text.Text;
import net.minecraft.util.ClickType;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.enchantment.ModEnchantments;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

public class StaffItem extends Item {
    // Multi-tome storage: a list of {id, tier} entries plus the selected index
    private static final String NBT_TOMES = "rmm_tomes";
    private static final String NBT_SELECTED = "rmm_selected";
    private static final String KEY_ID = "id";
    private static final String KEY_TIER = "tier";

    // Legacy single-socket keys (migrated on first write)
    private static final String LEGACY_ID = "rmm_tome_id";
    private static final String LEGACY_TIER = "rmm_tome_tier";
    private static final String LEGACY_XP = "rmm_tome_xp";

    public enum InsertResult { OK, WRONG_TIER, FULL, DUPLICATE }

    private final EnumSet<TomeTier> allowed;
    private final int tomeSlots;

    public StaffItem(Settings settings, EnumSet<TomeTier> allowed, int tomeSlots) {
        super(settings);
        this.allowed = allowed;
        this.tomeSlots = tomeSlots;
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

    public int getTomeSlots() {
        return tomeSlots;
    }

    // ---------------------------------------------------------------------
    // NBT helpers
    // ---------------------------------------------------------------------

    private static NbtCompound readCustom(ItemStack stack) {
        NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
        return comp == null ? new NbtCompound() : comp.copyNbt();
    }

    private static void writeCustom(ItemStack stack, NbtCompound nbt) {
        if (nbt.isEmpty()) stack.remove(DataComponentTypes.CUSTOM_DATA);
        else stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
    }

    private static TomeItem readTome(NbtCompound entry, String idKey, String tierKey) {
        try {
            SpellId id = SpellId.valueOf(entry.getString(idKey));
            TomeTier tier = TomeTier.valueOf(entry.getString(tierKey));
            return ModItems.getTomeFor(id, tier);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** All tomes equipped on this staff, in slot order. */
    public static List<TomeItem> getTomes(ItemStack staff) {
        NbtCompound nbt = readCustom(staff);
        List<TomeItem> tomes = new ArrayList<>();
        if (nbt.contains(NBT_TOMES, NbtElement.LIST_TYPE)) {
            NbtList list = nbt.getList(NBT_TOMES, NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < list.size(); i++) {
                TomeItem tome = readTome(list.getCompound(i), KEY_ID, KEY_TIER);
                if (tome != null) tomes.add(tome);
            }
        } else if (nbt.contains(LEGACY_ID)) {
            TomeItem tome = readTome(nbt, LEGACY_ID, LEGACY_TIER);
            if (tome != null) tomes.add(tome);
        }
        return tomes;
    }

    public static int getSelectedIndex(ItemStack staff) {
        int size = getTomes(staff).size();
        if (size == 0) return 0;
        return Math.floorMod(readCustom(staff).getInt(NBT_SELECTED), size);
    }

    /** The tome whose spell will be cast, or null if the staff is empty. */
    public static TomeItem getSelectedTome(ItemStack staff) {
        List<TomeItem> tomes = getTomes(staff);
        if (tomes.isEmpty()) return null;
        return tomes.get(Math.floorMod(readCustom(staff).getInt(NBT_SELECTED), tomes.size()));
    }

    private static void writeTomes(ItemStack staff, List<TomeItem> tomes, int selected) {
        NbtCompound nbt = readCustom(staff);
        nbt.remove(LEGACY_ID);
        nbt.remove(LEGACY_TIER);
        nbt.remove(LEGACY_XP);
        if (tomes.isEmpty()) {
            nbt.remove(NBT_TOMES);
            nbt.remove(NBT_SELECTED);
        } else {
            NbtList list = new NbtList();
            for (TomeItem tome : tomes) {
                NbtCompound entry = new NbtCompound();
                entry.putString(KEY_ID, tome.getSpell().name());
                entry.putString(KEY_TIER, tome.getTier().name());
                list.add(entry);
            }
            nbt.put(NBT_TOMES, list);
            nbt.putInt(NBT_SELECTED, Math.floorMod(selected, tomes.size()));
        }
        writeCustom(staff, nbt);
    }

    public static void setSelectedIndex(ItemStack staff, int index) {
        List<TomeItem> tomes = getTomes(staff);
        if (tomes.isEmpty()) return;
        writeTomes(staff, tomes, index);
    }

    public InsertResult canInsert(ItemStack staff, TomeItem tome) {
        if (!canAccept(tome)) return InsertResult.WRONG_TIER;
        List<TomeItem> tomes = getTomes(staff);
        for (TomeItem t : tomes) {
            if (t.getSpell() == tome.getSpell()) return InsertResult.DUPLICATE;
        }
        if (tomes.size() >= tomeSlots) return InsertResult.FULL;
        return InsertResult.OK;
    }

    /** Adds a tome to the staff and selects it. */
    public InsertResult insertTome(ItemStack staff, TomeItem tome) {
        InsertResult result = canInsert(staff, tome);
        if (result == InsertResult.OK) {
            List<TomeItem> tomes = getTomes(staff);
            tomes.add(tome);
            writeTomes(staff, tomes, tomes.size() - 1);
        }
        return result;
    }

    /** Removes the selected tome and returns it as a stack (EMPTY if the staff has none). */
    public static ItemStack removeSelectedTome(ItemStack staff) {
        List<TomeItem> tomes = getTomes(staff);
        if (tomes.isEmpty()) return ItemStack.EMPTY;
        int selected = getSelectedIndex(staff);
        TomeItem removed = tomes.remove(selected);
        // Keep the selection on the neighbour that slid into place (or the new last slot)
        writeTomes(staff, tomes, Math.min(selected, Math.max(0, tomes.size() - 1)));
        return new ItemStack(removed);
    }

    public static Text insertFailMessage(InsertResult result) {
        return switch (result) {
            case WRONG_TIER -> Text.literal("This staff cannot use that tome.");
            case FULL -> Text.literal("This staff has no free tome slots.");
            case DUPLICATE -> Text.literal("That tome is already on this staff.");
            case OK -> Text.empty();
        };
    }

    // ---------------------------------------------------------------------
    // Bundle-style inventory interaction
    // ---------------------------------------------------------------------

    /** Staff on the cursor, right-clicked onto a slot. */
    @Override
    public boolean onStackClicked(ItemStack staff, Slot slot, ClickType clickType, PlayerEntity player) {
        if (clickType != ClickType.RIGHT) return false;
        ItemStack slotStack = slot.getStack();

        if (slotStack.isEmpty()) {
            // Pull the selected tome out into the empty slot
            TomeItem selected = getSelectedTome(staff);
            if (selected == null || !slot.canInsert(new ItemStack(selected))) return false;
            slot.insertStack(removeSelectedTome(staff));
            player.playSound(SoundEvents.ITEM_BUNDLE_REMOVE_ONE, 0.8f, 0.8f + player.getWorld().getRandom().nextFloat() * 0.4f);
            return true;
        }

        if (slotStack.getItem() instanceof TomeItem tome) {
            if (!slot.canTakeItems(player)) return false;
            InsertResult result = insertTome(staff, tome);
            if (result == InsertResult.OK) {
                slot.takeStack(1);
                player.playSound(SoundEvents.ITEM_BUNDLE_INSERT, 0.8f, 0.8f + player.getWorld().getRandom().nextFloat() * 0.4f);
            } else if (!player.getWorld().isClient) {
                player.sendMessage(insertFailMessage(result), true);
            }
            return true;
        }
        return false;
    }

    /** Staff sitting in a slot, right-clicked with the cursor stack. */
    @Override
    public boolean onClicked(ItemStack staff, ItemStack cursor, Slot slot, ClickType clickType, PlayerEntity player, StackReference cursorRef) {
        if (clickType != ClickType.RIGHT || !slot.canTakePartial(player)) return false;

        if (cursor.isEmpty()) {
            // Pull the selected tome onto the cursor
            if (getTomes(staff).isEmpty()) return false;
            cursorRef.set(removeSelectedTome(staff));
            player.playSound(SoundEvents.ITEM_BUNDLE_REMOVE_ONE, 0.8f, 0.8f + player.getWorld().getRandom().nextFloat() * 0.4f);
            return true;
        }

        if (cursor.getItem() instanceof TomeItem tome) {
            InsertResult result = insertTome(staff, tome);
            if (result == InsertResult.OK) {
                cursor.decrement(1);
                player.playSound(SoundEvents.ITEM_BUNDLE_INSERT, 0.8f, 0.8f + player.getWorld().getRandom().nextFloat() * 0.4f);
            } else if (!player.getWorld().isClient) {
                player.sendMessage(insertFailMessage(result), true);
            }
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------------
    // Casting
    // ---------------------------------------------------------------------

    private static boolean canAfford(PlayerEntity p, int cost) {
        return p.isCreative() || getCurrentXpPoints(p) >= cost;
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
    private static int getEnchLevel(World world, ItemStack stack, net.minecraft.registry.RegistryKey<Enchantment> key) {
        var registry = world.getRegistryManager().get(RegistryKeys.ENCHANTMENT);
        var entry = registry.getEntry(key);
        return entry.map(e -> EnchantmentHelper.getLevel(e, stack)).orElse(0);
    }

    /**
     * Puts {@code tome} on cooldown after it was cast from {@code staff}: Quickcast shortens it, and it is
     * shared by every tome on the staff unless the staff has Attunement. A cooldown of 0 or less does nothing.
     */
    public static void applyCooldown(World world, PlayerEntity player, ItemStack staff, TomeItem tome, int cooldown) {
        if (cooldown <= 0) return;

        // --- QUICKCAST LOGIC ---
        int quickcastLevel = getEnchLevel(world, staff, ModEnchantments.QUICKCAST);
        if (quickcastLevel > 0) {
            float multiplier = switch (quickcastLevel) {
                case 1 -> 0.85f; // 15% faster
                case 2 -> 0.75f; // 25% faster
                default -> 0.50f; // 45% faster
            };
            cooldown = Math.max(2, (int)(cooldown * multiplier));
        }

        if (getEnchLevel(world, staff, ModEnchantments.ATTUNEMENT) > 0) {
            player.getItemCooldownManager().set(tome, cooldown);
        } else {
            for (TomeItem equipped : getTomes(staff)) {
                player.getItemCooldownManager().set(equipped, cooldown);
            }
        }
    }

    /** The staff in the player's hands (or, failing that, inventory) that carries {@code tome}, or EMPTY. */
    public static ItemStack findStaffWith(PlayerEntity player, TomeItem tome) {
        List<ItemStack> candidates = new ArrayList<>();
        candidates.add(player.getMainHandStack());
        candidates.add(player.getOffHandStack());
        for (int i = 0; i < player.getInventory().size(); i++) candidates.add(player.getInventory().getStack(i));
        for (ItemStack stack : candidates) {
            if (stack.getItem() instanceof StaffItem && getTomes(stack).contains(tome)) return stack;
        }
        return ItemStack.EMPTY;
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        ItemStack staff = player.getStackInHand(hand);

        TomeItem tome = getSelectedTome(staff);
        if (tome == null) {
            if (!world.isClient) player.sendMessage(Text.literal("No tome equipped."), true);
            return TypedActionResult.pass(staff);
        }

        net.ragnar.ragnarsmagicmod.item.spell.Spell spell = net.ragnar.ragnarsmagicmod.item.spell.Spells.get(tome.getSpell());
        if (spell == null) return TypedActionResult.pass(staff);

        // Cooldowns live on the tome items, so swapping spells never resets them
        if (player.getItemCooldownManager().isCoolingDown(tome) && !spell.ignoresCooldown(player)) {
            return TypedActionResult.fail(staff);
        }

        // --- RESERVE LOGIC ---
        int baseCost = spell.xpCost(player, tome.getXpCost());
        int reserveLevel = getEnchLevel(world, staff, ModEnchantments.RESERVE);
        int xpCost = baseCost;
        if (reserveLevel > 0 && baseCost > 0) {
            float multiplier = switch (reserveLevel) {
                case 1 -> 0.90f; // 10% off
                case 2 -> 0.85f; // 15% off
                default -> 0.75f; // 25% off
            };
            xpCost = Math.max(1, (int)(baseCost * multiplier));
        }

        if (!canAfford(player, xpCost)) {
            if (!world.isClient) player.sendMessage(Text.literal("Not enough XP."), true);
            return TypedActionResult.success(staff, world.isClient);
        }

        // XP is only spent once the spell actually goes off
        if (spell.cast(world, player, staff)) {
            if (!player.isCreative()) player.addExperience(-xpCost);
            player.incrementStat(Stats.USED.getOrCreateStat(this));
            staff.damage(1, player, hand == Hand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);

            applyCooldown(world, player, staff, tome, spell.cooldownAfterCast(player, tome.getCooldown()));
            return TypedActionResult.success(staff, world.isClient);
        }
        return TypedActionResult.pass(staff);
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        List<TomeItem> tomes = getTomes(stack);
        int selected = getSelectedIndex(stack);

        tooltip.add(Text.literal("Tomes: " + tomes.size() + "/" + tomeSlots).formatted(Formatting.GRAY));
        for (int i = 0; i < tomes.size(); i++) {
            TomeItem tome = tomes.get(i);
            boolean isSelected = i == selected;
            tooltip.add(Text.literal(isSelected ? " ▶ " : "   ")
                    .append(tome.getName())
                    .formatted(isSelected ? Formatting.GOLD : Formatting.DARK_GRAY));
        }
        TomeItem current = tomes.isEmpty() ? null : tomes.get(selected);
        if (current != null) {
            tooltip.add(Text.literal("Cost: " + current.getXpCost() + " XP").formatted(Formatting.BLUE));
        }
        tooltip.add(Text.literal("Right-click with a tome to equip it").formatted(Formatting.DARK_GRAY, Formatting.ITALIC));
        super.appendTooltip(stack, context, tooltip, type);
    }
}
