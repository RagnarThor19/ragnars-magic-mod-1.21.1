package net.ragnar.ragnarsmagicmod.mixin;

import net.minecraft.entity.projectile.FishingBobberEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the Tome of Fishing's tests see and set the bobber's countdowns and luck. */
@Mixin(FishingBobberEntity.class)
public interface FishingBobberAccessor {
    @Accessor("luckBonus")
    int ragnarsmagicmod$getLuckBonus();

    @Accessor("hookCountdown")
    void ragnarsmagicmod$setHookCountdown(int ticks);

    @Accessor("waitCountdown")
    int ragnarsmagicmod$getWaitCountdown();

    @Accessor("waitCountdown")
    void ragnarsmagicmod$setWaitCountdown(int ticks);
}
