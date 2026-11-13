package net.ragnar.ragnarsmagicmod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.ragnar.ragnarsmagicmod.entity.ModEntities;
import net.ragnar.ragnarsmagicmod.entity.client.SteveRenderer;


public class RagnarsMagicModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        net.ragnar.ragnarsmagicmod.entity.client.ModEntityRenderers.register();
        EntityRendererRegistry.register(ModEntities.STEVE, SteveRenderer::new);
        net.ragnar.ragnarsmagicmod.client.SpellSwitcher.init();
        net.ragnar.ragnarsmagicmod.client.SpellHud.init();
        net.ragnar.ragnarsmagicmod.client.PossessionClient.init();
        net.ragnar.ragnarsmagicmod.client.CloudClient.init();
        net.ragnar.ragnarsmagicmod.client.IllusionClient.init();
        net.ragnar.ragnarsmagicmod.client.DimensionSplitClient.init();
        net.ragnar.ragnarsmagicmod.client.WingsClient.init();
        net.ragnar.ragnarsmagicmod.client.TntClient.init();
        net.ragnar.ragnarsmagicmod.client.DayShiftClient.init();
        net.ragnar.ragnarsmagicmod.client.RewindClient.init();
        net.ragnar.ragnarsmagicmod.client.ScreenShake.init();
        net.ragnar.ragnarsmagicmod.client.CameraGlide.init();
        net.ragnar.ragnarsmagicmod.client.CloneClient.init();
        net.ragnar.ragnarsmagicmod.client.BlinkClient.init();
        net.ragnar.ragnarsmagicmod.client.IceBeamClient.init();
        net.ragnar.ragnarsmagicmod.client.ZapClient.init();
        net.ragnar.ragnarsmagicmod.client.FairyClient.init();
        net.ragnar.ragnarsmagicmod.client.DeflectionClient.init();
        net.ragnar.ragnarsmagicmod.client.SprayingClient.init();
        net.ragnar.ragnarsmagicmod.client.SlipperyClient.init();
        net.ragnar.ragnarsmagicmod.client.TestEntityClient.init();
        net.ragnar.ragnarsmagicmod.client.GrappleClient.init();
        net.ragnar.ragnarsmagicmod.client.PortalClient.init();
        net.ragnar.ragnarsmagicmod.client.building.BuildingClient.init();
        net.ragnar.ragnarsmagicmod.terraform.client.TerraformClient.init(); // Tome of Terraforming (after Building: it shares its keys)
        net.ragnar.ragnarsmagicmod.client.InvisibilityClient.init();
        net.ragnar.ragnarsmagicmod.client.AscendClient.init();
        net.ragnar.ragnarsmagicmod.client.ReckoningClient.init();
        net.ragnar.ragnarsmagicmod.client.StaffModels.init();
        net.ragnar.ragnarsmagicmod.knight.client.KnightClient.init(); // Tome of Knight
        net.ragnar.ragnarsmagicmod.jaunting.client.JauntingClient.init(); // Tome of Jaunting
        net.ragnar.ragnarsmagicmod.ricochet.client.RicochetClient.init(); // Tome of Ricochet
        net.ragnar.ragnarsmagicmod.jumping.client.JumpingClient.init(); // Tome of Jumping
        net.ragnar.ragnarsmagicmod.logs.client.LogsClient.init(); // Tome of Logs
        net.ragnar.ragnarsmagicmod.balllightning.client.BallLightningClient.init(); // Tome of Ball Lightning
        net.ragnar.ragnarsmagicmod.impulse.client.ImpulseClient.init(); // Tome of Impulse
        net.ragnar.ragnarsmagicmod.sight.client.SightClient.init(); // Tome of Sight
        net.ragnar.ragnarsmagicmod.shadowhands.client.ShadowHandsClient.init(); // Tome of Unseen Hands
        net.ragnar.ragnarsmagicmod.beaming.client.BeamingClient.init(); // Tome of Beaming
        net.ragnar.ragnarsmagicmod.upsidedown.client.UpsideDownClient.init(); // Tome of Upside Down
        net.ragnar.ragnarsmagicmod.boulders.client.BouldersClient.init(); // Tome of Boulders
        net.ragnar.ragnarsmagicmod.dragon.client.DragonClient.init(); // Tome of the Dragon
        net.ragnar.ragnarsmagicmod.moon.client.MoonClient.init(); // Tome of the Moon
        net.ragnar.ragnarsmagicmod.mightypush.client.MightyPushClient.init(); // Tome of Mighty Pushing
        net.ragnar.ragnarsmagicmod.lightningpath.client.LightningPathClient.init(); // Tome of the Lightning Path
        net.ragnar.ragnarsmagicmod.bubbles.client.BubblesClient.init(); // Tome of Bubbles
        net.ragnar.ragnarsmagicmod.earthquake.client.EarthquakeClient.init(); // Tome of Earthquake
        net.ragnar.ragnarsmagicmod.lunging.client.LungingClient.init(); // Tome of Lunging
        net.ragnar.ragnarsmagicmod.phantom.client.PhantomClient.init(); // Tome of the Phantom
        net.ragnar.ragnarsmagicmod.sleep.client.SleepClient.init(); // Tome of Sleep Darts, Tome of Sleep Potions
        net.ragnar.ragnarsmagicmod.crows.client.CrowsClient.init(); // Tome of Crows
        net.ragnar.ragnarsmagicmod.tornado.client.TornadoClient.init(); // Tome of Tornados
        net.ragnar.ragnarsmagicmod.poisondarts.client.PoisonDartsClient.init(); // Tome of Poison Darts
        net.ragnar.ragnarsmagicmod.basalt.client.BasaltClient.init(); // Tome of Basalt
        net.ragnar.ragnarsmagicmod.lavafish.client.LavaFishClient.init(); // the lava fish
        net.ragnar.ragnarsmagicmod.spinattack.client.SpinAttackClient.init(); // Tome of Spin Attack
        net.ragnar.ragnarsmagicmod.leaping.client.LeapingClient.init(); // Tome of Leaping
        net.ragnar.ragnarsmagicmod.climbing.client.ClimbingClient.init(); // Tome of Climbing
        net.ragnar.ragnarsmagicmod.gliding.client.GlidingClient.init(); // Tome of Gliding
        net.ragnar.ragnarsmagicmod.swimming.client.SwimmingClient.init(); // Tome of Swimming
        net.ragnar.ragnarsmagicmod.groundpound.client.GroundPoundClient.init(); // Tome of Ground Pound
        net.ragnar.ragnarsmagicmod.sidestep.client.SidestepClient.init(); // Tome of Sidestep
        net.ragnar.ragnarsmagicmod.desperation.client.DesperationClient.init(); // Tome of Desperation
        net.ragnar.ragnarsmagicmod.punching.client.PunchingClient.init(); // Tome of Punching
        net.ragnar.ragnarsmagicmod.geysir.client.GeysirClient.init(); // Tome of Geysir
        net.ragnar.ragnarsmagicmod.blackhole.client.BlackHoleClient.init(); // Tome of Black Holes
        net.ragnar.ragnarsmagicmod.astral.client.AstralClient.init(); // Tome of Astral Projection
        net.ragnar.ragnarsmagicmod.gambling.client.GamblingClient.init(); // Tome of Gambling
    }
}
