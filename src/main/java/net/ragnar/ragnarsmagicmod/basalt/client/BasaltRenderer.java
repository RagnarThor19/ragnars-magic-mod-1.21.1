package net.ragnar.ragnarsmagicmod.basalt.client;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.PillarBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.basalt.BasaltEntity;
import org.joml.Quaternionf;

/**
 * Draws the basalt boulder: a huge craggy mass of basalt, blackstone and obsidian with veins of glowing magma bursting
 * out of it, lit by its own heat. While it gathers, the chunks of ground flying in are drawn too, and the rock
 * builds up around a white-hot core one piece at a time.
 */
public class BasaltRenderer extends EntityRenderer<BasaltEntity> {

    /** One cube of the rock: which block, where its centre sits (radius 1 units), how big, how it's turned (degrees). */
    private record Part(BlockState state, float x, float y, float z, float size, float rx, float ry, float rz) {
        boolean hot() {
            return state.isOf(Blocks.MAGMA_BLOCK);
        }
    }

    private static BlockState basalt(Direction.Axis axis) {
        return Blocks.BASALT.getDefaultState().with(PillarBlock.AXIS, axis);
    }

    /** In the order they build up while it gathers: the molten core first, the crust last. */
    private static final Part[] PARTS = {
            new Part(Blocks.MAGMA_BLOCK.getDefaultState(), 0f, 0f, 0f, 1.05f, 10f, 20f, 5f),
            new Part(basalt(Direction.Axis.Y), 0.05f, 0f, 0f, 1.28f, 0f, 0f, 0f),
            new Part(Blocks.BLACKSTONE.getDefaultState(), 0f, 0.05f, 0.05f, 1.22f, 45f, 0f, 35f),
            new Part(basalt(Direction.Axis.X), -0.1f, -0.05f, 0.1f, 1.14f, 20f, 45f, 0f),
            new Part(Blocks.MAGMA_BLOCK.getDefaultState(), 0.66f, -0.1f, -0.3f, 0.5f, 70f, 40f, 20f),
            new Part(Blocks.SMOOTH_BASALT.getDefaultState(), 0.32f, 0.36f, 0.15f, 0.84f, 30f, 15f, 60f),
            new Part(Blocks.BLACKSTONE.getDefaultState(), -0.38f, 0.24f, -0.3f, 0.8f, 10f, 60f, 25f),
            new Part(Blocks.MAGMA_BLOCK.getDefaultState(), -0.55f, -0.4f, 0.42f, 0.48f, 15f, 20f, 70f),
            new Part(Blocks.SMOOTH_BASALT.getDefaultState(), 0.22f, -0.43f, -0.26f, 0.74f, 55f, 30f, 10f),
            new Part(basalt(Direction.Axis.Z), -0.05f, 0.5f, -0.05f, 0.66f, 35f, 80f, 15f),
            new Part(Blocks.MAGMA_BLOCK.getDefaultState(), 0.12f, 0.3f, 0.7f, 0.44f, 40f, 10f, 30f),
            new Part(Blocks.OBSIDIAN.getDefaultState(), 0.42f, 0.32f, -0.42f, 0.5f, 5f, 35f, 50f),
            new Part(Blocks.GILDED_BLACKSTONE.getDefaultState(), -0.22f, -0.52f, -0.12f, 0.5f, 25f, 65f, 40f),
            new Part(Blocks.MAGMA_BLOCK.getDefaultState(), -0.7f, 0.15f, 0.08f, 0.42f, 60f, 15f, 35f),
            new Part(Blocks.POLISHED_BLACKSTONE.getDefaultState(), 0.3f, -0.18f, 0.48f, 0.5f, 20f, 50f, 65f),
            new Part(Blocks.MAGMA_BLOCK.getDefaultState(), 0.1f, 0.72f, -0.35f, 0.4f, 30f, 45f, 10f),
            new Part(Blocks.MAGMA_BLOCK.getDefaultState(), 0.05f, -0.72f, 0.3f, 0.4f, 50f, 25f, 60f),
    };

