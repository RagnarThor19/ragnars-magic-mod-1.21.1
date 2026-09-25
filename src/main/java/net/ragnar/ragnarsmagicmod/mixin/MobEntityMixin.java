package net.ragnar.ragnarsmagicmod.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.ragnar.ragnarsmagicmod.item.spell.ControllingSpell;
import net.ragnar.ragnarsmagicmod.item.spell.IllusionSpell;
import net.ragnar.ragnarsmagicmod.item.spell.VinesSpell;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A mob possessed with the Tome of Controlling is steered by its player instead of its AI. */
@Mixin(MobEntity.class)
public class MobEntityMixin {
    @Inject(method = "tickNewAi", at = @At("HEAD"), cancellable = true)
    private void ragnarsmagicmod$possessedBrain(CallbackInfo ci) {
        if (ControllingSpell.drive((MobEntity) (Object) this)) ci.cancel();
        // Wrapped up by the Tome of Vines: no goals, no brain, no attacks
        else if (VinesSpell.isHeld((MobEntity) (Object) this)) ci.cancel();
    }

    @Inject(method = "checkDespawn", at = @At("HEAD"), cancellable = true)
    private void ragnarsmagicmod$keepHost(CallbackInfo ci) {
        if (ControllingSpell.isControlled((MobEntity) (Object) this)) ci.cancel();
    }

    @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
    private void ragnarsmagicmod$ignoreEmptyBody(LivingEntity target, CallbackInfo ci) {
        if (target != null && (ControllingSpell.isPossessing(target) || IllusionSpell.isGhost(target))) ci.cancel();
    }
}
