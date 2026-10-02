package net.ragnar.ragnarsmagicmod.mixin;

import net.minecraft.entity.player.ItemCooldownManager;
import net.minecraft.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** Read-only view of a player's cooldowns, so tome cooldowns can outlast leaving and dying (see TomeCooldowns). */
@Mixin(ItemCooldownManager.class)
public interface ItemCooldownManagerAccessor {
    @Accessor("entries")
    Map<Item, Object> ragnarsmagicmod$getEntries();

    @Accessor("tick")
    int ragnarsmagicmod$getTick();
}
