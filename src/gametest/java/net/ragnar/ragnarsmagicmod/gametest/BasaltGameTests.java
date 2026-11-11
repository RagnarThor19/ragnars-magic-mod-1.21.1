package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.ragnar.ragnarsmagicmod.basalt.Basalt;
import net.ragnar.ragnarsmagicmod.basalt.BasaltEntity;
import net.ragnar.ragnarsmagicmod.basalt.BasaltSpell;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.phantom.Phantom;

import java.util.List;

/** Tome of Basalt: the numbers, the one second gathering, and a 35 damage direct hit that never touches the caster. */
public class BasaltGameTests implements FabricGameTest {

    private static void forceChunks(TestContext ctx) {
        BlockPos a = ctx.getAbsolutePos(new BlockPos(0, 0, 0)), b = ctx.getAbsolutePos(new BlockPos(7, 0, 7));
        for (int cx = Math.min(a.getX(), b.getX()) >> 4; cx <= Math.max(a.getX(), b.getX()) >> 4; cx++) {
            for (int cz = Math.min(a.getZ(), b.getZ()) >> 4; cz <= Math.max(a.getZ(), b.getZ()) >> 4; cz++) {
                ctx.getWorld().setChunkForced(cx, cz, true);
            }
        }
    }

    private static ServerPlayerEntity caster(TestContext ctx, float pitch) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(3, 1, 0)));
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, pitch);
        p.setHeadYaw(0f);
        return p;
    }

    private static BasaltEntity theRock(TestContext ctx, ServerPlayerEntity p) {
        List<BasaltEntity> found = ctx.getWorld().getEntitiesByClass(BasaltEntity.class, new Box(p.getBlockPos()).expand(6), e -> true);
        ctx.assertTrue(found.size() == 1, "one basalt boulder, found " + found.size());
        return found.get(0);
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void tomeIsAMasterTomeWithTheRightNumbers(TestContext ctx) {
        ctx.assertEquals(TomeTier.MASTER, Basalt.TOME_OF_BASALT.getTier(), "tier");
        ctx.assertEquals(30, Basalt.TOME_OF_BASALT.getXpCost(), "xp");
        ctx.assertEquals(500, Basalt.TOME_OF_BASALT.getCooldown(), "cooldown (25 seconds)");
        ctx.assertEquals(35f, BasaltEntity.HIT_DAMAGE, "35 on a direct hit");
        ctx.assertEquals(20, BasaltEntity.GATHER_TICKS, "a second to gather");
        ctx.assertTrue(Spells.get(SpellId.BASALT) instanceof BasaltSpell, "spell registered");
        ctx.assertEquals(Basalt.TOME_OF_BASALT, ModItems.getTomeFor(SpellId.BASALT, TomeTier.MASTER), "lookup");
        ItemGroups.updateDisplayContext(ctx.getWorld().getEnabledFeatures(), true, ctx.getWorld().getRegistryManager());
        List<ItemStack> tools = List.copyOf(Registries.ITEM_GROUP.get(ItemGroups.TOOLS).getDisplayStacks());
        int phantom = -1, basalt = -1;
        for (int i = 0; i < tools.size(); i++) {
            if (tools.get(i).isOf(Phantom.TOME_OF_THE_PHANTOM)) phantom = i;
            if (tools.get(i).isOf(Basalt.TOME_OF_BASALT)) basalt = i;
        }
        ctx.assertTrue(basalt >= 0 && basalt == phantom + 1, "with the master tomes, after the phantom (" + phantom + ", " + basalt + ")");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 40)
    public void gathersForASecondThenFlies(TestContext ctx) {
        forceChunks(ctx);
        ServerPlayerEntity p = caster(ctx, 0f);
        ctx.assertTrue(Spells.get(SpellId.BASALT).cast(ctx.getWorld(), p, ItemStack.EMPTY), "cast");
        BasaltEntity rock = theRock(ctx, p);
        ctx.assertTrue(rock.center().y > p.getEyeY() + 1.5, "gathers high over the caster's head");
        for (int i = 0; i < BasaltEntity.GATHER_TICKS - 1; i++) rock.tick();
        ctx.assertEquals(BasaltEntity.GATHERING, rock.stage(), "still gathering just before the second is up");
        rock.tick();
        rock.tick();
        ctx.assertEquals(BasaltEntity.FLYING, rock.stage(), "thrown once it's gathered");
        ctx.assertTrue(rock.getVelocity().z > 1.0, "hurled where they look, " + rock.getVelocity());
        rock.discard();
        p.discard();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 60)
    public void directHitDoes35AndSparesTheCaster(TestContext ctx) {
        forceChunks(ctx);
        IronGolemEntity golem = ctx.spawnEntity(EntityType.IRON_GOLEM, new BlockPos(3, 1, 4));
        golem.setAiDisabled(true);
        ServerPlayerEntity p = caster(ctx, 25f); // looking down at the ground just behind the golem
        float casterHealth = p.getHealth();
        ctx.assertTrue(Spells.get(SpellId.BASALT).cast(ctx.getWorld(), p, ItemStack.EMPTY), "cast");
        BasaltEntity rock = theRock(ctx, p);

        ctx.waitAndRun(1, () -> {
            float before = golem.getHealth();
            float firstHit = 0f;
            boolean burning = false;
            // Only up to the first hit: left to roll on, something this big wanders into the neighbouring tests
            for (int i = 0; i < 80 && !rock.isRemoved() && firstHit == 0f; i++) {
                rock.tick();
                if (golem.getHealth() < before) {
                    firstHit = before - golem.getHealth();
                    burning = golem.isOnFire();
                }
            }
            rock.discard();
            ctx.assertTrue(firstHit >= BasaltEntity.HIT_DAMAGE - 0.01f, "first hit was the direct one, " + firstHit);
            ctx.assertTrue(burning, "set alight by the hit");
            ctx.assertTrue(p.getHealth() >= casterHealth && !p.isOnFire(), "caster untouched");
            golem.discard();
            p.discard();
            ctx.complete();
        });
    }
}
