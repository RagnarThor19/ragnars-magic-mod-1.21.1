package net.ragnar.ragnarsmagicmod.mixin;

import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the Tome of Lunging's stab land at full strength, as if the swing were fully charged (see Lunging). */
@Mixin(LivingEntity.class)
public interface LivingEntityAttackAccessor {
    @Accessor("lastAttackedTicks")
    void ragnarsmagicmod$setLastAttackedTicks(int ticks);
}
