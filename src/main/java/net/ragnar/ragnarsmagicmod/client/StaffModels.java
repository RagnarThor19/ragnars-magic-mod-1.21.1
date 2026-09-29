package net.ragnar.ragnarsmagicmod.client;

import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedModelManager;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.ModItems;

import java.util.Map;

/**
 * Staffs are 3D in your hand but a flat sprite in the inventory, in item frames and on the ground, like the trident
 * and spyglass. The item's own model (models/item/golden_staff.json...) is the 3D one; the flat one is
 * models/item/golden_staff_inventory.json, swapped in by ItemRendererMixin.
 */
public final class StaffModels {
    private StaffModels() {}

    private static Map<Item, Identifier> inventoryModels;

    public static void init() {
        ModelLoadingPlugin.register(context -> context.addModels(
                inventoryModel("golden_staff"), inventoryModel("diamond_staff"), inventoryModel("netherite_staff")));
    }

    private static Identifier inventoryModel(String staff) {
        return Identifier.of(RagnarsMagicMod.MOD_ID, "item/" + staff + "_inventory");
    }

    /** The flat model to draw instead, or null to keep {@code model}. */
    public static BakedModel flatModel(ItemStack stack, ModelTransformationMode mode) {
        if (mode != ModelTransformationMode.GUI && mode != ModelTransformationMode.GROUND && mode != ModelTransformationMode.FIXED) return null;
        if (inventoryModels == null) {
            inventoryModels = Map.of(
                    ModItems.GOLDEN_STAFF, inventoryModel("golden_staff"),
                    ModItems.DIAMOND_STAFF, inventoryModel("diamond_staff"),
                    ModItems.NETHERITE_STAFF, inventoryModel("netherite_staff"));
        }
        Identifier id = inventoryModels.get(stack.getItem());
        if (id == null) return null;
        BakedModelManager models = MinecraftClient.getInstance().getBakedModelManager();
        BakedModel flat = models.getModel(id);
        // Without the flat model file, just draw the staff's own model everywhere
        return flat == null || flat == models.getMissingModel() ? null : flat;
    }
}
