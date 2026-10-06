package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Every item the mod adds shows up in some creative tab. */
public class CreativeTabGameTests implements FabricGameTest {
    /** Items that only exist so a projectile has something to draw; nothing a player should hold. */
    private static final Set<String> PROJECTILE_ONLY = Set.of("ice_shard", "rock", "jaunting_kunai");

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void everyItemIsInACreativeTab(TestContext ctx) {
        ItemGroups.updateDisplayContext(ctx.getWorld().getEnabledFeatures(), true, ctx.getWorld().getRegistryManager());
        Set<Item> shown = new HashSet<>();
        for (ItemGroup group : Registries.ITEM_GROUP) {
            for (ItemStack stack : group.getDisplayStacks()) shown.add(stack.getItem());
        }
        List<String> missing = new ArrayList<>();
        for (Item item : Registries.ITEM) {
            var id = Registries.ITEM.getId(item);
            if (id.getNamespace().equals(RagnarsMagicMod.MOD_ID) && !shown.contains(item) && !PROJECTILE_ONLY.contains(id.getPath())) missing.add(id.getPath());
        }
        ctx.assertTrue(missing.isEmpty(), "not in any creative tab: " + missing);
        ctx.complete();
    }
}
