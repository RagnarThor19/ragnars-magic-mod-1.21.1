package net.ragnar.ragnarsmagicmod.mixin;

import net.minecraft.entity.mob.CreeperEntity;
import net.ragnar.ragnarsmagicmod.item.spell.VinesSpell;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A creeper wrapped up by the Tome of Vines can't go off, even one lit with flint and steel. */
@Mixin(CreeperEntity.class)
public class CreeperEntityMixin {
    @Inject(method = "explode", at = @At("HEAD"), cancellable = true)
    private void ragnarsmagicmod$smothered(CallbackInfo ci) {
        if (VinesSpell.isHeld((CreeperEntity) (Object) this)) ci.cancel();
    }
}
