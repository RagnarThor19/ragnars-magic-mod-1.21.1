package net.ragnar.ragnarsmagicmod.boulders.client;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Quaternionf;

/**
 * A rough 3D lump of stone built out of real block models: a few cubes of different stones, each turned a different
 * way and pushed off centre, so their corners stick out through each other into a craggy, roundish rock. Every shape
 * here is about 2 across (radius 1); scale it to size.
 */
public final class RockModel {
    private RockModel() {}

    /** One cube of the lump: which block, where its centre sits, how big it is, and how it's turned (degrees). */
    public record Part(BlockState state, float x, float y, float z, float size, float rx, float ry, float rz) {
        Part(net.minecraft.block.Block block, float x, float y, float z, float size, float rx, float ry, float rz) {
            this(block.getDefaultState(), x, y, z, size, rx, ry, rz);
        }
    }

    /** The Tome of Rocks' rock: plain grey stone. */
    public static final Part[] ROCK = {
            new Part(Blocks.STONE, 0f, 0f, 0f, 1.25f, 0f, 0f, 0f),
            new Part(Blocks.COBBLESTONE, 0.05f, 0f, 0.05f, 1.15f, 40f, 25f, 30f),
            new Part(Blocks.ANDESITE, 0.32f, 0.25f, -0.1f, 0.8f, 20f, 50f, 10f),
            new Part(Blocks.COBBLESTONE, -0.3f, -0.22f, 0.2f, 0.72f, 60f, 10f, 40f),
            new Part(Blocks.STONE, -0.12f, 0.3f, 0.32f, 0.6f, 15f, 70f, 25f),
    };

    /** The Tome of Boulders' boulder: a big weathered mass of stone with moss and tuff in its cracks. */
    public static final Part[] BOULDER = {
            new Part(Blocks.COBBLESTONE, 0f, 0f, 0f, 1.3f, 0f, 0f, 0f),
            new Part(Blocks.STONE, 0.05f, 0.05f, 0f, 1.2f, 45f, 0f, 35f),
            new Part(Blocks.ANDESITE, -0.1f, -0.05f, 0.1f, 1.1f, 20f, 45f, 0f),
            new Part(Blocks.MOSSY_COBBLESTONE, 0.3f, 0.35f, 0.15f, 0.82f, 30f, 15f, 60f),
            new Part(Blocks.COBBLESTONE, -0.36f, 0.22f, -0.3f, 0.78f, 10f, 60f, 25f),
            new Part(Blocks.TUFF, 0.22f, -0.4f, -0.26f, 0.72f, 55f, 30f, 10f),
            new Part(Blocks.STONE, -0.26f, -0.36f, 0.36f, 0.68f, 15f, 20f, 70f),
            new Part(Blocks.MOSSY_COBBLESTONE, -0.05f, 0.45f, -0.05f, 0.62f, 35f, 80f, 15f),
            new Part(Blocks.ANDESITE, 0.42f, -0.08f, -0.38f, 0.6f, 70f, 40f, 20f),
    };

    /** Draws {@code parts} centred on the current origin. */
    public static void render(Part[] parts, MatrixStack ms, VertexConsumerProvider buffers, int light) {
        BlockRenderManager blocks = MinecraftClient.getInstance().getBlockRenderManager();
        for (Part p : parts) {
            ms.push();
            ms.translate(p.x(), p.y(), p.z());
            ms.multiply(new Quaternionf().rotationXYZ(
                    (float) Math.toRadians(p.rx()), (float) Math.toRadians(p.ry()), (float) Math.toRadians(p.rz())));
            ms.scale(p.size(), p.size(), p.size());
            ms.translate(-0.5f, -0.5f, -0.5f);
            blocks.renderBlockAsEntity(p.state(), ms, buffers, light, OverlayTexture.DEFAULT_UV);
            ms.pop();
        }
    }
}
