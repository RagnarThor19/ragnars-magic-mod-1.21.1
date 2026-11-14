package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.thunderstrike.Thunderstrike;
import net.ragnar.ragnarsmagicmod.thunderstrike.ThunderstrikeSpell;

/** Tome of Thunderstrike: a warning, one giant strike that hits hard at the centre, then chains outward. */
public class ThunderstrikeGameTests implements FabricGameTest {
    private static final int C = 15;

    private static void floor(TestContext ctx) {
        for (int x = 0; x <= 2 * C; x++) for (int z = 0; z <= 2 * C; z++) {
            ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y <= 6; y++) ctx.setBlockState(new BlockPos(x, y, z), Blocks.AIR);
        }
    }

    private static Vec3d ground(TestContext ctx) {
        return Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(C, 1, C)));
    }

    private static <T extends MobEntity> T mob(TestContext ctx, EntityType<T> type, double dx) {
        Vec3d g = ground(ctx);
        T e = type.create(ctx.getWorld());
        e.refreshPositionAndAngles(g.x + dx, g.y, g.z, 0f, 0f);
        e.setAiDisabled(true);
        ctx.getWorld().spawnEntity(e);
        return e;
    }

    private static ServerPlayerEntity caster(TestContext ctx, double dz) {
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        Vec3d g = ground(ctx);
        p.refreshPositionAndAngles(g.x, g.y, g.z + dz, 180f, 60f);
        return p;
    }

    private static float lost(IronGolemEntity g) {
        return g.getMaxHealth() - g.getHealth();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void itsAMasterTomeWithTheNewNumbers(TestContext ctx) {
        TomeItem tome = (TomeItem) ModItems.TOME_LIGHTNING_CASCADE;
        ctx.assertEquals(TomeTier.MASTER, tome.getTier(), "tier");
        ctx.assertEquals(45, tome.getXpCost(), "xp");
        ctx.assertEquals(400, tome.getCooldown(), "20 second cooldown");
        ctx.assertTrue(Spells.get(SpellId.LIGHTNING_CASCADE) instanceof ThunderstrikeSpell, "the new spell");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "thunderstrike_strike", tickLimit = 200)
    public void warnsThenStrikesHardThenChainsOutward(TestContext ctx) {
        floor(ctx);
        IronGolemEntity centre = mob(ctx, EntityType.IRON_GOLEM, 0);
        IronGolemEntity edge = mob(ctx, EntityType.IRON_GOLEM, 4);
        IronGolemEntity first = mob(ctx, EntityType.IRON_GOLEM, -9);    // out of the strike: the first link of a chain
        IronGolemEntity second = mob(ctx, EntityType.IRON_GOLEM, -14);  // ...and the next one on from it
        ServerPlayerEntity p = caster(ctx, 2); // standing right in it: it never hurts its caster

        Thunderstrike.start(ctx.getWorld(), p, ground(ctx));
        ctx.waitAndRun(Thunderstrike.WARN_TICKS - 5, () -> {
            ctx.assertEquals(lost(centre), 0f, "nothing yet: it's still a warning");
            ctx.waitAndRun(30, () -> {
                // Golems take a fire tick or two after the strike, so allow a little over
                ctx.assertTrue(lost(centre) >= Thunderstrike.CENTER_DAMAGE - 0.5f && lost(centre) <= Thunderstrike.CENTER_DAMAGE + 4,
                        "the full strike at the centre: " + lost(centre));
                ctx.assertTrue(lost(edge) > Thunderstrike.EDGE_DAMAGE && lost(edge) < Thunderstrike.CENTER_DAMAGE - 5,
                        "less further out: " + lost(edge));
                ctx.assertTrue(Math.abs(lost(first) - Thunderstrike.ARC_DAMAGE) < 0.5f, "the chain's first link: " + lost(first));
                float next = Thunderstrike.ARC_DAMAGE * Thunderstrike.ARC_DECAY;
                ctx.assertTrue(Math.abs(lost(second) - next) < 0.5f, "and weaker on the next: " + lost(second));
                ctx.assertTrue(centre.isOnFire() || lost(centre) > Thunderstrike.CENTER_DAMAGE, "set alight");
                ctx.assertTrue(p.getHealth() == p.getMaxHealth(), "the caster is never hurt");
                centre.discard();
                edge.discard();
                first.discard();
                second.discard();
                p.discard();
                ctx.complete();
            });
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "thunderstrike_creeper", tickLimit = 200)
    public void aCreeperNearbyComesOutCharged(TestContext ctx) {
        floor(ctx);
        CreeperEntity creeper = mob(ctx, EntityType.CREEPER, 6.5); // just outside the strike, but near enough
        ServerPlayerEntity p = caster(ctx, -10);
        Thunderstrike.start(ctx.getWorld(), p, ground(ctx));
        ctx.waitAndRun(Thunderstrike.WARN_TICKS + 4, () -> {
            ctx.assertTrue(creeper.shouldRenderOverlay(), "charged (its glow is the charge), as real lightning leaves them");
            creeper.discard();
            p.discard();
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "thunderstrike_cast", tickLimit = 100)
    public void castingAtTheGroundCallsTheStorm(TestContext ctx) {
        floor(ctx);
        ServerPlayerEntity p = caster(ctx, -6);
        p.setPitch(40f);
        p.setYaw(0f);
        int before = Thunderstrike.active();
        ctx.assertTrue(Spells.get(SpellId.LIGHTNING_CASCADE).cast(ctx.getWorld(), p, ItemStack.EMPTY), "cast at the floor");
        ctx.assertEquals(Thunderstrike.active(), before + 1, "a storm is gathering");
        p.setPitch(-80f); // at the open sky
        ctx.assertFalse(Spells.get(SpellId.LIGHTNING_CASCADE).cast(ctx.getWorld(), p, ItemStack.EMPTY), "nothing to strike up there");
        p.discard();
        ctx.complete();
    }
}
