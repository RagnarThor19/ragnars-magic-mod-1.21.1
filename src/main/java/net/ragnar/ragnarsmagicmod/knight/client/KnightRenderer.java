package net.ragnar.ragnarsmagicmod.knight.client;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.feature.EyesFeatureRenderer;
import net.minecraft.util.Identifier;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.knight.KnightEntity;

public class KnightRenderer extends MobEntityRenderer<KnightEntity, KnightModel> {
    private static final Identifier TEXTURE = Identifier.of(RagnarsMagicMod.MOD_ID, "textures/entity/knight.png");
    // Soul-blue eyes and the runes along the blade, drawn full-bright like an enderman's eyes
    private static final RenderLayer GLOW = RenderLayer.getEyes(Identifier.of(RagnarsMagicMod.MOD_ID, "textures/entity/knight_glow.png"));

    public KnightRenderer(EntityRendererFactory.Context context) {
        super(context, new KnightModel(context.getPart(KnightModel.LAYER)), 1.0f);
        this.addFeature(new EyesFeatureRenderer<>(this) {
            @Override
            public RenderLayer getEyesTexture() {
                return GLOW;
            }
        });
    }

    @Override
    public Identifier getTexture(KnightEntity knight) {
        return TEXTURE;
    }
}
