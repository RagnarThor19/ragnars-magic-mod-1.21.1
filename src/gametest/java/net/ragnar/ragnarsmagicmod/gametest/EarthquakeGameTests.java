package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.ragnar.ragnarsmagicmod.earthquake.Earthquake;
import net.ragnar.ragnarsmagicmod.earthquake.EarthquakeCast;
import net.ragnar.ragnarsmagicmod.earthquake.EarthquakeSpell;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;

/** Tome of Earthquake: charges three seconds, then flings and hurts everything within 25 blocks, even in a cave. */
public class EarthquakeGameTests implements FabricGameTest {

    private static ServerPlayerEntity caster(TestContext ctx, BlockPos rel) {
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(rel));
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
        return p;
    }

    private static IronGolemEntity golem(TestContext ctx, BlockPos rel) {
        ctx.setBlockState(rel.down(), Blocks.STONE);
        IronGolemEntity g = ctx.spawnEntity(EntityType.IRON_GOLEM, rel);
        g.setAiDisabled(true);
        return g;
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void tomeIsAMasterTomeWithTheRightNumbers(TestContext ctx) {
        ctx.assertEquals(TomeTier.MASTER, Earthquake.TOME_OF_EARTHQUAKE.getTier(), "tier");
        ctx.assertEquals(50, Earthquake.TOME_OF_EARTHQUAKE.getXpCost(), "xp");
        ctx.assertEquals(2000, Earthquake.TOME_OF_EARTHQUAKE.getCooldown(), "cooldown (100 seconds)");
        ctx.assertTrue(Spells.get(SpellId.EARTHQUAKE) instanceof EarthquakeSpell, "spell registered");
        ctx.assertEquals(60, EarthquakeCast.CHARGE_TICKS, "a three second charge");
        ctx.assertTrue(EarthquakeCast.RADIUS == 25, "25 block reach");
        ctx.complete();
    }

    /** Its own batch: 25 blocks reaches well into the neighbouring tests. Run under a stone roof, like a cave. */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 60, batchId = "earthquake")
    public void flingsAndHurtsEverythingInReachEvenUnderground(TestContext ctx) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) {
            ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
            ctx.setBlockState(new BlockPos(x, 4, z), Blocks.STONE); // the cave roof
        }
        ServerPlayerEntity p = caster(ctx, new BlockPos(3, 1, 1));
        IronGolemEntity near = golem(ctx, new BlockPos(3, 1, 5));   // 4 blocks out
        IronGolemEntity far = golem(ctx, new BlockPos(3, 1, 22));   // 21 blocks out
        IronGolemEntity beyond = golem(ctx, new BlockPos(3, 1, 30)); // 29 blocks out: out of reach

        ctx.assertTrue(Spells.get(SpellId.EARTHQUAKE).cast(ctx.getWorld(), p, ItemStack.EMPTY), "cast");
        EarthquakeCast cast = EarthquakeCast.of(p);
        ctx.assertTrue(cast != null, "charging");
        ctx.assertFalse(Spells.get(SpellId.EARTHQUAKE).cast(ctx.getWorld(), p, ItemStack.EMPTY), "one at a time");
        Vec3d start = p.getPos();

        ctx.waitAndRun(1, () -> {
            // Run it by hand (the test server doesn't reliably tick everything every tick)
            while (cast.age() < EarthquakeCast.CHARGE_TICKS - 1) {
                p.setVelocity(0.3, 0, 0.3); // trying to walk off
                cast.tick();
                ctx.assertTrue(p.getVelocity().horizontalLengthSquared() < 1e-6, "rooted while it charges");
            }
            ctx.assertTrue(near.getHealth() == near.getMaxHealth(), "nothing hurt while it charges");

            Vec3d nearKick = null, farKick = null;
            for (int i = 0; i < EarthquakeCast.WAVE_TICKS + 3; i++) {
                cast.tick();
                if (nearKick == null && near.getHealth() < near.getMaxHealth()) nearKick = near.getVelocity();
                if (farKick == null && far.getHealth() < far.getMaxHealth()) farKick = far.getVelocity();
            }
            ctx.assertTrue(nearKick != null && farKick != null, "the wave reached both golems");
            ctx.assertTrue(nearKick.y > 0.8, "flung up: " + nearKick);
            ctx.assertTrue(farKick.y > 0.5, "the far one too: " + farKick);
            Vec3d away = near.getPos().subtract(start).multiply(1, 0, 1).normalize();
            ctx.assertTrue(nearKick.multiply(1, 0, 1).dotProduct(away) > 0, "and away from the caster");
            float nearHurt = near.getMaxHealth() - near.getHealth(), farHurt = far.getMaxHealth() - far.getHealth();
            ctx.assertTrue(nearHurt > farHurt, "hardest near the middle: " + nearHurt + " vs " + farHurt);
            ctx.assertTrue(farHurt >= EarthquakeCast.EDGE_DAMAGE - 0.01f, "still hurts at the edge: " + farHurt);
            ctx.assertTrue(beyond.getHealth() == beyond.getMaxHealth(), "out of reach is untouched");
            ctx.assertTrue(p.getHealth() == p.getMaxHealth(), "never hurts the caster");
            ctx.assertTrue(ctx.getBlockState(new BlockPos(3, 4, 5)).isOf(Blocks.STONE)
                    && ctx.getBlockState(new BlockPos(3, 0, 5)).isOf(Blocks.STONE), "changes no blocks");

            while (cast.tick()) { /* the aftershocks, then done */ }
            ctx.assertTrue(EarthquakeCast.of(p) == null, "over");
            p.discard();
            near.discard();
            far.discard();
            beyond.discard();
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "earthquake")
    public void fizzlesIfTheCasterDiesWhileCharging(TestContext ctx) {
        ServerPlayerEntity p = caster(ctx, new BlockPos(3, 1, 3));
        EarthquakeCast cast = EarthquakeCast.begin(p);
        cast.tick();
        p.kill();
        ctx.assertFalse(cast.tick(), "stops");
        ctx.assertTrue(EarthquakeCast.of(p) == null, "gone");
        p.discard();
        ctx.complete();
    }
}
