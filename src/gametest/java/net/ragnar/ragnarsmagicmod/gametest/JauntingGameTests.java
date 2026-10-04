package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;
import net.ragnar.ragnarsmagicmod.jaunting.Jaunting;
import net.ragnar.ragnarsmagicmod.jaunting.JauntingKunaiEntity;
import net.ragnar.ragnarsmagicmod.jaunting.JauntingMarks;
import net.ragnar.ragnarsmagicmod.jaunting.JauntingSpell;

import java.util.List;

/** Tome of Jaunting: throw the kunai, flash to it wherever it is, or dispel it. */
public class JauntingGameTests implements FabricGameTest {

    private static Spell spell() {
        return Spells.get(SpellId.JAUNTING);
    }

    private static ServerPlayerEntity player(TestContext ctx, BlockPos rel, float yaw, float pitch) {
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(rel));
        p.refreshPositionAndAngles(at.x, at.y, at.z, yaw, pitch);
        return p;
    }

    private static void floor(TestContext ctx) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
    }

    private static List<JauntingKunaiEntity> kunais(TestContext ctx, ServerPlayerEntity p) {
        return ctx.getWorld().getEntitiesByClass(JauntingKunaiEntity.class, new Box(p.getBlockPos()).expand(96),
                k -> p.getUuid().equals(k.getOwnerId()));
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 80)
    public void throwsAndJauntsToTheWall(TestContext ctx) {
        ctx.assertEquals(TomeTier.ADVANCED, Jaunting.TOME_OF_JAUNTING.getTier(), "tier");
        ctx.assertEquals(8, Jaunting.TOME_OF_JAUNTING.getXpCost(), "xp");
        ctx.assertEquals(300, Jaunting.TOME_OF_JAUNTING.getCooldown(), "cooldown");
        ctx.assertTrue(spell() instanceof JauntingSpell, "spell registered");

        floor(ctx);
        for (int x = 0; x < 8; x++) for (int y = 1; y < 5; y++) ctx.setBlockState(new BlockPos(x, y, 7), Blocks.STONE);
        ServerPlayerEntity p = player(ctx, new BlockPos(3, 1, 1), 0f, 0f); // facing the wall
        ServerWorld w = ctx.getWorld();

        ctx.assertEquals(8, spell().xpCost(p, 8), "throwing costs XP");
        ctx.assertTrue(spell().cast(w, p, ItemStack.EMPTY), "throw");
        ctx.assertEquals(300, spell().cooldownAfterCast(p, 300), "throwing starts the cooldown");
        ctx.assertTrue(kunais(ctx, p).size() == 1, "one kunai out");
        ctx.assertTrue(JauntingMarks.get(w.getServer(), p.getUuid()) != null, "marked");
        ctx.assertTrue(spell().ignoresCooldown(p), "can jaunt during the cooldown");
        ctx.assertEquals(0, spell().xpCost(p, 8), "jaunting is free");

        ctx.waitAndRun(10, () -> {
            List<JauntingKunaiEntity> ks = kunais(ctx, p);
            ctx.assertTrue(ks.size() == 1 && !ks.get(0).isFlying(), "stuck in the wall");
            double wallZ = ctx.getAbsolutePos(new BlockPos(0, 0, 7)).getZ();
            ctx.assertTrue(Math.abs(ks.get(0).getZ() - wallZ) < 0.3, "at the wall face, z=" + ks.get(0).getZ());

            ctx.assertTrue(spell().cast(w, p, ItemStack.EMPTY), "jaunt");
            ctx.assertEquals(0, spell().cooldownAfterCast(p, 300), "jaunting doesn't restart the cooldown");
            ctx.assertTrue(p.getZ() > wallZ - 1.2 && p.getZ() < wallZ, "standing at the wall, z=" + p.getZ() + " wall " + wallZ);
            ctx.assertTrue(w.isSpaceEmpty(p), "not stuck in the wall");
            ctx.assertTrue(JauntingMarks.get(w.getServer(), p.getUuid()) == null, "mark used up");
            ctx.waitAndRun(1, () -> {
                ctx.assertTrue(kunais(ctx, p).isEmpty(), "kunai gone");
                p.discard();
                ctx.complete();
            });
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 80)
    public void sticksInAMobAndRidesAlong(TestContext ctx) {
        floor(ctx);
        ServerWorld w = ctx.getWorld();
        CowEntity cow = ctx.spawnEntity(EntityType.COW, new BlockPos(3, 1, 5));
        cow.setAiDisabled(true);
        ServerPlayerEntity p = player(ctx, new BlockPos(3, 1, 1), 0f, 8f);
        ctx.assertTrue(spell().cast(w, p, ItemStack.EMPTY), "throw");

        ctx.waitAndRun(5, () -> {
            List<JauntingKunaiEntity> ks = kunais(ctx, p);
            ctx.assertTrue(ks.size() == 1, "kunai still there");
            JauntingKunaiEntity k = ks.get(0);
            ctx.assertTrue(k.host() == cow, "stuck in the cow");
            ctx.assertTrue(Math.abs(cow.getHealth() - (cow.getMaxHealth() - JauntingKunaiEntity.DAMAGE)) < 0.01,
                    "8 damage, health " + cow.getHealth());

            Vec3d before = k.getPos().subtract(cow.getPos());
            cow.setPosition(cow.getX() + 2, cow.getY(), cow.getZ());
            ctx.waitAndRun(2, () -> {
                Vec3d after = k.getPos().subtract(cow.getPos());
                ctx.assertTrue(after.distanceTo(before) < 0.05, "rode along with it");

                ctx.assertTrue(spell().cast(w, p, ItemStack.EMPTY), "jaunt");
                ctx.assertTrue(p.getPos().distanceTo(cow.getPos()) < 1.0, "landed in the cow, " + p.getPos().distanceTo(cow.getPos()));
                p.discard();
                cow.discard();
                ctx.complete();
            });
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 40)
    public void shiftDispels(TestContext ctx) {
        floor(ctx);
        ServerWorld w = ctx.getWorld();
        ServerPlayerEntity p = player(ctx, new BlockPos(3, 1, 1), 0f, 60f); // into the floor
        Vec3d start = p.getPos();
        ctx.assertTrue(spell().cast(w, p, ItemStack.EMPTY), "throw");
        ctx.waitAndRun(4, () -> {
            p.setSneaking(true);
            ctx.assertEquals(0, spell().xpCost(p, 8), "dispelling is free");
            ctx.assertTrue(spell().cast(w, p, ItemStack.EMPTY), "dispel");
            ctx.assertTrue(p.getPos().distanceTo(start) < 0.01, "didn't move");
            ctx.assertTrue(JauntingMarks.get(w.getServer(), p.getUuid()) == null, "mark gone");
            ctx.assertTrue(!spell().cast(w, p, ItemStack.EMPTY), "nothing left to dispel");
            ctx.waitAndRun(1, () -> {
                ctx.assertTrue(kunais(ctx, p).isEmpty(), "kunai gone");
                p.discard();
                ctx.complete();
            });
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 80)
    public void fliesStraightThenDropsAndCanBeCaughtMidAir(TestContext ctx) {
        ServerWorld w = ctx.getWorld();
        // High above the test area (it is boxed in), aimed a little up
        ServerPlayerEntity p = player(ctx, new BlockPos(3, 40, 1), 0f, -10f);
        ChunkPos c = new ChunkPos(p.getBlockPos());
        for (int dz = 0; dz <= 9; dz++) w.setChunkForced(c.x, c.z + dz, true);
        Vec3d start = p.getEyePos();
        Vec3d line = p.getRotationVector();
        ctx.assertTrue(spell().cast(w, p, ItemStack.EMPTY), "throw");
        JauntingKunaiEntity k = kunais(ctx, p).get(0);

        ctx.waitAndRun(11, () -> {
            Vec3d off = k.getPos().subtract(start);
            double run = off.dotProduct(line), wander = off.subtract(line.multiply(run)).length();
            ctx.assertTrue(run > 30, "far and fast, " + run + " blocks");
            ctx.assertTrue(wander < 0.2, "still dead straight, off the line by " + wander);
            ctx.waitAndRun(20, () -> {
                Vec3d off2 = k.getPos().subtract(start);
                double below = line.multiply(off2.dotProduct(line)).y - off2.y;
                ctx.assertTrue(below > 0.8, "then it curves down, " + below + " below the line");
                ctx.assertTrue(k.isFlying(), "still in the air");

                Vec3d at = k.getPos();
                ctx.assertTrue(spell().cast(w, p, ItemStack.EMPTY), "jaunt mid-air");
                ctx.assertTrue(p.getPos().distanceTo(at) < 2.0, "caught it, " + p.getPos().distanceTo(at));
                ctx.assertTrue(p.getVelocity().distanceTo(k.getVelocity()) < 0.01 && p.getVelocity().z > 1.5,
                        "kept its speed, " + p.getVelocity() + " vs " + k.getVelocity());
                for (int dz = 0; dz <= 9; dz++) w.setChunkForced(c.x, c.z + dz, false);
                p.discard();
                ctx.complete();
            });
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 60)
    public void jauntsToAKunaiLeftInAnUnloadedChunk(TestContext ctx) {
        floor(ctx);
        ServerWorld w = ctx.getWorld();
        ServerPlayerEntity p = player(ctx, new BlockPos(3, 1, 1), 0f, 45f); // into the floor a couple of blocks out
        ctx.assertTrue(spell().cast(w, p, ItemStack.EMPTY), "throw");
        ctx.waitAndRun(6, () -> {
            JauntingKunaiEntity k = kunais(ctx, p).get(0);
            ctx.assertTrue(!k.isFlying(), "stuck in the floor");
            Vec3d where = k.getPos();

            // Saved and loaded back, it's still the owner's kunai
            NbtCompound nbt = new NbtCompound();
            k.writeNbt(nbt);
            JauntingKunaiEntity copy = new JauntingKunaiEntity(Jaunting.KUNAI, w);
            copy.readNbt(nbt);
            ctx.assertTrue(p.getUuid().equals(copy.getOwnerId()) && copy.getUuid().equals(k.getUuid()), "owner survives saving");

            // Unloaded with its chunk (the owner wandered off): the mark still knows where it is
            k.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            ctx.assertTrue(JauntingMarks.get(w.getServer(), p.getUuid()) != null, "mark kept while unloaded");
            p.refreshPositionAndAngles(p.getX() + 5, p.getY() + 3, p.getZ(), 0f, 0f);
            ctx.assertTrue(spell().cast(w, p, ItemStack.EMPTY), "jaunt");
            ctx.assertTrue(p.getPos().distanceTo(where) < 1.5, "back at it, " + p.getPos().distanceTo(where));
            ctx.assertTrue(w.isSpaceEmpty(p), "not stuck in the floor");
            ctx.assertTrue(JauntingMarks.get(w.getServer(), p.getUuid()) == null, "mark used up");
            p.discard();
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 40)
    public void onlyOneKunaiAtATime(TestContext ctx) {
        floor(ctx);
        ServerWorld w = ctx.getWorld();
        ServerPlayerEntity p = player(ctx, new BlockPos(3, 1, 1), 0f, 60f);
        ctx.assertTrue(spell().cast(w, p, ItemStack.EMPTY), "throw");
        JauntingKunaiEntity first = kunais(ctx, p).get(0);
        // A stray copy (say, one left in a chunk from before) goes away on its own
        JauntingKunaiEntity stray = new JauntingKunaiEntity(w, p);
        stray.setPosition(first.getPos().add(1, 0.5, 0));
        w.spawnEntity(stray);
        ctx.waitAndRun(3, () -> {
            ctx.assertTrue(stray.isRemoved(), "stray gone");
            ctx.assertTrue(!first.isRemoved(), "the real one stays");
            ctx.assertTrue(JauntingMarks.get(w.getServer(), p.getUuid()).kunai().equals(first.getUuid()), "mark untouched");
            p.setSneaking(true);
            spell().cast(w, p, ItemStack.EMPTY);
            p.discard();
            ctx.complete();
        });
    }
}
