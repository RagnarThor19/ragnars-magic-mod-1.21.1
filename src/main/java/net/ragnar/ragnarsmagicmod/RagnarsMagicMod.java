package net.ragnar.ragnarsmagicmod;

import net.fabricmc.api.ModInitializer;

import net.ragnar.ragnarsmagicmod.item.ModItems;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RagnarsMagicMod implements ModInitializer {
	public static final String MOD_ID = "ragnarsmagicmod";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
        ModItems.registerModItems();
	}
}