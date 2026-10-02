package net.ragnar.ragnarsmagicmod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** When one cooldown entry runs out (the class is package-private, hence the string target). */
@Mixin(targets = "net.minecraft.entity.player.ItemCooldownManager$Entry")
public interface ItemCooldownEntryAccessor {
    @Accessor("endTick")
    int ragnarsmagicmod$getEndTick();
}
