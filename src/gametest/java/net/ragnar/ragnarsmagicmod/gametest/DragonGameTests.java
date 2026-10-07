package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.ragnar.ragnarsmagicmod.dragon.Dragon;
import net.ragnar.ragnarsmagicmod.dragon.DragonMissileEntity;
import net.ragnar.ragnarsmagicmod.dragon.DragonSpell;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;

import java.util.List;

/** Tome of the Dragon: a piloted little dragon that goes off on contact or after five seconds, its pilot safe meanwhile. */
public class DragonGameTests implements FabricGameTest {

    private static ServerPlayerEntity pilot(TestContext ctx, BlockPos rel, float pitch) {
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(rel));
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, pitch);
        return p;
    }

    /** Whether damage to {@code p} would be let through right now. */
    private static boolean canHurt(TestContext ctx, ServerPlayerEntity p) {
        return net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DAMAGE.invoker()
                .allowDamage(p, ctx.getWorld().getDamageSources().generic(), 6f);
    }

    private static List<DragonMissileEntity> dragons(TestContext ctx, ServerPlayerEntity p) {
        return ctx.getWorld().getEntitiesByClass(DragonMissileEntity.class, new Box(p.getBlockPos()).expand(160), d -> true);
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void tomeIsAnAdvancedTomeWithTheRightNumbers(TestContext ctx) {
        ctx.assertEquals(TomeTier.ADVANCED, Dragon.TOME_OF_THE_DRAGON.getTier(), "tier");
        ctx.assertEquals(16, Dragon.TOME_OF_THE_DRAGON.getXpCost(), "xp");
        ctx.assertEquals(400, Dragon.TOME_OF_THE_DRAGON.getCooldown(), "cooldown");
        ctx.assertTrue(Spells.get(SpellId.DRAGON) instanceof DragonSpell, "spell registered");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void directHitDoesTwentyAndThePilotIsSafe(TestContext ctx) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
        IronGolemEntity target = ctx.spawnEntity(EntityType.IRON_GOLEM, new BlockPos(3, 1, 5));
        target.setAiDisabled(true);
        ZombieEntity bystander = ctx.spawnEntity(EntityType.ZOMBIE, new BlockPos(6, 1, 5));
        bystander.setAiDisabled(true);
        ServerPlayerEntity p = pilot(ctx, new BlockPos(3, 1, 0), 0f);

        ctx.assertTrue(Spells.get(SpellId.DRAGON).cast(ctx.getWorld(), p, ItemStack.EMPTY), "cast");
        ctx.assertTrue(Dragon.isPiloting(p), "flying it");
        ctx.assertFalse(Spells.get(SpellId.DRAGON).cast(ctx.getWorld(), p, ItemStack.EMPTY), "one at a time");
        ctx.assertFalse(canHurt(ctx, p), "can't be hurt while flying");

        List<DragonMissileEntity> found = dragons(ctx, p);
        ctx.assertTrue(found.size() == 1, "dragon out");
        DragonMissileEntity dragon = found.get(0);
        for (int i = 0; i < 40 && !dragon.isRemoved(); i++) dragon.tick();

        ctx.assertTrue(dragon.isRemoved(), "went off on contact");
        float golemLost = target.getMaxHealth() - target.getHealth();
        ctx.assertTrue(golemLost >= DragonMissileEntity.DIRECT_DAMAGE - 0.01f, "direct hit does 20, took " + golemLost);
        float zombieLost = bystander.getMaxHealth() - bystander.getHealth();
        ctx.assertTrue(zombieLost > 0 && zombieLost < DragonMissileEntity.DIRECT_DAMAGE, "caught in the blast for less, took " + zombieLost);
        ctx.assertFalse(Dragon.isPiloting(p), "back in your body");
        ctx.assertTrue(canHurt(ctx, p), "can be hurt again once it's over");
        p.discard();
        target.discard();
        bystander.discard();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void goesOffAfterFiveSecondsInTheAir(TestContext ctx) {
        ServerPlayerEntity p = pilot(ctx, new BlockPos(3, 1, 3), -30f);
        ctx.assertTrue(Spells.get(SpellId.DRAGON).cast(ctx.getWorld(), p, ItemStack.EMPTY), "cast");
        DragonMissileEntity dragon = dragons(ctx, p).get(0);
        // Held in mid-air (the test area is too small to fly around in for five seconds)
        Vec3d hold = dragon.getPos();
        for (int i = 0; i < DragonMissileEntity.LIFETIME - 1; i++) {
            dragon.setPosition(hold);
            dragon.setVelocity(Vec3d.ZERO);
            dragon.tick();
        }
        ctx.assertFalse(dragon.isRemoved(), "still flying just before five seconds");
        ctx.assertTrue(Dragon.isPiloting(p), "still flying it");
        dragon.setPosition(hold);
        dragon.setVelocity(Vec3d.ZERO);
        dragon.tick();
        dragon.tick();
        ctx.assertTrue(dragon.isRemoved(), "went off at five seconds");
        ctx.assertFalse(Dragon.isPiloting(p), "back in your body");
        p.discard();
        ctx.complete();
    }
}
