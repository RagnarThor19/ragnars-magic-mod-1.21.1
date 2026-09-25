package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.SmeltingRecipe;
import net.minecraft.recipe.input.SingleStackRecipeInput;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.Optional;

/**
 * Tome of Smelting: smelts one of whatever you hold in your other hand, instantly, by the furnace's own
 * recipes. Only stackable things - tools, armour and weapons are never touched.
 */
public class SmeltingSpell implements Spell {
    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        if (!(world instanceof ServerWorld sw)) return false;

        Hand other = player.getMainHandStack() == staff ? Hand.OFF_HAND : Hand.MAIN_HAND;
        ItemStack input = player.getStackInHand(other);
        if (input.isEmpty()) {
            player.sendMessage(Text.literal("Hold something to smelt in your other hand."), true);
            return false;
        }
        // Keeps gear safe: iron tools and armour would otherwise melt down into nuggets
        if (input.getMaxCount() == 1 || input.isDamageable()) {
            player.sendMessage(Text.literal("That's too precious to melt down."), true);
            return false;
        }

        Optional<RecipeEntry<SmeltingRecipe>> recipe = sw.getRecipeManager()
                .getFirstMatch(RecipeType.SMELTING, new SingleStackRecipeInput(input), sw);
        if (recipe.isEmpty()) {
            player.sendMessage(Text.literal("That can't be smelted."), true);
            return false;
        }
        ItemStack result = recipe.get().value().craft(new SingleStackRecipeInput(input), sw.getRegistryManager());
        if (result.isEmpty()) return false;

        input.decrement(1);
        if (!player.getInventory().insertStack(result)) player.dropItem(result, false);

        // A breath of furnace heat from your hand
        Vec3d hand = handPos(player, other);
        sw.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BLOCK_FURNACE_FIRE_CRACKLE, SoundCategory.PLAYERS, 0.9f, 1.3f);
        sw.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ITEM_FIRECHARGE_USE, SoundCategory.PLAYERS, 0.15f, 1.8f);
        sw.spawnParticles(ParticleTypes.FLAME, hand.x, hand.y, hand.z, 4, 0.08, 0.08, 0.08, 0.01);
        sw.spawnParticles(ParticleTypes.SMOKE, hand.x, hand.y + 0.1, hand.z, 2, 0.05, 0.05, 0.05, 0.01);
        return true;
    }

    /** Roughly where the item in {@code hand} is held. */
    private static Vec3d handPos(PlayerEntity player, Hand hand) {
        boolean right = (hand == Hand.MAIN_HAND) == (player.getMainArm() == net.minecraft.util.Arm.RIGHT);
        float yaw = player.getBodyYaw() * MathHelper.RADIANS_PER_DEGREE;
        Vec3d forward = new Vec3d(-MathHelper.sin(yaw), 0, MathHelper.cos(yaw));
        Vec3d side = new Vec3d(-forward.z, 0, forward.x).multiply(right ? -1 : 1);
        return player.getPos().add(0, 1.0, 0).add(forward.multiply(0.4)).add(side.multiply(0.35));
    }
}
