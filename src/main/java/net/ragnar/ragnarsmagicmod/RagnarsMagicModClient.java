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
    }
}
