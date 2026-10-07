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
        net.ragnar.ragnarsmagicmod.knight.Knight.register(); // Tome of Knight
        net.ragnar.ragnarsmagicmod.jaunting.Jaunting.register(); // Tome of Jaunting
        net.ragnar.ragnarsmagicmod.ricochet.Ricochet.register(); // Tome of Ricochet
        net.ragnar.ragnarsmagicmod.jumping.Jumping.register(); // Tome of Jumping
        net.ragnar.ragnarsmagicmod.logs.Logs.register(); // Tome of Logs
        net.ragnar.ragnarsmagicmod.balllightning.BallLightning.register(); // Tome of Ball Lightning
        net.ragnar.ragnarsmagicmod.impulse.Impulse.register(); // Tome of Impulse
        net.ragnar.ragnarsmagicmod.sight.Sight.register(); // Tome of Sight
        net.ragnar.ragnarsmagicmod.shadowhands.ShadowHands.register(); // Tome of Unseen Hands
        net.ragnar.ragnarsmagicmod.beaming.Beaming.register(); // Tome of Beaming
        net.ragnar.ragnarsmagicmod.upsidedown.UpsideDown.register(); // Tome of Upside Down
        net.ragnar.ragnarsmagicmod.boulders.Boulders.register(); // Tome of Boulders
        net.ragnar.ragnarsmagicmod.dragon.Dragon.register(); // Tome of the Dragon
        net.ragnar.ragnarsmagicmod.moon.Moon.register(); // Tome of the Moon
        net.ragnar.ragnarsmagicmod.mightypush.MightyPush.register(); // Tome of Mighty Pushing
        net.ragnar.ragnarsmagicmod.lightningpath.LightningPath.register(); // Tome of the Lightning Path
        net.ragnar.ragnarsmagicmod.network.SelectSpellPayload.register();
        net.ragnar.ragnarsmagicmod.network.TelekinesisPayloads.register();
        net.ragnar.ragnarsmagicmod.network.PossessionPayloads.register();
        net.ragnar.ragnarsmagicmod.network.CloudPayload.register();
        net.ragnar.ragnarsmagicmod.network.IllusionPayload.register();
        net.ragnar.ragnarsmagicmod.network.DimensionSplitPayload.register();
        net.ragnar.ragnarsmagicmod.network.WingsPayload.register();
        net.ragnar.ragnarsmagicmod.network.TntPayloads.register();
        net.ragnar.ragnarsmagicmod.network.DayShiftPayload.register();
        net.ragnar.ragnarsmagicmod.network.RewindPayload.register();
        net.ragnar.ragnarsmagicmod.network.ShakePayload.register();
        net.ragnar.ragnarsmagicmod.network.CloneSwapPayload.register();
        net.ragnar.ragnarsmagicmod.network.CloneTimerPayload.register();
        net.ragnar.ragnarsmagicmod.network.IceBeamPayload.register();
        net.ragnar.ragnarsmagicmod.network.ZapPayload.register();
        net.ragnar.ragnarsmagicmod.network.FairyPayload.register();
        net.ragnar.ragnarsmagicmod.network.BlinkPayload.register();
        net.ragnar.ragnarsmagicmod.network.SprayPayload.register();
        net.ragnar.ragnarsmagicmod.network.SlipperyPayload.register();
        net.ragnar.ragnarsmagicmod.network.TestEntityPayload.register();
        net.ragnar.ragnarsmagicmod.network.GrapplePayloads.register();
        net.ragnar.ragnarsmagicmod.network.PortalPayloads.register();
        net.ragnar.ragnarsmagicmod.network.BuildingPayloads.register();
        net.ragnar.ragnarsmagicmod.network.InvisibilityPayload.register();
        net.ragnar.ragnarsmagicmod.network.AscendPayload.register();
        net.ragnar.ragnarsmagicmod.network.ReckoningPayload.register();
        net.ragnar.ragnarsmagicmod.util.PortalNetwork.register();
        net.ragnar.ragnarsmagicmod.util.TempEntities.register();
        net.ragnar.ragnarsmagicmod.util.TomeCooldowns.register(); // tome cooldowns survive leaving and dying
        net.ragnar.ragnarsmagicmod.util.SkyDrop.register();
        net.ragnar.ragnarsmagicmod.item.spell.DeadSpell.register();
        net.ragnar.ragnarsmagicmod.item.spell.ControllingSpell.register();
        net.ragnar.ragnarsmagicmod.item.spell.ClonesSpell.register();
        net.ragnar.ragnarsmagicmod.item.spell.IceBeamSpell.register();
        net.ragnar.ragnarsmagicmod.item.spell.ToweringSpell.register();
        net.ragnar.ragnarsmagicmod.item.spell.FairySpell.register();
        net.ragnar.ragnarsmagicmod.item.spell.SprayingSpell.register();
        net.ragnar.ragnarsmagicmod.item.spell.BuildingSpell.register();
        net.ragnar.ragnarsmagicmod.item.spell.InvisibilitySpell.register();
        net.ragnar.ragnarsmagicmod.item.spell.SizeSpell.register();
        net.ragnar.ragnarsmagicmod.item.spell.TrappingSpell.register();
        net.ragnar.ragnarsmagicmod.item.spell.AscendSpell.register();
        net.ragnar.ragnarsmagicmod.item.spell.ReckoningSpell.register();

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
        Spells.register(SpellId.VOID, new net.ragnar.ragnarsmagicmod.item.spell.VoidSpell());
        Spells.register(SpellId.FREEZING, new net.ragnar.ragnarsmagicmod.item.spell.FreezingSpell());
        Spells.register(SpellId.RAINING_ARROWS, new net.ragnar.ragnarsmagicmod.item.spell.RainingArrowsSpell());
        Spells.register(SpellId.RANDOMNESS, new net.ragnar.ragnarsmagicmod.item.spell.RandomnessSpell());
        Spells.register(SpellId.ROCKS, new net.ragnar.ragnarsmagicmod.item.spell.RocksSpell());
        Spells.register(SpellId.SWAP, new net.ragnar.ragnarsmagicmod.item.spell.SwappingSpell());
        Spells.register(SpellId.CLOUDS, new net.ragnar.ragnarsmagicmod.item.spell.CloudSpell());
        Spells.register(SpellId.SMASH, new net.ragnar.ragnarsmagicmod.item.spell.SmashingSpell());
        Spells.register(SpellId.PULL, new net.ragnar.ragnarsmagicmod.item.spell.PullingSpell());
        Spells.register(SpellId.FELLING, new net.ragnar.ragnarsmagicmod.item.spell.FellingSpell());
        Spells.register(SpellId.BREATHING, new net.ragnar.ragnarsmagicmod.item.spell.BreathingSpell());
        Spells.register(SpellId.IGNITION, new net.ragnar.ragnarsmagicmod.item.spell.IgnitionSpell());
        Spells.register(SpellId.DEAD, new net.ragnar.ragnarsmagicmod.item.spell.DeadSpell());
        Spells.register(SpellId.SWORDS, new net.ragnar.ragnarsmagicmod.item.spell.SwordsSpell());
        Spells.register(SpellId.LEVITATION, new net.ragnar.ragnarsmagicmod.item.spell.LevitationSpell());
        Spells.register(SpellId.TELEKINESIS, new net.ragnar.ragnarsmagicmod.item.spell.TelekinesisSpell());
        Spells.register(SpellId.CONTROLLING, new net.ragnar.ragnarsmagicmod.item.spell.ControllingSpell());
        Spells.register(SpellId.QUIVER, new net.ragnar.ragnarsmagicmod.item.spell.QuiverSpell());
        Spells.register(SpellId.GLACIER, new net.ragnar.ragnarsmagicmod.item.spell.GlacierSpell());
        Spells.register(SpellId.HOME, new net.ragnar.ragnarsmagicmod.item.spell.HomeSpell());
        Spells.register(SpellId.STONE_CANNON, new net.ragnar.ragnarsmagicmod.item.spell.StoneCannonSpell());
        Spells.register(SpellId.SLASHING, new net.ragnar.ragnarsmagicmod.item.spell.SlashingSpell());
        Spells.register(SpellId.DEFLECTION, new net.ragnar.ragnarsmagicmod.item.spell.DeflectionSpell());
        Spells.register(SpellId.SHARP_LEAVES, new net.ragnar.ragnarsmagicmod.item.spell.SharpLeavesSpell());
        Spells.register(SpellId.AIR_CUT, new net.ragnar.ragnarsmagicmod.item.spell.AirCutSpell());
        Spells.register(SpellId.ILLUSION, new net.ragnar.ragnarsmagicmod.item.spell.IllusionSpell());
        Spells.register(SpellId.DIMENSION_SPLIT, new net.ragnar.ragnarsmagicmod.item.spell.DimensionSplitSpell());
        Spells.register(SpellId.VINES, new net.ragnar.ragnarsmagicmod.item.spell.VinesSpell());
        Spells.register(SpellId.WINGS, new net.ragnar.ragnarsmagicmod.item.spell.WingsSpell());
        Spells.register(SpellId.WATERWALKING, new net.ragnar.ragnarsmagicmod.item.spell.WaterwalkingSpell());
        Spells.register(SpellId.COBWEBS, new net.ragnar.ragnarsmagicmod.item.spell.CobwebSpell());
        Spells.register(SpellId.SMELTING, new net.ragnar.ragnarsmagicmod.item.spell.SmeltingSpell());
        Spells.register(SpellId.SPEED, new net.ragnar.ragnarsmagicmod.item.spell.SpeedSpell());
        net.ragnar.ragnarsmagicmod.item.spell.SpeedSpell.register(); // passive Speed I
        Spells.register(SpellId.BLOOM, new net.ragnar.ragnarsmagicmod.item.spell.BloomSpell());
        Spells.register(SpellId.TNT, new net.ragnar.ragnarsmagicmod.item.spell.TntSpell());
        Spells.register(SpellId.DUSK_AND_DAWN, new net.ragnar.ragnarsmagicmod.item.spell.DuskAndDawnSpell());
        Spells.register(SpellId.REWIND, new net.ragnar.ragnarsmagicmod.item.spell.RewindSpell());
        Spells.register(SpellId.JUDGEMENT, new net.ragnar.ragnarsmagicmod.item.spell.JudgementSpell());
        Spells.register(SpellId.KINDLING, new net.ragnar.ragnarsmagicmod.item.spell.KindlingSpell());
        Spells.register(SpellId.CLONES, new net.ragnar.ragnarsmagicmod.item.spell.ClonesSpell());
        Spells.register(SpellId.MENDING, new net.ragnar.ragnarsmagicmod.item.spell.MendingSpell());
        Spells.register(SpellId.TOWERING, new net.ragnar.ragnarsmagicmod.item.spell.ToweringSpell());
        Spells.register(SpellId.REACHING, new net.ragnar.ragnarsmagicmod.item.spell.ReachingSpell());
        Spells.register(SpellId.FAIRY, new net.ragnar.ragnarsmagicmod.item.spell.FairySpell());
        Spells.register(SpellId.SPRAYING, new net.ragnar.ragnarsmagicmod.item.spell.SprayingSpell());
        Spells.register(SpellId.SLIPPERINESS, new net.ragnar.ragnarsmagicmod.item.spell.SlipperinessSpell());
        Spells.register(SpellId.PARANOIA, new net.ragnar.ragnarsmagicmod.item.spell.ParanoiaSpell());
        Spells.register(SpellId.SORTING, new net.ragnar.ragnarsmagicmod.item.spell.SortingSpell());
        Spells.register(SpellId.ENDER_CHEST, new net.ragnar.ragnarsmagicmod.item.spell.EnderChestSpell());
        Spells.register(SpellId.STORAGE, new net.ragnar.ragnarsmagicmod.item.spell.StorageSpell());
        Spells.register(SpellId.ENDER_PEARLS, new net.ragnar.ragnarsmagicmod.item.spell.EnderPearlSpell());
        Spells.register(SpellId.RECKONING, new net.ragnar.ragnarsmagicmod.item.spell.ReckoningSpell());
        Spells.register(SpellId.GRAPPLING, new net.ragnar.ragnarsmagicmod.item.spell.GrapplingSpell());
        Spells.register(SpellId.PORTALS, new net.ragnar.ragnarsmagicmod.item.spell.PortalsSpell());
        Spells.register(SpellId.BUILDING, new net.ragnar.ragnarsmagicmod.item.spell.BuildingSpell());
        Spells.register(SpellId.SHRINKING, new net.ragnar.ragnarsmagicmod.item.spell.SizeSpell(net.ragnar.ragnarsmagicmod.item.spell.SizeSpell.Kind.SMALL));
        Spells.register(SpellId.GROWING, new net.ragnar.ragnarsmagicmod.item.spell.SizeSpell(net.ragnar.ragnarsmagicmod.item.spell.SizeSpell.Kind.LARGE));
        Spells.register(SpellId.TRAPPING, new net.ragnar.ragnarsmagicmod.item.spell.TrappingSpell());
        Spells.register(SpellId.ASCEND, new net.ragnar.ragnarsmagicmod.item.spell.AscendSpell());
        net.ragnar.ragnarsmagicmod.item.spell.RewindSpell.register();
    }
}