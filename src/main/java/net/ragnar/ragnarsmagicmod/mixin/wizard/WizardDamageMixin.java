package net.ragnar.ragnarsmagicmod.mixin.wizard;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.ragnar.ragnarsmagicmod.wizard.WanderingWizardEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** The Wandering Wizard's spells hit softer than a player's casting the same tome. */
@Mixin(LivingEntity.class)
public abstract class WizardDamageMixin {
    @ModifyVariable(method = "damage", at = @At("HEAD"), argsOnly = true)
    private float ragnarsmagicmod$softenWizardSpells(float amount, DamageSource source, float original) {
        if (source.getAttacker() instanceof WanderingWizardEntity wizard && wizard != (Object) this) {
            return amount * WanderingWizardEntity.SPELL_DAMAGE_SCALE;
        }
        return amount;
    }
}
