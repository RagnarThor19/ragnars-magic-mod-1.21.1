package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.ragnar.ragnarsmagicmod.util.PortalNetwork;

import java.util.UUID;

/** Tome of Portals: two portals on the floor work both ways. */
public class PortalGameTests implements FabricGameTest {

    /**
     * Keeps the test's chunks ticking whatever its neighbours do. Never un-forced: forcing is on/off per chunk, not
     * counted, so un-forcing here could stop a neighbouring test that shares a chunk.
     */
    private static void forceChunks(TestContext ctx, boolean force) {
        BlockPos a = ctx.getAbsolutePos(new BlockPos(0, 0, 0)), b = ctx.getAbsolutePos(new BlockPos(7, 0, 7));
        for (int cx = Math.min(a.getX(), b.getX()) >> 4; cx <= Math.max(a.getX(), b.getX()) >> 4; cx++) {
            for (int cz = Math.min(a.getZ(), b.getZ()) >> 4; cz <= Math.max(a.getZ(), b.getZ()) >> 4; cz++) {
                ctx.getWorld().setChunkForced(cx, cz, force);
            }
        }
    }

    /** A floor portal whose long side runs along z, centred on {@code x, z} of the test area. */
    private static PortalNetwork.Portal floorPortal(TestContext ctx, double x, double z) {
        Vec3d c = Vec3d.of(ctx.getAbsolutePos(new BlockPos(0, 1, 0))).add(x, 0, z);
        return new PortalNetwork.Portal(ctx.getWorld().getRegistryKey(), c, Direction.UP, Direction.SOUTH);
    }

    private static void moveTo(TestContext ctx, ArmorStandEntity stand, double x, double z) {
        Vec3d at = Vec3d.of(ctx.getAbsolutePos(new BlockPos(0, 1, 0))).add(x, 0, z);
        stand.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
        stand.setVelocity(Vec3d.ZERO);
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 120)
    public void floorToFloorWorksBothWays(TestContext ctx) {
        forceChunks(ctx, true);
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
        MinecraftServer server = ctx.getWorld().getServer();
        UUID owner = UUID.randomUUID();
        PortalNetwork.Portal a = floorPortal(ctx, 1.5, 3.0), b = floorPortal(ctx, 5.5, 3.0);
        PortalNetwork.open(server, owner, a);
        PortalNetwork.open(server, owner, b);

        ArmorStandEntity stand = ctx.spawnEntity(EntityType.ARMOR_STAND, new BlockPos(1, 1, 3));
        moveTo(ctx, stand, 1.5, 3.0); // standing on A

        ctx.waitAndRun(5, () -> ctx.assertTrue(Math.abs(stand.getX() - b.center().x) < 0.6, "went A -> B, x " + stand.getX()));
        ctx.waitAndRun(35, () -> {
            ctx.assertTrue(Math.abs(stand.getX() - b.center().x) < 0.6, "landing back on B doesn't bounce it back, x " + stand.getX());
            moveTo(ctx, stand, 5.5, 5.0); // just off the end of B, still right next to it
        });
        ctx.waitAndRun(38, () -> moveTo(ctx, stand, 5.5, 3.0)); // and back on
        ctx.waitAndRun(44, () -> {
            ctx.assertTrue(Math.abs(stand.getX() - a.center().x) < 0.6, "stepping back onto B goes B -> A, x " + stand.getX());
            PortalNetwork.close(server, owner);
            stand.discard();
            ctx.complete();
        });
    }
}
