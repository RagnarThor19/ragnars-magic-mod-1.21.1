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
            if (!world.isClient) {
                if (getSocketed(staff) != null && !player.isCreative()) {
                    player.giveItemStack(new ItemStack(ModItems.TOME_OF_FIREBALLS)); // MVP: only fireballs
                }
                NbtCompound nbt = readCustom(staff);
                nbt.remove(NBT_ID);
                nbt.remove(NBT_TIER);
                nbt.remove(NBT_XP);
                writeCustom(staff, nbt);
                player.sendMessage(Text.literal("Staff socket cleared."), true);
            }
            return TypedActionResult.success(staff);
        }

        // ---------- Cast ----------
        SpellId id = getSocketed(staff);
        if (id == null) {
            if (!world.isClient)
                player.sendMessage(Text.literal("No tome socketed."), true);
            return TypedActionResult.pass(staff);
        }

        if (id == SpellId.FIREBALLS) {
            int xpCost = getXpCost(staff);
            if (!spendXp(player, xpCost)) {
                if (!world.isClient)
                    player.sendMessage(Text.literal("Not enough XP."), true);
                return TypedActionResult.fail(staff);
            }

            if (!world.isClient) {
                var look = player.getRotationVec(1.0f);

                // spawn a bit in front of the player's eyes
                double sx = player.getX() + look.x * 1.5;
                double sy = player.getEyeY();
                double sz = player.getZ() + look.z * 1.5;

                SmallFireballEntity fb = new SmallFireballEntity(
                        net.minecraft.entity.EntityType.SMALL_FIREBALL,
                        world
                );
                fb.setOwner(player);                 // attribute the projectile
                fb.setPosition(sx, sy, sz);          // where to spawn
                fb.setVelocity(look.x * 0.8, look.y * 0.8, look.z * 0.8); // give it speed

                world.spawnEntity(fb);

                // cooldown + durability + stat
                player.getItemCooldownManager().set(this, 20);
                EquipmentSlot slot = (hand == Hand.MAIN_HAND) ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND;
                staff.damage(1, player, slot);
                player.incrementStat(Stats.USED.getOrCreateStat(this));
                world.playSoundFromEntity(
                        null,                               // send to all nearby players
                        player,                             // source of the sound
                        SoundEvents.ITEM_FIRECHARGE_USE,    // which sound
                        SoundCategory.PLAYERS,              // category
                        0.3f,                               // volume
                        0.8f                                // pitch
                );

            }

            return TypedActionResult.success(staff, world.isClient);
        }

        return TypedActionResult.pass(staff);
    }
}
