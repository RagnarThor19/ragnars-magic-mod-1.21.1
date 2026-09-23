package net.ragnar.ragnarsmagicmod.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.player.ItemCooldownManager;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A staff's cooldown sweep in the hotbar/inventory shows its currently selected spell's cooldown. */
@Mixin(DrawContext.class)
public class DrawContextMixin {
    @WrapOperation(method = "drawItemInSlot(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/item/ItemStack;IILjava/lang/String;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/ItemCooldownManager;getCooldownProgress(Lnet/minecraft/item/Item;F)F"))
    private float ragnarsmagicmod$selectedSpellCooldown(ItemCooldownManager cooldowns, Item item, float tickDelta,
                                                        Operation<Float> original, @Local(argsOnly = true) ItemStack stack) {
        if (item instanceof StaffItem) {
            TomeItem tome = StaffItem.getSelectedTome(stack);
            if (tome != null) return original.call(cooldowns, tome, tickDelta);
        }
        return original.call(cooldowns, item, tickDelta);
    }
}
