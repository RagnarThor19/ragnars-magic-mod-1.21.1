package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.HuskEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.ragnar.ragnarsmagicmod.geysir.Geysir;
import net.ragnar.ragnarsmagicmod.geysir.GeysirEntity;
import net.ragnar.ragnarsmagicmod.geysir.GeysirSpell;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;

import java.util.List;

/** Tome of Geysir: a geysir under every creature in reach, and how hard each throws what's over it. */
public class GeysirGameTests implements FabricGameTest {

    private static void floor(TestContext ctx) {
        for (BlockPos p : BlockPos.iterate(0, 0, 0, 7, 0, 7)) ctx.setBlockState(p, net.minecraft.block.Blocks.STONE);
    }

    private static HuskEntity husk(TestContext ctx, double x, double z) {
        HuskEntity h = ctx.spawnEntity(EntityType.HUSK, new Vec3d(x, 1, z));
        h.setAiDisabled(true);
        return h;
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "geysir_numbers")
    public void tomeIsAnAdvancedTomeWithTheRightNumbers(TestContext ctx) {
        ctx.assertEquals(TomeTier.ADVANCED, Geysir.TOME_OF_GEYSIR.getTier(), "advanced");
        ctx.assertEquals(10.0, GeysirSpell.RADIUS, "10 block radius");
        ctx.assertEquals(20, GeysirEntity.WARN, "a second's warning");
        double direct = GeysirEntity.riseFrom(GeysirEntity.velocityFor(GeysirEntity.DIRECT_HEIGHT));
        ctx.assertTrue(Math.abs(direct - 15) < 0.1, "dead centre throws 15 blocks up: " + direct);
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "geysir_spots")
    public void aGeysirUnderEveryCreatureInReachButNotYourPets(TestContext ctx) {
        floor(ctx);
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        Vec3d at = ctx.getAbsolute(new Vec3d(1.5, 1, 1.5));
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
        HuskEntity a = husk(ctx, 4.5, 1.5), b = husk(ctx, 1.5, 5.5), c = husk(ctx, 6.5, 6.5);
        WolfEntity pet = ctx.spawnEntity(EntityType.WOLF, new Vec3d(3.5, 1, 3.5));
        pet.setOwner(p);
        pet.setTamed(true, true);

        List<Vec3d> spots = GeysirSpell.spots(ctx.getWorld(), p);
        ctx.assertEquals(3, spots.size(), "one under each husk, none under the pet");
        for (Vec3d s : spots) ctx.assertTrue(Math.abs(s.y - (at.y)) < 0.01, "on the ground: " + s);
        for (var e : List.of(a, b, c, pet, p)) e.discard();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "geysir_launch")
    public void deadCentreFliesHighOffCentreLowerAndSideways(TestContext ctx) {
        floor(ctx);
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        Vec3d centre = ctx.getAbsolute(new Vec3d(3.5, 1, 3.5));
        HuskEntity direct = husk(ctx, 3.5, 3.5);
        HuskEntity edge = husk(ctx, 5.1, 3.5);   // 1.6 east: it moved a little in the warning
        HuskEntity clear = husk(ctx, 6.9, 3.5);  // 3.4 east: got away
        p.refreshPositionAndAngles(centre.x - 1.0, centre.y, centre.z, 0f, 0f); // the caster, standing in it

        GeysirEntity g = GeysirEntity.erupt(ctx.getWorld(), centre, p, false);
        g.launch(ctx.getWorld());
        double full = GeysirEntity.velocityFor(GeysirEntity.DIRECT_HEIGHT);
        ctx.assertTrue(Math.abs(direct.getVelocity().y - full) < 1e-3, "dead centre: straight up, full height: " + direct.getVelocity());
        ctx.assertTrue(Math.abs(direct.getVelocity().x) < 1e-3 && Math.abs(direct.getVelocity().z) < 1e-3, "and not sideways");
        ctx.assertTrue(edge.getVelocity().y > 0.4 && edge.getVelocity().y < full * 0.8, "off centre: lower: " + edge.getVelocity());
        ctx.assertTrue(edge.getVelocity().x > 0.1, "and thrown away from the middle: " + edge.getVelocity());
        ctx.assertTrue(clear.getVelocity().y <= 0.0, "out of reach: untouched: " + clear.getVelocity());
        ctx.assertTrue(p.getVelocity().y <= 0.0, "never the caster");
        ctx.assertTrue(direct.getHealth() < direct.getMaxHealth() && direct.getHealth() > direct.getMaxHealth() - 5, "a little damage");

        // And for real: a second's warning, then up it goes (a cow with its wits about it: mobs with no AI don't move at all)
        var later = ctx.spawnEntity(EntityType.COW, new Vec3d(1.5, 1, 6.5));
        Vec3d spot = ctx.getAbsolute(new Vec3d(1.5, 1, 6.5));
        GeysirEntity.erupt(ctx.getWorld(), spot, p, false);
        double startY = later.getY();
        ctx.waitAndRun(GeysirEntity.WARN - 2, () -> {
            ctx.assertTrue(later.getY() - startY < 0.01, "nothing during the warning");
            ctx.waitAndRun(10, () -> {
                ctx.assertTrue(later.getY() - startY > 5, "then thrown high: " + (later.getY() - startY));
                for (var e : List.of(direct, edge, clear, later, p, g)) e.discard();
                ctx.complete();
            });
        });
    }
}
