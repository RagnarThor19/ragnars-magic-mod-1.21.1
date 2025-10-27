package net.ragnar.ragnarsmagicmod;

import net.fabricmc.api.ModInitializer;

import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.spell.GhastFireballSpell;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RagnarsMagicMod implements ModInitializer {
	public static final String MOD_ID = "ragnarsmagicmod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
        LOGGER.info("Initializing {}", MOD_ID);
        ModItems.registerModItems();
        net.ragnar.ragnarsmagicmod.item.spell.Spells.register(

            net.ragnar.ragnarsmagicmod.item.spell.SpellId.FIREBALLS,
            new net.ragnar.ragnarsmagicmod.item.spell.FireballSpell()
        );
        Spells.register(SpellId.GHAST_FIREBALL, new GhastFireballSpell(1)); // power 1 is safe
        net.ragnar.ragnarsmagicmod.entity.ModEntities.register();

        net.ragnar.ragnarsmagicmod.item.spell.Spells.register(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.ICE_SHARDS,
                new net.ragnar.ragnarsmagicmod.item.spell.IceShardsSpell()
        );

        net.ragnar.ragnarsmagicmod.item.spell.Spells.register(
                SpellId.FALLING_ANVILS,
                new net.ragnar.ragnarsmagicmod.item.spell.FallingAnvilsSpell()
        );

        net.ragnar.ragnarsmagicmod.item.spell.Spells.register(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.METEOR,
                new net.ragnar.ragnarsmagicmod.item.spell.MeteorSpell()
        );

        net.ragnar.ragnarsmagicmod.item.spell.Spells.register(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.FALLING_STALACTITE,
                new net.ragnar.ragnarsmagicmod.item.spell.FallingStalactiteSpell()
        );

        net.ragnar.ragnarsmagicmod.item.spell.Spells.register(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.WIND_PUSH,
                new net.ragnar.ragnarsmagicmod.item.spell.WindPushSpell()
        );

        net.ragnar.ragnarsmagicmod.item.spell.Spells.register(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.BLINK,
                new net.ragnar.ragnarsmagicmod.item.spell.BlinkSpell()
        );

        net.ragnar.ragnarsmagicmod.item.spell.Spells.register(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.RISING_SPIKES,
                new net.ragnar.ragnarsmagicmod.item.spell.RisingSpikesSpell()
        );

        net.ragnar.ragnarsmagicmod.item.spell.Spells.register(
                net.ragnar.ragnarsmagicmod.item.spell.SpellId.DASH,
                new net.ragnar.ragnarsmagicmod.item.spell.DashSpell()
        );


    }
}