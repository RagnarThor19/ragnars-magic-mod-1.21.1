// File: src/main/java/net/ragnar/ragnarsmagicmod/item/spell/InvisibilitySpell.java
package net.ragnar.ragnarsmagicmod.item.spell;

import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.world.World;

public class InvisibilitySpell implements Spell {
    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return true;

        // Apply Invisibility: 20 seconds (400 ticks), Amplifier 0
        // boolean ambient = false, boolean visible = false (NO PARTICLES), boolean showIcon = true
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.INVISIBILITY, 400, 0, false, false, true));

        // Play a subtle "vanishing" sound
        world.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BLOCK_ENCHANTMENT_TABLE_USE, SoundCategory.PLAYERS, 0.3f, 1.2f);
        world.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ENTITY_ILLUSIONER_PREPARE_MIRROR, SoundCategory.PLAYERS, 0.3f, 1.8f);

        return true;
    }
}