    public BasaltRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
        shadowRadius = 2.0f;
        shadowOpacity = 0.9f;
    }

    @Override
    public void render(BasaltEntity rock, float yaw, float tickDelta, MatrixStack ms, VertexConsumerProvider buffers, int light) {
        BlockRenderManager blocks = MinecraftClient.getInstance().getBlockRenderManager();
        float time = rock.age + tickDelta;
        float g = rock.gather(tickDelta);
        float heat = rock.heat();
        boolean gathering = rock.stage() == BasaltEntity.GATHERING;
        // Molten parts glow by themselves; how bright depends on how hot it still is
        int hotLight = LightmapTextureManager.pack(Math.max(LightmapTextureManager.getBlockLightCoordinates(light),
                Math.round(7 + 8 * heat)), LightmapTextureManager.getSkyLightCoordinates(light));
        // The crust is lit a little by its own heat, so it never vanishes into the dark
        int crustLight = LightmapTextureManager.pack(Math.max(LightmapTextureManager.getBlockLightCoordinates(light),
                Math.round(3 + 5 * heat)), LightmapTextureManager.getSkyLightCoordinates(light));

        ms.push();
        ms.translate(0f, rock.getHeight() / 2f, 0f);

        // The chunks of ground flying in
        if (gathering) {
            for (BasaltEntity.Shard s : rock.shards) {
                float t = s.progress(time);
                Vec3d o = s.offset(time);
                float size = s.size * (1f - 0.55f * t * t) * MathHelper.clamp(t * 6f + 0.3f, 0f, 1f);
                float spin = s.angle(time);
                ms.push();
                ms.translate(o.x, o.y, o.z);
                ms.multiply(new Quaternionf().rotationXYZ(
                        (float) Math.toRadians(s.spinX * spin), (float) Math.toRadians(s.spinY * spin), (float) Math.toRadians(s.spinZ * spin)));
                ms.scale(size, size, size);
                ms.translate(-0.5f, -0.5f, -0.5f);
                blocks.renderBlockAsEntity(s.state, ms, buffers, s.hot ? hotLight : light, OverlayTexture.DEFAULT_UV);
                ms.pop();
            }
        }

        // The rock, building up while it gathers: the core swells first, then the pieces slam on round it
        float whole = MathHelper.lerp(easeOut(g), 0.45f, 1f);
        float s = BasaltEntity.RADIUS * whole;
        Quaternionf turn = rock.tumble.get(tickDelta);
        ms.push();
        ms.multiply(turn);
        ms.scale(s, s, s);
        for (int i = 0; i < PARTS.length; i++) {
            Part p = PARTS[i];
            float pop = 1f;
            if (gathering) {
                float at = i == 0 ? 0f : 0.12f + 0.78f * (i - 1) / (PARTS.length - 2);
                float k = (g - at) / 0.12f;
                if (k <= 0f) continue;
                pop = backOut(MathHelper.clamp(k, 0f, 1f));
            }
            ms.push();
            ms.translate(p.x(), p.y(), p.z());
            ms.multiply(new Quaternionf().rotationXYZ(
                    (float) Math.toRadians(p.rx()), (float) Math.toRadians(p.ry()), (float) Math.toRadians(p.rz())));
            float size = p.size() * pop;
            ms.scale(size, size, size);
            ms.translate(-0.5f, -0.5f, -0.5f);
            blocks.renderBlockAsEntity(p.state(), ms, buffers, p.hot() ? hotLight : crustLight, OverlayTexture.DEFAULT_UV);
            ms.pop();
        }
        ms.pop();

        ms.pop();
        super.render(rock, yaw, tickDelta, ms, buffers, light);
    }

    private static float easeOut(float t) {
        return 1f - (1f - t) * (1f - t);
    }

    /** Overshoots a touch and settles, so each piece lands on with a thump. */
    private static float backOut(float t) {
        float back = 1.8f, u = t - 1f;
        return 1f + (back + 1f) * u * u * u + back * u * u;
    }

    @Override
    public Identifier getTexture(BasaltEntity rock) {
        return PlayerScreenHandler.BLOCK_ATLAS_TEXTURE;
    }
}
