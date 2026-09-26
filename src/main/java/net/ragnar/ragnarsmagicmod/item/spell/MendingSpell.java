package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Arm;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Tome of Mending: pours magic into a worn item and restores {@link #REPAIR_FRACTION} of its durability per cast.
 * It mends, in order of preference: whatever is in your other hand, then your most worn piece of armour, then the
 * staff itself. Casts are quick so topping an item up never feels like a chore, the chime climbs as the item
 * fills up, and nothing is spent if there's nothing to mend.
 */
public class MendingSpell implements Spell {
    private static final float REPAIR_FRACTION = 0.05f;

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw)) return false;

        boolean staffInMain = player.getMainHandStack() == staff;
        ItemStack target = pick(player, staff, staffInMain);
        if (target == null) {
            player.sendMessage(Text.literal("Nothing to mend.").formatted(Formatting.GRAY), true);
            return false;
        }

        int max = target.getMaxDamage();
        float before = 1f - target.getDamage() / (float) max;
        target.setDamage(Math.max(0, target.getDamage() - Math.max(1, MathHelper.ceil(max * REPAIR_FRACTION))));
        float after = 1f - target.getDamage() / (float) max;
        boolean full = !target.isDamaged();

        player.sendMessage(Text.empty()
                .append(target.getName().copy().formatted(Formatting.WHITE))
                .append(Text.literal("  " + pct(before) + " → ").formatted(Formatting.GRAY))
                .append(Text.literal(full ? "Fully mended!" : pct(after)).formatted(full ? Formatting.GOLD : Formatting.GREEN)), true);

        effects(sw, player, staffInMain, target == player.getMainHandStack() || target == player.getOffHandStack(), after, full);
        return true;
    }

    /** The other hand's item, else the most worn armour piece, else the staff - whichever needs it. */
    private static ItemStack pick(PlayerEntity player, ItemStack staff, boolean staffInMain) {
        ItemStack other = staffInMain ? player.getOffHandStack() : player.getMainHandStack();
        if (needsMending(other)) return other;

        ItemStack worst = null;
        float worstLeft = 1f;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack armor = player.getEquippedStack(slot);
            if (!needsMending(armor)) continue;
            float left = 1f - armor.getDamage() / (float) armor.getMaxDamage();
            if (left < worstLeft) {
                worstLeft = left;
                worst = armor;
            }
        }
        if (worst != null) return worst;
        return needsMending(staff) ? staff : null;
    }

    private static boolean needsMending(ItemStack stack) {
        return !stack.isEmpty() && stack.isDamageable() && stack.isDamaged();
    }

    private static String pct(float f) {
        return Math.round(f * 100) + "%";
    }

    private static void effects(ServerWorld world, PlayerEntity player, boolean staffInMain, boolean inHand, float after, boolean full) {
        // Where the magic flows to: the other hand, or the middle of your body for armour
        Vec3d look = player.getRotationVector();
        Vec3d right = new Vec3d(-look.z, 0, look.x).normalize();
        Arm otherArm = staffInMain ? player.getMainArm().getOpposite() : player.getMainArm();
        Vec3d to = inHand
                ? player.getEyePos().add(look.multiply(0.5)).add(right.multiply(otherArm == Arm.RIGHT ? 0.35 : -0.35)).add(0, -0.45, 0)
                : player.getPos().add(0, 1.0, 0);

        // Enchanting glyphs stream in from all around (they fly from the offset toward the point)
        for (int i = 0; i < 40; i++) {
            double a = world.random.nextDouble() * Math.PI * 2;
            double r = 1.2 + world.random.nextDouble() * 0.8;
            world.spawnParticles(ParticleTypes.ENCHANT, to.x, to.y, to.z, 0,
                    Math.cos(a) * r, world.random.nextDouble() * 1.5 - 0.3, Math.sin(a) * r, 1.0);
        }
        world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, to.x, to.y, to.z, 6, 0.2, 0.2, 0.2, 0);

        Vec3d p = player.getPos();
        world.playSound(null, p.x, p.y, p.z, SoundEvents.BLOCK_ANVIL_USE, SoundCategory.PLAYERS, 0.35f, 1.7f);
        world.playSound(null, p.x, p.y, p.z, SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.7f, 0.9f + world.random.nextFloat() * 0.3f);
        // The chime climbs as the item fills up
        world.playSound(null, p.x, p.y, p.z, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.2f, 0.7f + 1.2f * after);
        if (full) {
            world.playSound(null, p.x, p.y, p.z, SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.6f, 1.6f);
            world.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, to.x, to.y, to.z, 20, 0.2, 0.2, 0.2, 0.25);
        }
    }
}
