package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.mightypush.MightyPush;
import net.ragnar.ragnarsmagicmod.mightypush.MightyPushCast;
import net.ragnar.ragnarsmagicmod.mightypush.MightyPushSpell;

/** Tome of Mighty Pushing: carries what it can, crushes what it can't, and leaves an airborne caster safe. */
public class MightyPushGameTests implements FabricGameTest {

    private static ServerPlayerEntity caster(TestContext ctx, BlockPos rel) {
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(rel));
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
        return p;
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void tomeIsAMasterTomeWithTheRightNumbers(TestContext ctx) {
        ctx.assertEquals(TomeTier.MASTER, MightyPush.TOME_OF_MIGHTY_PUSHING.getTier(), "tier");
        ctx.assertEquals(50, MightyPush.TOME_OF_MIGHTY_PUSHING.getXpCost(), "xp");
        ctx.assertEquals(2000, MightyPush.TOME_OF_MIGHTY_PUSHING.getCooldown(), "cooldown (100 seconds)");
        ctx.assertTrue(Spells.get(SpellId.MIGHTY_PUSH) instanceof MightyPushSpell, "spell registered");
        ctx.assertTrue(MightyPushCast.CHARGE_TICKS >= 60, "at least a three second cast");
        ctx.complete();
    }

    /**
     * Its own batch: a 30 block barrier reaches well into the neighbouring tests, which run at the same time as the
     * rest of their batch.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 60, batchId = "mighty_push")
    public void carriesWhatItCanAndCrushesWhatItCant(TestContext ctx) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
        // A wall right behind the zombie: nowhere to go
        for (int x = 0; x < 3; x++) for (int y = 1; y < 4; y++) ctx.setBlockState(new BlockPos(x, y, 7), Blocks.STONE);
        ZombieEntity pinned = ctx.spawnEntity(EntityType.ZOMBIE, new BlockPos(1, 1, 6));
        IronGolemEntity free = ctx.spawnEntity(EntityType.IRON_GOLEM, new BlockPos(5, 1, 3));
        ServerPlayerEntity p = caster(ctx, new BlockPos(1, 1, 1));

        MightyPushCast.begin(p, false);
        MightyPushCast cast = MightyPushCast.of(p);
        ctx.assertTrue(cast != null, "casting");
        ctx.assertFalse(Spells.get(SpellId.MIGHTY_PUSH).cast(ctx.getWorld(), p, net.minecraft.item.ItemStack.EMPTY), "one at a time");

        ctx.waitAndRun(1, () -> {
            // Run it by hand (the test server doesn't reliably tick everything every tick), the mobs moving along with it
            for (int i = 0; i < MightyPushCast.CHARGE_TICKS; i++) cast.tick();
            ctx.assertTrue(free.getHealth() == free.getMaxHealth(), "nothing happens while it charges");
            Vec3d kick = null;
            for (int i = 0; i < MightyPushCast.PUSH_TICKS; i++) {
                cast.tick();
                if (kick == null && free.getHealth() < free.getMaxHealth()) kick = free.getVelocity();
                if (pinned.isAlive()) pinned.tick();
                free.tick();
            }
            ctx.assertTrue(kick != null, "the barrier reached the golem");
            Vec3d away = free.getPos().subtract(p.getPos()).multiply(1, 0, 1).normalize();
            ctx.assertTrue(kick.multiply(1, 0, 1).dotProduct(away) > 0.8, "carried away from the caster: " + kick);
            ctx.assertTrue(free.getMaxHealth() - free.getHealth() >= MightyPushCast.HIT_DAMAGE - 0.01f, "hit hard");
            ctx.assertTrue(!pinned.isAlive() || pinned.getMaxHealth() - pinned.getHealth() >= 15f, "the pinned zombie was crushed");
            ctx.assertTrue(p.getHealth() == p.getMaxHealth(), "never hurts the caster");
            ctx.assertTrue(ctx.getBlockState(new BlockPos(1, 2, 7)).isOf(Blocks.STONE), "breaks no blocks");

            while (cast.tick()) { /* let it finish */ }
            ctx.assertTrue(MightyPushCast.of(p) == null, "over");
            p.discard();
            free.discard();
            if (pinned.isAlive()) pinned.discard();
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "mighty_push")
    public void airborneCasterHangsThenDriftsDown(TestContext ctx) {
        ServerPlayerEntity p = caster(ctx, new BlockPos(3, 2, 3));
        MightyPushCast.begin(p, true);
        MightyPushCast cast = MightyPushCast.of(p);
        ctx.assertTrue(p.hasNoGravity(), "hangs in the air while casting");
        cast.tick();
        ctx.assertTrue(p.getVelocity().y > 0, "rises");
        while (cast.tick()) {
            ctx.assertTrue(p.hasNoGravity(), "still hanging there");
        }
        ctx.assertFalse(p.hasNoGravity(), "gravity back once it's over");
        ctx.assertTrue(p.hasStatusEffect(StatusEffects.SLOW_FALLING), "and a gentle way down");
        p.discard();
        ctx.complete();
    }
}
