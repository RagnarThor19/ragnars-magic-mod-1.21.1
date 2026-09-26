package net.ragnar.ragnarsmagicmod.mixin;

import net.minecraft.entity.mob.CreeperEntity;
import net.ragnar.ragnarsmagicmod.item.spell.IceBeamSpell;
import net.ragnar.ragnarsmagicmod.item.spell.VinesSpell;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A creeper wrapped up by the Tome of Vines or frozen by the Tome of Ice Beam can't go off, even one lit with flint and steel. */
@Mixin(CreeperEntity.class)
public class CreeperEntityMixin {
    @Inject(method = "explode", at = @At("HEAD"), cancellable = true)
    private void ragnarsmagicmod$smothered(CallbackInfo ci) {
        CreeperEntity creeper = (CreeperEntity) (Object) this;
        if (VinesSpell.isHeld(creeper) || IceBeamSpell.isFrozen(creeper)) ci.cancel();
    }
}
