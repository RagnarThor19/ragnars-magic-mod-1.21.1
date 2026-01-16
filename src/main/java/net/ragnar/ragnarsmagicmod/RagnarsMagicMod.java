package net.ragnar.ragnarsmagicmod;

import net.fabricmc.api.ModInitializer;
import net.ragnar.ragnarsmagicmod.enchantment.ModEnchantments;
import net.ragnar.ragnarsmagicmod.entity.ModEntities;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.spell.GhastFireballSpell;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.util.ModLootTableModifiers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RagnarsMagicMod implements ModInitializer {
    public static final String MOD_ID = "ragnarsmagicmod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing {}", MOD_ID);

        ModItems.registerModItems();
        ModEnchantments.registerModEnchantments(); // Just loads the keys

        net.ragnar.ragnarsmagicmod.sound.ModSoundEvents.init();
        ModEntities.registerModEntities();

        // Register Loot Table Modifiers
        ModLootTableModifiers.modifyLootTables();

        // Spell Registration
        Spells.register(SpellId.FIREBALLS, new net.ragnar.ragnarsmagicmod.item.spell.FireballSpell());
        Spells.register(SpellId.GHAST_FIREBALL, new GhastFireballSpell(1));
        Spells.register(SpellId.ICE_SHARDS, new net.ragnar.ragnarsmagicmod.item.spell.IceShardsSpell());
        Spells.register(SpellId.FALLING_ANVILS, new net.ragnar.ragnarsmagicmod.item.spell.FallingAnvilsSpell());
        Spells.register(SpellId.METEOR, new net.ragnar.ragnarsmagicmod.item.spell.MeteorSpell());
        Spells.register(SpellId.FALLING_STALACTITE, new net.ragnar.ragnarsmagicmod.item.spell.FallingStalactiteSpell());
        Spells.register(SpellId.WIND_PUSH, new net.ragnar.ragnarsmagicmod.item.spell.WindPushSpell());
        Spells.register(SpellId.BLINK, new net.ragnar.ragnarsmagicmod.item.spell.BlinkSpell());
        Spells.register(SpellId.RISING_SPIKES, new net.ragnar.ragnarsmagicmod.item.spell.RisingSpikesSpell());
        Spells.register(SpellId.DASH, new net.ragnar.ragnarsmagicmod.item.spell.DashSpell());
        Spells.register(SpellId.WIND_CHARGE, new net.ragnar.ragnarsmagicmod.item.spell.WindChargeSpell());
        Spells.register(SpellId.WITHER_SKULL, new net.ragnar.ragnarsmagicmod.item.spell.WitherSkullSpell());
        Spells.register(SpellId.ZAP, new net.ragnar.ragnarsmagicmod.item.spell.ZapSpell());
        Spells.register(SpellId.LIGHTNING, new net.ragnar.ragnarsmagicmod.item.spell.LightningSpell());
        Spells.register(SpellId.LIGHTNING_CASCADE, new net.ragnar.ragnarsmagicmod.item.spell.LightningCascadeSpell());
        Spells.register(SpellId.ENERGY_ORB, new net.ragnar.ragnarsmagicmod.item.spell.EnergyOrbSpell());
        Spells.register(SpellId.SONIC_BOOM, new net.ragnar.ragnarsmagicmod.item.spell.SonicBoomSpell());
        Spells.register(SpellId.REJUVENATION, new net.ragnar.ragnarsmagicmod.item.spell.RejuvenationSpell());
        Spells.register(SpellId.LIGHT, new net.ragnar.ragnarsmagicmod.item.spell.LightOrbSpell());
        Spells.register(SpellId.AEGIS, new net.ragnar.ragnarsmagicmod.item.spell.AegisSpell());
        Spells.register(SpellId.INSIGHT, new net.ragnar.ragnarsmagicmod.item.spell.InsightSpell());
        Spells.register(SpellId.TRACKING, new net.ragnar.ragnarsmagicmod.item.spell.TrackingSpell());
        Spells.register(SpellId.SUN, new net.ragnar.ragnarsmagicmod.item.spell.SunSpell());
        Spells.register(SpellId.MINING, new net.ragnar.ragnarsmagicmod.item.spell.MiningSpell());
        Spells.register(SpellId.DRAGON_BREATH, new net.ragnar.ragnarsmagicmod.item.spell.DragonBreathSpell());
        Spells.register(SpellId.GRAVITY, new net.ragnar.ragnarsmagicmod.item.spell.GravitySpell());
        Spells.register(SpellId.ICE_BEAM, new net.ragnar.ragnarsmagicmod.item.spell.IceBeamSpell());
        Spells.register(SpellId.VORTEX, new net.ragnar.ragnarsmagicmod.item.spell.VortexSpell());
        Spells.register(SpellId.PUSHBACK, new net.ragnar.ragnarsmagicmod.item.spell.PushbackSpell());
        Spells.register(SpellId.RECALLING, new net.ragnar.ragnarsmagicmod.item.spell.RecallingSpell());
        Spells.register(SpellId.FANGS, new net.ragnar.ragnarsmagicmod.item.spell.FangsSpell());
        Spells.register(SpellId.GHOSTSTEP, new net.ragnar.ragnarsmagicmod.item.spell.GhoststepSpell());
        Spells.register(SpellId.SUMMON_STEVE, new net.ragnar.ragnarsmagicmod.item.spell.SummonSteveSpell());
        Spells.register(SpellId.GROWTH, new net.ragnar.ragnarsmagicmod.item.spell.GrowthSpell());
        Spells.register(SpellId.ARROW_VOLLEY, new net.ragnar.ragnarsmagicmod.item.spell.ArrowVolleySpell());
        Spells.register(SpellId.BOOMING, new net.ragnar.ragnarsmagicmod.item.spell.BoomingSpell());
        Spells.register(SpellId.TORCHES, new net.ragnar.ragnarsmagicmod.item.spell.TorchesSpell());
        Spells.register(SpellId.IMPALING, new net.ragnar.ragnarsmagicmod.item.spell.ImpalingSpell());
        Spells.register(SpellId.INVISIBILITY, new net.ragnar.ragnarsmagicmod.item.spell.InvisibilitySpell());
    }
}