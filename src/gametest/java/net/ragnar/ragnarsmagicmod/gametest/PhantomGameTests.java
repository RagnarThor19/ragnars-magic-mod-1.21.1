package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.phantom.Phantom;
import net.ragnar.ragnarsmagicmod.phantom.PhantomSpell;

/** Tome of the Phantom: becoming a spectre and back, what can't hurt it, walls, and the cooldown. */
public class PhantomGameTests implements FabricGameTest {

    /** A survival player holding a staff with the tome on it. */
    private static ServerPlayerEntity caster(TestContext ctx, BlockPos rel) {
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(rel));
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
        p.getInventory().clear();
        ItemStack staff = new ItemStack(ModItems.NETHERITE_STAFF);
        ((StaffItem) ModItems.NETHERITE_STAFF).insertTome(staff, Phantom.TOME_OF_THE_PHANTOM);
        p.getInventory().setStack(0, staff);
        p.getInventory().selectedSlot = 0;
        p.addExperience(1000);
        return p;
    }

    private static boolean cast(ServerPlayerEntity p) {
        Spell spell = Spells.get(SpellId.PHANTOM);
        return spell.cast(p.getServerWorld(), p, p.getMainHandStack());
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void tomeIsAMasterTomeWithTheRightNumbers(TestContext ctx) {
        ctx.assertEquals(TomeTier.MASTER, Phantom.TOME_OF_THE_PHANTOM.getTier(), "tier");
        ctx.assertEquals(30, Phantom.TOME_OF_THE_PHANTOM.getXpCost(), "xp");
        ctx.assertEquals(2400, Phantom.TOME_OF_THE_PHANTOM.getCooldown(), "cooldown (120 seconds)");
        ctx.assertEquals(160, Phantom.DURATION_TICKS, "lasts 8 seconds");
        ctx.assertTrue(Spells.get(SpellId.PHANTOM) instanceof PhantomSpell, "spell registered");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void tomeIsInTheToolsCreativeTab(TestContext ctx) {
        net.minecraft.item.ItemGroups.updateDisplayContext(ctx.getWorld().getEnabledFeatures(), true, ctx.getWorld().getRegistryManager());
        var tools = net.minecraft.registry.Registries.ITEM_GROUP.get(net.minecraft.item.ItemGroups.TOOLS);
        ctx.assertTrue(tools.getDisplayStacks().stream().anyMatch(s -> s.isOf(Phantom.TOME_OF_THE_PHANTOM)), "in Tools & Utilities");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void castAgainTurnsBackAndStartsTheCooldown(TestContext ctx) {
        ServerPlayerEntity p = caster(ctx, new BlockPos(2, 1, 2));
        Spell spell = Spells.get(SpellId.PHANTOM);
        ctx.assertEquals(30, spell.xpCost(p, 30), "costs the tome's XP to become a spectre");
        ctx.assertTrue(cast(p), "cast");
        ctx.assertTrue(Phantom.isPhantom(p), "a spectre now");
        ctx.assertTrue(p.hasNoGravity(), "floats");
        ctx.assertFalse(p.getItemCooldownManager().isCoolingDown(Phantom.TOME_OF_THE_PHANTOM), "no cooldown while a spectre");
        ctx.assertTrue(spell.ignoresCooldown(p), "turning back is allowed any time");
        ctx.assertEquals(0, spell.xpCost(p, 30), "and free");

        ctx.assertTrue(cast(p), "cast again");
        ctx.assertFalse(Phantom.isPhantom(p), "solid again");
        ctx.assertFalse(p.hasNoGravity(), "falls again");
        ctx.assertTrue(p.getItemCooldownManager().isCoolingDown(Phantom.TOME_OF_THE_PHANTOM), "cooldown starts now");
        p.discard();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void nothingHurtsASpectreAndItTouchesNothing(TestContext ctx) {
        ServerPlayerEntity p = caster(ctx, new BlockPos(2, 1, 2));
        cast(p);
        // (Asked of the damage event directly: a freshly joined player shrugs off damage for a few seconds anyway)
        var sources = p.getServerWorld().getDamageSources();
        var allow = ServerLivingEntityEvents.ALLOW_DAMAGE.invoker();
        ctx.assertFalse(allow.allowDamage(p, sources.generic(), 6f), "generic damage");
        ctx.assertFalse(allow.allowDamage(p, sources.inWall(), 1f), "suffocation");
        ctx.assertFalse(allow.allowDamage(p, sources.lava(), 4f), "lava");
        ctx.assertFalse(allow.allowDamage(p, sources.fall(), 10f), "falling");
        ctx.assertFalse(allow.allowDamage(p, sources.playerAttack(p), 10f), "other players");
        ctx.assertTrue(allow.allowDamage(p, sources.outOfWorld(), 4f), "but the void still works");

        PigEntity pig = ctx.spawnEntity(EntityType.PIG, new BlockPos(3, 1, 2));
        ctx.assertEquals(net.minecraft.util.ActionResult.FAIL, net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.invoker()
                .interact(p, p.getWorld(), net.minecraft.util.Hand.MAIN_HAND, pig, null), "can't hit things");

        cast(p);
        ctx.assertTrue(allow.allowDamage(p, sources.generic(), 1f), "hurt again once solid");
        p.discard();
        pig.discard();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void passesThroughWallsOnlyWhileASpectre(TestContext ctx) {
        ServerPlayerEntity p = caster(ctx, new BlockPos(2, 1, 2));
        cast(p);
        p.playerTick();
        ctx.assertTrue(p.noClip, "drifts through blocks");
        cast(p);
        p.playerTick();
        ctx.assertFalse(p.noClip, "solid again");
        p.discard();
        ctx.complete();
    }

    /** Running out of time inside a wall: solid right there, no rescue, and the wall can hurt you again. */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 400)
    public void timeRunsOutInsideAWall(TestContext ctx) {
        BlockPos rel = new BlockPos(2, 1, 2);
        ServerPlayerEntity p = caster(ctx, rel);
        cast(p);
        ctx.setBlockState(rel, Blocks.STONE);
        ctx.setBlockState(rel.up(), Blocks.STONE);
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(rel));
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
        p.setVelocity(Vec3d.ZERO);
        ctx.assertTrue(Phantom.insideBlocks(p), "inside the stone");

        ctx.waitAndRun(Phantom.DURATION_TICKS + 5, () -> {
            ctx.assertFalse(Phantom.isPhantom(p), "the time ran out");
            ctx.assertTrue(p.getPos().squaredDistanceTo(at) < 0.01, "still right where it ended, inside the wall");
            ctx.assertTrue(Phantom.insideBlocks(p), "still inside the stone");
            ctx.assertTrue(p.getItemCooldownManager().isCoolingDown(Phantom.TOME_OF_THE_PHANTOM), "cooling down");
            ctx.assertTrue(p.damage(p.getServerWorld().getDamageSources().inWall(), 1f), "and the wall suffocates you");
            p.discard();
            ctx.complete();
        });
    }
}
