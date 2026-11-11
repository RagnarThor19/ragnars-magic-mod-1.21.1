package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.ragnar.ragnarsmagicmod.crows.Crows;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.punching.Punching;
import net.ragnar.ragnarsmagicmod.punching.PunchingSpell;

import java.util.List;

/** Tome of Punching: the numbers, how far the dash goes, and what the punch does to what it hits and what's near. */
public class PunchingGameTests implements FabricGameTest {

    private static ServerPlayerEntity caster(TestContext ctx) {
        for (int x = 0; x <= 8; x++) for (int z = 0; z <= 10; z++) ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(4, 1, 0)));
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f); // facing +z
        p.setHeadYaw(0f);
        return p;
    }

    private static VillagerEntity villager(TestContext ctx, BlockPos rel) {
        VillagerEntity v = ctx.spawnEntity(EntityType.VILLAGER, rel);
        v.setAiDisabled(true);
        return v;
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void anAdvancedTomeWithTheRightNumbers(TestContext ctx) {
        TomeItem t = Punching.TOME_OF_PUNCHING;
        ctx.assertEquals(TomeTier.ADVANCED, t.getTier(), "tier");
        ctx.assertEquals(8, t.getXpCost(), "xp");
        ctx.assertEquals(200, t.getCooldown(), "cooldown (10 seconds)");
        ctx.assertEquals(14f, Punching.PUNCH_DAMAGE, "14 on a direct hit");
        ctx.assertEquals(7.0, Punching.DASH_DISTANCE, "dashes up to 7 blocks");
        ctx.assertTrue(Spells.get(SpellId.PUNCHING) instanceof PunchingSpell, "spell registered");
        ItemGroups.updateDisplayContext(ctx.getWorld().getEnabledFeatures(), true, ctx.getWorld().getRegistryManager());
        List<ItemStack> tools = List.copyOf(Registries.ITEM_GROUP.get(ItemGroups.TOOLS).getDisplayStacks());
        int crows = -1, punching = -1;
        for (int i = 0; i < tools.size(); i++) {
            if (tools.get(i).isOf(Crows.TOME_OF_CROWS)) crows = i;
            if (tools.get(i).isOf(Punching.TOME_OF_PUNCHING)) punching = i;
        }
        ctx.assertTrue(punching == crows + 1, "with the advanced tomes, after the crows (" + crows + ", " + punching + ")");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void dashGoesSevenOrStopsShort(TestContext ctx) {
        ServerPlayerEntity p = caster(ctx);
        Vec3d fwd = new Vec3d(0, 0, 1);
        double open = Punching.dashDistance(ctx.getWorld(), p, fwd);
        ctx.assertTrue(Math.abs(open - 7.0) < 1e-6, "open ground: the full 7, got " + open);

        VillagerEntity v = villager(ctx, new BlockPos(4, 1, 5));
        double toMob = Punching.dashDistance(ctx.getWorld(), p, fwd);
        ctx.assertTrue(toMob > 2.5 && toMob < 4.5, "stops in punching reach of the villager 5 ahead, got " + toMob);
        v.discard();

        for (int x = 0; x <= 8; x++) for (int y = 1; y <= 3; y++) ctx.setBlockState(new BlockPos(x, y, 4), Blocks.STONE);
        double toWall = Punching.dashDistance(ctx.getWorld(), p, fwd);
        ctx.assertTrue(toWall < 3.6, "stops at the wall 4 ahead, got " + toWall);
        p.discard();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void punchHitsHardAndTheShockwaveSpreads(TestContext ctx) {
        ServerPlayerEntity p = caster(ctx);
        VillagerEntity hit = villager(ctx, new BlockPos(4, 1, 1));       // right in front of the fist
        VillagerEntity near = villager(ctx, new BlockPos(7, 1, 2));      // off to the side, in the blast
        VillagerEntity far = villager(ctx, new BlockPos(4, 1, 10));      // well out of it
        float casterHp = p.getHealth();

        Punching.impact(p, new Vec3d(0, 0, 1));
        float dealt = hit.getMaxHealth() - hit.getHealth();
        ctx.assertTrue(dealt >= Punching.PUNCH_DAMAGE - 0.01f || hit.isDead(), "direct hit for 14, got " + dealt);
        ctx.assertTrue(hit.getVelocity().z > 1.5 && hit.getVelocity().y > 0.5, "sent flying, " + hit.getVelocity());
        float splash = near.getMaxHealth() - near.getHealth();
        ctx.assertTrue(splash > 0f && splash < Punching.PUNCH_DAMAGE, "caught in the shockwave for less, got " + splash);
        ctx.assertTrue(near.getVelocity().x > 0.3, "knocked away from the blow, " + near.getVelocity());
        ctx.assertEquals(far.getMaxHealth(), far.getHealth(), "out of reach");
        ctx.assertEquals(casterHp, p.getHealth(), "the caster's fine");
        hit.discard();
        near.discard();
        far.discard();
        p.discard();
        ctx.complete();
    }
}
