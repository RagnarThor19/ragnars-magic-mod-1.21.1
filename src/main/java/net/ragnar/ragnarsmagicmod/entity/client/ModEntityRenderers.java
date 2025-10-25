package net.ragnar.ragnarsmagicmod.entity.client;

import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.render.entity.FlyingItemEntityRenderer;
import net.ragnar.ragnarsmagicmod.entity.ModEntities;

public class ModEntityRenderers {
    public static void register() {
        EntityRendererRegistry.register(ModEntities.ICE_SHARD, ctx -> new FlyingItemEntityRenderer<>(ctx));
    }
}
