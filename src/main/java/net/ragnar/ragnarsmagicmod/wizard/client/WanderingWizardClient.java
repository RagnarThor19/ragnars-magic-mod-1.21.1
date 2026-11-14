package net.ragnar.ragnarsmagicmod.wizard.client;

import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.VillagerResemblingModel;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.wizard.WanderingWizard;
import net.ragnar.ragnarsmagicmod.wizard.WanderingWizardEntity;

/**
 * How the Wandering Wizard is drawn: the wandering trader's own model, in a hooded umber robe over a moss-green coat,
 * his tome held in his folded arms. While he can be seen, a few glowing runes - the enchanting table's own glyphs -
 * drift round his head. When he casts he lifts the tome up in front of him, the runes speed up and take on the
 * spell's colour, a rune circle opens on the ground under him and a small sigil spins in front of the tome; when the
 * spell goes off the circles flare out and fade.
 */
public final class WanderingWizardClient {
    private WanderingWizardClient() {}

    public static void init() {
        EntityRendererRegistry.register(WanderingWizard.WANDERING_WIZARD, Renderer::new);
    }

    private static final Identifier TEXTURE = Identifier.of(RagnarsMagicMod.MOD_ID, "textures/entity/wandering_wizard.png");
    private static final RenderLayer CIRCLE = RenderLayer.getEntityTranslucentEmissive(
            Identifier.of(RagnarsMagicMod.MOD_ID, "textures/entity/wandering_wizard_circle.png"));
    private static final RenderLayer SIGIL = RenderLayer.getEntityTranslucentEmissive(
            Identifier.of(RagnarsMagicMod.MOD_ID, "textures/entity/wandering_wizard_sigil.png"));
    /** The 26 glyphs of the enchanting table's alphabet. */
    private static final RenderLayer[] GLYPHS = new RenderLayer[26];

    static {
        for (int i = 0; i < 26; i++) {
            GLYPHS[i] = RenderLayer.getEntityTranslucentEmissive(Identifier.ofVanilla("textures/particle/sga_" + (char) ('a' + i) + ".png"));
        }
    }

    /** The runes' own glow when he isn't casting: a pale, mossy green. */
    private static final int RUNE_COLOR = 0xB8F27C;
    private static final int RUNES = 5;
    private static final int FULL_BRIGHT = LightmapTextureManager.MAX_LIGHT_COORDINATE;
    /** How far his arms lift the tome at the height of a cast, in radians. */
    private static final float ARM_RAISE = 0.75f;

    /** The trader's model, but he raises his folded arms to hold the tome up when he casts. */
    static final class Model extends VillagerResemblingModel<WanderingWizardEntity> {
        private static final float ARMS_PITCH = -0.75f;
        private final ModelPart arms;
        float raise;

        Model(ModelPart root) {
            super(root);
            arms = root.getChild("arms");
        }

        @Override
        public void setAngles(WanderingWizardEntity entity, float limbAngle, float limbDistance, float animationProgress, float headYaw, float headPitch) {
            super.setAngles(entity, limbAngle, limbDistance, animationProgress, headYaw, headPitch);
            arms.pitch = ARMS_PITCH - ARM_RAISE * raise;
            // Holding it up, he looks down at the page
            head.pitch += 0.25f * raise;
        }

        private final ModelPart head = getHead();
    }

    /** The tome in his arms, lifted with them. */
    static final class TomeFeature extends FeatureRenderer<WanderingWizardEntity, Model> {
        private final HeldItemRenderer items;

        TomeFeature(FeatureRendererContext<WanderingWizardEntity, Model> context, HeldItemRenderer items) {
            super(context);
            this.items = items;
        }

