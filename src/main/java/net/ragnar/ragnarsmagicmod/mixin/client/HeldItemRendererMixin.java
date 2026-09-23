package net.ragnar.ragnarsmagicmod.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.item.ItemStack;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Swapping spells or taking durability changes the staff's data; don't replay the equip animation for that. */
@Mixin(HeldItemRenderer.class)
public class HeldItemRendererMixin {
    @WrapOperation(method = "updateHeldItems", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/item/ItemStack;areEqual(Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ItemStack;)Z"))
    private boolean ragnarsmagicmod$ignoreStaffData(ItemStack left, ItemStack right, Operation<Boolean> original) {
        if (left.getItem() instanceof StaffItem && left.isOf(right.getItem()) && left.getCount() == right.getCount()) {
            return true;
        }
        return original.call(left, right);
    }
}
