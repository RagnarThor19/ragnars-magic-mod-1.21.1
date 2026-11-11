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
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Every item the mod adds shows up in some creative tab, and every tome in its own tier's section of Tools & Utilities. */
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

    /**
     * Every tome is in Tools & Utilities, inside its own tier's section: all the beginner tomes, then all the advanced
     * ones, then all the master ones, then the passive ones. Tomes added later (anywhere in the mod) are checked too, since it looks at every
     * registered TomeItem rather than a list.
     */
    @GameTest(templateName = EMPTY_STRUCTURE)
    public void everyTomeIsInItsTiersSectionOfTheToolsTab(TestContext ctx) {
        ItemGroups.updateDisplayContext(ctx.getWorld().getEnabledFeatures(), true, ctx.getWorld().getRegistryManager());
        ItemGroup tools = Registries.ITEM_GROUP.get(ItemGroups.TOOLS);

        // The tomes in the order the tab shows them
        List<TomeItem> order = new ArrayList<>();
        for (ItemStack stack : tools.getDisplayStacks()) {
            if (stack.getItem() instanceof TomeItem tome && !order.contains(tome)) order.add(tome);
        }

        List<String> missing = new ArrayList<>();
        for (Item item : Registries.ITEM) {
            if (item instanceof TomeItem tome && !order.contains(tome)) missing.add(Registries.ITEM.getId(item).getPath());
        }
        ctx.assertTrue(missing.isEmpty(), "tomes not in Tools & Utilities: " + missing);

        // Sections never go backwards: a tome ranked below the one before it is out of place
        List<String> misplaced = new ArrayList<>();
        TomeTier section = TomeTier.BEGINNER;
        for (int i = 0; i < order.size(); i++) {
            TomeItem tome = order.get(i);
            if (tome.getTier().ordinal() < section.ordinal()) {
                misplaced.add(Registries.ITEM.getId(tome).getPath() + " (" + tome.getTier() + ", after "
                        + Registries.ITEM.getId(order.get(i - 1)).getPath() + " in the " + section + " section)");
            } else {
                section = tome.getTier();
            }
        }
        ctx.assertTrue(misplaced.isEmpty(), "tomes outside their tier's section: " + misplaced);
        RagnarsMagicMod.LOGGER.info("[TOMES] {} tomes: {} beginner, {} advanced, {} master, {} passive", order.size(),
                order.stream().filter(t -> t.getTier() == TomeTier.BEGINNER).count(),
                order.stream().filter(t -> t.getTier() == TomeTier.ADVANCED).count(),
                order.stream().filter(t -> t.getTier() == TomeTier.MASTER).count(),
                order.stream().filter(t -> t.getTier() == TomeTier.PASSIVE).count());
        RagnarsMagicMod.LOGGER.info("[TOMES] order: {}", order.stream().map(t -> Registries.ITEM.getId(t).getPath()).toList());
        ctx.complete();
    }
}
