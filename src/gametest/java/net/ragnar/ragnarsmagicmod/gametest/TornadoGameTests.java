package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.tornado.Tornado;
import net.ragnar.ragnarsmagicmod.tornado.TornadoEntity;
import net.ragnar.ragnarsmagicmod.tornado.TornadoSpell;

import java.util.List;

/** Tome of Tornados: the numbers, and that a tornado really picks a pig up, hurts it, leaves the caster be and dies out. */
public class TornadoGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void tomeIsABeginnerTomeWithTheRightNumbers(TestContext ctx) {
        ctx.assertEquals(TomeTier.BEGINNER, Tornado.TOME_OF_TORNADOS.getTier(), "tier");
        ctx.assertEquals(12, Tornado.TOME_OF_TORNADOS.getXpCost(), "xp");
        ctx.assertEquals(400, Tornado.TOME_OF_TORNADOS.getCooldown(), "cooldown (20 seconds)");
        ctx.assertEquals(7f, TornadoEntity.HEIGHT, "7 blocks tall");
        ctx.assertTrue(Spells.get(SpellId.TORNADO) instanceof TornadoSpell, "spell registered");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void tomeIsInTheToolsCreativeTab(TestContext ctx) {
        net.minecraft.item.ItemGroups.updateDisplayContext(ctx.getWorld().getEnabledFeatures(), true, ctx.getWorld().getRegistryManager());
        var tools = net.minecraft.registry.Registries.ITEM_GROUP.get(net.minecraft.item.ItemGroups.TOOLS);
        ctx.assertTrue(tools.getDisplayStacks().stream().anyMatch(s -> s.isOf(Tornado.TOME_OF_TORNADOS)), "in Tools & Utilities");
        ctx.complete();
    }

    /** A walled 13x13 stone yard (so the tornado can't wander off into other tests), a pig in the middle. */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = TornadoEntity.LIFE + 40)
    public void pickUpAPigHurtsItAndBlowsOut(TestContext ctx) {
        for (int x = 0; x <= 12; x++) {
            for (int z = 0; z <= 12; z++) {
                ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
                if (x == 0 || z == 0 || x == 12 || z == 12) {
                    for (int y = 1; y <= 4; y++) ctx.setBlockState(new BlockPos(x, y, z), Blocks.STONE);
                }
            }
        }
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(6, 1, 2)));
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f); // looking along +z
        float casterHealth = p.getHealth();

        PigEntity pig = ctx.spawnEntity(EntityType.PIG, new BlockPos(6, 1, 6));
        float pigHealth = pig.getHealth();
        double floor = at.y;
        double[] highest = {floor};

        ctx.assertTrue(Spells.get(SpellId.TORNADO).cast(p.getServerWorld(), p, p.getMainHandStack()), "cast");
        Box yard = new Box(ctx.getAbsolutePos(BlockPos.ORIGIN)).stretch(13, 6, 13);
        List<TornadoEntity> spun = p.getServerWorld().getEntitiesByClass(TornadoEntity.class, yard, e -> true);
        ctx.assertTrue(spun.size() == 1, "one tornado");
        TornadoEntity tornado = spun.get(0);
        ctx.assertTrue(tornado.getY() - floor < 0.01 && tornado.getY() - floor > -0.01, "on the ground, not at " + tornado.getY());
        ctx.assertTrue(tornado.getPos().z > at.z + 1.5, "a few steps in front");

        ctx.runAtEveryTick(() -> {
            if (pig.isAlive()) highest[0] = Math.max(highest[0], pig.getY());
        });
        ctx.runAtTick(TornadoEntity.LIFE - 2, () -> {
            ctx.assertTrue(!tornado.isRemoved(), "still going near the end");
            ctx.assertTrue(highest[0] - floor > 1.5, "lifted the pig off the ground (highest " + (highest[0] - floor) + ")");
            ctx.assertTrue(!pig.isAlive() || pig.getHealth() < pigHealth, "hurt the pig");
            ctx.assertTrue(p.getHealth() == casterHealth, "left the caster alone");
        });
        ctx.runAtTick(TornadoEntity.LIFE + 5, () -> {
            ctx.assertTrue(tornado.isRemoved(), "blown itself out");
            ctx.complete();
        });
    }
}