        @Override
        public void render(MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, WanderingWizardEntity entity,
                           float limbAngle, float limbDistance, float tickDelta, float animationProgress, float headYaw, float headPitch) {
            // No tome floating in mid-air while he's invisible
            if (entity.isInvisible()) return;
            ItemStack stack = entity.getEquippedStack(EquipmentSlot.MAINHAND);
            if (stack.isEmpty()) return;
            float raise = getContextModel().raise;
            matrices.push();
            if (raise > 0f) {
                // Turn about the shoulders, just as the arms do
                matrices.translate(0f, 3f / 16f, -1f / 16f);
                matrices.multiply(RotationAxis.POSITIVE_X.rotation(-ARM_RAISE * raise));
                matrices.translate(0f, -3f / 16f, 1f / 16f);
            }
            // Where the villagers hold what they carry
            matrices.translate(0f, 0.4f, -0.4f);
            matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(180f));
            items.renderItem(entity, stack, ModelTransformationMode.GROUND, false, matrices, vertexConsumers, light);
            matrices.pop();
        }
    }

    static final class Renderer extends MobEntityRenderer<WanderingWizardEntity, Model> {
        Renderer(EntityRendererFactory.Context ctx) {
            super(ctx, new Model(ctx.getPart(EntityModelLayers.WANDERING_TRADER)), 0.5f);
            addFeature(new TomeFeature(this, ctx.getHeldItemRenderer()));
        }

        @Override
        public Identifier getTexture(WanderingWizardEntity entity) {
            return TEXTURE;
        }

        /** The trader's size. */
        @Override
        protected void scale(WanderingWizardEntity entity, MatrixStack matrices, float amount) {
            matrices.scale(0.9375f, 0.9375f, 0.9375f);
        }

        @Override
        public void render(WanderingWizardEntity entity, float yaw, float tickDelta, MatrixStack matrices,
                           VertexConsumerProvider vertexConsumers, int light) {
            float cast = entity.getCastAnim(tickDelta);
            float flash = entity.getCastFlash(tickDelta);
            boolean invisibility = entity.isCastingInvisibility();
            // He lifts the tome for a spell, not for the invisibility he casts on himself
            model.raise = invisibility ? 0f : ease(cast);
            super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);

            float time = entity.age + tickDelta;
            int color = entity.getCastColor();
            if (!entity.isInvisible()) renderRunes(entity, matrices, vertexConsumers, time, cast, color);
            if (cast > 0f || flash > 0f) {
                renderCircles(entity, matrices, vertexConsumers, tickDelta, time, cast, flash, color, invisibility);
            }
        }

        private static float ease(float t) {
            return t * t * (3f - 2f * t);
        }

        /** The runes drifting round his head, each turning to face you. */
        private void renderRunes(WanderingWizardEntity entity, MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                                 float time, float cast, int castColor) {
            float c = ease(cast);
            int color = lerpColor(RUNE_COLOR, castColor, c);
            // Slow and lazy, then whirling as a spell builds
            float spin = time * (0.035f + 0.16f * c);
            double radius = 0.62 + 0.18 * c;
            double headY = entity.getHeight() * 0.9375 - 0.14;
            // Each wizard has his own runes, and every so often one of them changes
            int seed = (int) (entity.getUuid().getLeastSignificantBits() & 0x7fffffff);
            for (int i = 0; i < RUNES; i++) {
                float a = spin + i * MathHelper.TAU / RUNES;
                float bob = MathHelper.sin(time * 0.07f + i * 1.7f) * 0.07f;
                int letter = Math.floorMod(seed + i * 7 + (int) ((time + i * 41) / 80f), 26);
                float alpha = 0.75f + 0.15f * MathHelper.sin(time * 0.15f + i * 2.3f) + 0.1f * c;
                matrices.push();
                matrices.translate(Math.cos(a) * radius, headY + bob - 0.12 * c, Math.sin(a) * radius);
                matrices.multiply(dispatcher.getRotation());
                float size = 0.15f + 0.03f * c;
                quad(vertexConsumers.getBuffer(GLYPHS[letter]), matrices.peek(), size, color, alpha);
                matrices.pop();
            }
        }

        /** The rune circle on the ground, a smaller one turning against it, and the sigil in front of the tome. */
        private void renderCircles(WanderingWizardEntity entity, MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                                   float tickDelta, float time, float cast, float flash, int color, boolean invisibility) {
            // Opening up through the wind-up; when it goes off it swells and fades
            float open = flash > 0f ? 1f + 0.35f * (1f - flash) : ease(cast);
            float alpha = flash > 0f ? flash : Math.min(1f, cast * 1.6f);
            float bright = flash > 0f ? 1f : 0.75f + 0.25f * cast;

            matrices.push();
            matrices.translate(0, 0.03, 0);
            matrices.push();
            matrices.multiply(RotationAxis.POSITIVE_Y.rotation(time * 0.05f));
            matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(90f));
            quad(vertexConsumers.getBuffer(CIRCLE), matrices.peek(), 1.75f * open, scale(color, bright), 0.85f * alpha);
            matrices.pop();
            matrices.push();
            matrices.translate(0, 0.01, 0);
            matrices.multiply(RotationAxis.POSITIVE_Y.rotation(-time * 0.09f));
            matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(90f));
            quad(vertexConsumers.getBuffer(CIRCLE), matrices.peek(), 0.8f * open, scale(color, bright), 0.7f * alpha);
            matrices.pop();
            matrices.pop();

            if (invisibility) return;
            // The sigil hangs in the air just past the tome, facing the way he faces
            float bodyYaw = MathHelper.lerpAngleDegrees(tickDelta, entity.prevBodyYaw, entity.bodyYaw);
            float r = bodyYaw * MathHelper.RADIANS_PER_DEGREE;
            matrices.push();
            matrices.translate(-MathHelper.sin(r) * 0.95, 1.3, MathHelper.cos(r) * 0.95);
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-bodyYaw));
            matrices.multiply(RotationAxis.POSITIVE_Z.rotation(time * 0.12f));
            quad(vertexConsumers.getBuffer(SIGIL), matrices.peek(), 0.42f * open, scale(color, bright), alpha);
            matrices.pop();
        }

        private static int lerpColor(int from, int to, float t) {
            int r = (int) MathHelper.lerp(t, from >> 16 & 255, to >> 16 & 255);
            int g = (int) MathHelper.lerp(t, from >> 8 & 255, to >> 8 & 255);
            int b = (int) MathHelper.lerp(t, from & 255, to & 255);
            return r << 16 | g << 8 | b;
        }

        private static int scale(int color, float k) {
            return lerpColor(0, color, k);
        }

        /** A square, {@code half} out from the middle each way, in the XY plane of the current matrix. */
        private static void quad(VertexConsumer buffer, MatrixStack.Entry entry, float half, int color, float alpha) {
            int r = color >> 16 & 255, g = color >> 8 & 255, b = color & 255;
            int a = (int) (MathHelper.clamp(alpha, 0f, 1f) * 255);
            if (a <= 3) return;
            vertex(buffer, entry, -half, -half, r, g, b, a, 0f, 1f);
            vertex(buffer, entry, half, -half, r, g, b, a, 1f, 1f);
            vertex(buffer, entry, half, half, r, g, b, a, 1f, 0f);
            vertex(buffer, entry, -half, half, r, g, b, a, 0f, 0f);
        }

        private static void vertex(VertexConsumer buffer, MatrixStack.Entry entry, float x, float y, int r, int g, int b, int a, float u, float v) {
            buffer.vertex(entry, x, y, 0f).color(r, g, b, a).texture(u, v).overlay(OverlayTexture.DEFAULT_UV)
                    .light(FULL_BRIGHT).normal(entry, 0f, 1f, 0f);
        }
    }
}
