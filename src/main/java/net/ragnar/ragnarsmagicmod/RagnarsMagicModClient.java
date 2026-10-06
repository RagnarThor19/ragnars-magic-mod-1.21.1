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
        net.ragnar.ragnarsmagicmod.client.SprayingClient.init();
        net.ragnar.ragnarsmagicmod.client.SlipperyClient.init();
        net.ragnar.ragnarsmagicmod.client.TestEntityClient.init();
        net.ragnar.ragnarsmagicmod.client.GrappleClient.init();
        net.ragnar.ragnarsmagicmod.client.PortalClient.init();
        net.ragnar.ragnarsmagicmod.client.building.BuildingClient.init();
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
    }
}
