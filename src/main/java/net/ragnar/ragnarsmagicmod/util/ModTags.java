package net.ragnar.ragnarsmagicmod.util;

import net.minecraft.item.Item;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;

public class ModTags {
    public static class Items {
        public static final TagKey<Item> STAFFS = createTag("staffs");

        private static TagKey<Item> createTag(String name) {
            return TagKey.of(RegistryKeys.ITEM, Identifier.of(RagnarsMagicMod.MOD_ID, name));
        }
    }
}