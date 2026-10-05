package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.logs.Logs;
import net.ragnar.ragnarsmagicmod.logs.LogsSpell;

/** Tome of Logs: a hit inside the window is swapped for a log and you end up behind the attacker. */
public class LogsGameTests implements FabricGameTest {

    private static Spell spell() {
        return Spells.get(SpellId.LOGS);
    }

    private static ServerPlayerEntity player(TestContext ctx, BlockPos rel) {
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(rel));
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
        try { // a freshly joined player can't be hurt for a while; skip that so hits land straight away
            java.lang.reflect.Field f = ServerPlayerEntity.class.getDeclaredField("joinInvulnerabilityTicks");
            f.setAccessible(true);
            f.setInt(p, 0);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return p;
    }

    private static void floor(TestContext ctx) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
    }

    private static ZombieEntity attacker(TestContext ctx, BlockPos rel) {
        ZombieEntity z = ctx.spawnEntity(EntityType.ZOMBIE, rel);
        z.setAiDisabled(true);
        z.refreshPositionAndAngles(z.getX(), z.getY(), z.getZ(), 180f, 0f); // facing the player at lower z
        z.setBodyYaw(180f);
        z.setHeadYaw(180f);
        return z;
    }

    /** Lets the spawned mobs settle before anything happens. */
    private static final int JOIN_GRACE = 2;

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 40)
    public void hitIsSwappedForALog(TestContext ctx) {
        ctx.assertEquals(TomeTier.ADVANCED, Logs.TOME_OF_LOGS.getTier(), "tier");
        ctx.assertEquals(5, Logs.TOME_OF_LOGS.getXpCost(), "xp");
        ctx.assertEquals(260, Logs.TOME_OF_LOGS.getCooldown(), "cooldown");
        ctx.assertTrue(spell() instanceof LogsSpell, "spell registered");

        floor(ctx);
        ServerWorld w = ctx.getWorld();
        ZombieEntity z = attacker(ctx, new BlockPos(3, 1, 3));
        ServerPlayerEntity p = player(ctx, new BlockPos(3, 1, 1));
        Vec3d start = p.getPos();

        ctx.waitAndRun(JOIN_GRACE, () -> {
            ctx.assertTrue(spell().cast(w, p, ItemStack.EMPTY), "cast");
            ctx.assertEquals(0, spell().cooldownAfterCast(p, 260), "no cooldown on the cast itself");
            ctx.assertFalse(spell().cast(w, p, ItemStack.EMPTY), "can't stack casts");
        });
        ctx.waitAndRun(JOIN_GRACE + 5, () -> {
            float health = p.getHealth();
            p.damage(w.getDamageSources().mobAttack(z), 6f);
            ctx.assertTrue(p.getHealth() == health, "took no damage, health " + p.getHealth());
            ctx.assertTrue(p.getZ() > z.getZ() + 0.5, "behind the attacker, at " + p.getPos() + " attacker " + z.getPos());
            ctx.assertTrue(w.isSpaceEmpty(p), "not stuck in anything");
            ctx.assertTrue(p.getItemCooldownManager().isCoolingDown(Logs.TOME_OF_LOGS), "cooldown starts on the swap");
            ctx.assertTrue(!w.getEntitiesByClass(DisplayEntity.BlockDisplayEntity.class, new Box(start, start).expand(1.5), d -> true).isEmpty(),
                    "a log where they stood");

            float after = p.getHealth();
            p.timeUntilRegen = 0;
            p.damage(w.getDamageSources().mobAttack(z), 6f);
            ctx.assertTrue(p.getHealth() < after, "only the first hit is swapped");
            p.discard();
            z.discard();
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 100)
    public void windowRunsOutIntoTheCooldown(TestContext ctx) {
        floor(ctx);
        ServerWorld w = ctx.getWorld();
        ZombieEntity z = attacker(ctx, new BlockPos(3, 1, 3));
        ServerPlayerEntity p = player(ctx, new BlockPos(3, 1, 1));
        ctx.waitAndRun(JOIN_GRACE, () -> ctx.assertTrue(spell().cast(w, p, ItemStack.EMPTY), "cast"));
        ctx.waitAndRun(JOIN_GRACE + LogsSpell.WINDOW - 5, () ->
                ctx.assertFalse(p.getItemCooldownManager().isCoolingDown(Logs.TOME_OF_LOGS), "no cooldown while it's ready"));
        ctx.waitAndRun(JOIN_GRACE + LogsSpell.WINDOW + 3, () -> {
            ctx.assertTrue(p.getItemCooldownManager().isCoolingDown(Logs.TOME_OF_LOGS), "cooldown once the window ends");
            float health = p.getHealth();
            Vec3d before = p.getPos();
            p.damage(w.getDamageSources().mobAttack(z), 6f);
            ctx.assertTrue(p.getHealth() < health, "hits land normally again");
            ctx.assertTrue(p.getPos().equals(before), "no teleport");
            p.discard();
            z.discard();
            ctx.complete();
        });
    }
}
