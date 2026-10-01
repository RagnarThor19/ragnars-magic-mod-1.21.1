package net.ragnar.ragnarsmagicmod.knight.client;

import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.ragnar.ragnarsmagicmod.knight.Knight;

/** Client half of the Tome of Knight (see Knight for how to remove it). */
public final class KnightClient {
    private KnightClient() {}

    public static void init() {
        EntityModelLayerRegistry.registerModelLayer(KnightModel.LAYER, KnightModel::getTexturedModelData);
        EntityRendererRegistry.register(Knight.KNIGHT, KnightRenderer::new);
    }
}
