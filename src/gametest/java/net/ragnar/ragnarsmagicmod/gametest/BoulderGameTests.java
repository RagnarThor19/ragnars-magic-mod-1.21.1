package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.ragnar.ragnarsmagicmod.boulders.BoulderEntity;
import net.ragnar.ragnarsmagicmod.boulders.BoulderSpell;
import net.ragnar.ragnarsmagicmod.boulders.Boulders;
import net.ragnar.ragnarsmagicmod.entity.RockEntity;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.RocksSpell;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;

/** Tome of Rocks (the old Tome of Boulders) and the new Tome of Boulders. */
public class BoulderGameTests implements FabricGameTest {

    /** Keeps the test's chunks ticking whatever its neighbours do (see BallLightningGameTests). */
    private static void forceChunks(TestContext ctx) {
        BlockPos a = ctx.getAbsolutePos(new BlockPos(0, 0, 0)), b = ctx.getAbsolutePos(new BlockPos(7, 0, 7));
        for (int cx = Math.min(a.getX(), b.getX()) >> 4; cx <= Math.max(a.getX(), b.getX()) >> 4; cx++) {
            for (int cz = Math.min(a.getZ(), b.getZ()) >> 4; cz <= Math.max(a.getZ(), b.getZ()) >> 4; cz++) {
                ctx.getWorld().setChunkForced(cx, cz, true);
            }
        }
    }

    private static ZombieEntity zombie(TestContext ctx, BlockPos rel) {
        ZombieEntity z = ctx.spawnEntity(EntityType.ZOMBIE, rel);
        z.setAiDisabled(true);
        return z;
    }

    private static ServerPlayerEntity caster(TestContext ctx, BlockPos rel, float pitch) {
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(rel));
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, pitch);
        p.setHeadYaw(0f);
        return p;
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void tomesAreSetUp(TestContext ctx) {
        TomeItem rocks = (TomeItem) ModItems.TOME_OF_ROCKS;
        ctx.assertEquals(TomeTier.BEGINNER, rocks.getTier(), "rocks tier");
        ctx.assertTrue(Spells.get(SpellId.ROCKS) instanceof RocksSpell, "rocks spell");
        ctx.assertEquals(rocks, ModItems.getTomeFor(SpellId.ROCKS, TomeTier.BEGINNER), "rocks lookup");

        TomeItem boulders = Boulders.TOME_OF_BOULDERS;
        ctx.assertEquals(TomeTier.ADVANCED, boulders.getTier(), "boulders tier");
        ctx.assertEquals(14, boulders.getXpCost(), "boulders xp");
        ctx.assertEquals(280, boulders.getCooldown(), "boulders cooldown");
        ctx.assertTrue(Spells.get(SpellId.BOULDERS) instanceof BoulderSpell, "boulders spell");
        ctx.assertEquals(boulders, ModItems.getTomeFor(SpellId.BOULDERS, TomeTier.ADVANCED), "boulders lookup");

        // A staff that had the old beginner Tome of Boulders on it now holds the Tome of Rocks
        ItemStack staff = new ItemStack(ModItems.GOLDEN_STAFF);
        ((StaffItem) ModItems.GOLDEN_STAFF).insertTome(staff, rocks);
        NbtCompound nbt = staff.get(net.minecraft.component.DataComponentTypes.CUSTOM_DATA).copyNbt();
        rewriteIds(nbt, "ROCKS", "BOULDER");
        staff.set(net.minecraft.component.DataComponentTypes.CUSTOM_DATA, net.minecraft.component.type.NbtComponent.of(nbt));
        ctx.assertTrue(StaffItem.getTomes(staff).contains(rocks), "old BOULDER id reads back as the Tome of Rocks");
        ctx.complete();
    }

    /** Swaps every string {@code from} for {@code to} anywhere in {@code nbt}. */
    private static void rewriteIds(NbtCompound nbt, String from, String to) {
        for (String key : nbt.getKeys()) {
            var el = nbt.get(key);
            if (el instanceof NbtCompound c) rewriteIds(c, from, to);
            else if (el instanceof NbtList list) {
                for (var item : list) if (item instanceof NbtCompound c) rewriteIds(c, from, to);
            } else if (el != null && el.asString().equals(from)) nbt.putString(key, to);
        }
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 60)
    public void rockHitsHeadOn(TestContext ctx) {
        forceChunks(ctx);
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
        ZombieEntity target = zombie(ctx, new BlockPos(3, 1, 5));
        ServerPlayerEntity p = caster(ctx, new BlockPos(3, 1, 0), 0f);

        ctx.assertTrue(Spells.get(SpellId.ROCKS).cast(ctx.getWorld(), p, ItemStack.EMPTY), "cast");
        Box around = new Box(p.getBlockPos()).expand(32);
        ctx.assertTrue(ctx.getWorld().getEntitiesByClass(RockEntity.class, around, r -> true).size() == 1, "rock out");

        ctx.waitAndRun(15, () -> {
            for (RockEntity rock : ctx.getWorld().getEntitiesByClass(RockEntity.class, around, x -> true)) {
                for (int i = 0; i < 40 && !rock.isRemoved(); i++) rock.tick();
            }
            ctx.assertTrue(target.getHealth() <= target.getMaxHealth() - 6f,
                    "hit head on for 7 (less a zombie's bit of armour), health " + target.getHealth());
            ctx.assertTrue(ctx.getWorld().getEntitiesByClass(RockEntity.class, around, r -> true).isEmpty(), "broke");
            p.discard();
            target.discard();
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 80)
    public void boulderSlamsAndThrowsThingsAround(TestContext ctx) {
        forceChunks(ctx);
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
        for (int x = 0; x < 8; x++) for (int y = 1; y < 4; y++) ctx.setBlockState(new BlockPos(x, y, 7), Blocks.STONE);
        ZombieEntity left = zombie(ctx, new BlockPos(1, 1, 5));
        ZombieEntity right = zombie(ctx, new BlockPos(6, 1, 5));
        // Looking down at the ground a few blocks out
        ServerPlayerEntity p = caster(ctx, new BlockPos(3, 1, 0), 35f);

        ctx.assertTrue(Spells.get(SpellId.BOULDERS).cast(ctx.getWorld(), p, ItemStack.EMPTY), "cast");
        Box around = new Box(p.getBlockPos()).expand(32);
        var found = ctx.getWorld().getEntitiesByClass(BoulderEntity.class, around, b -> true);
        ctx.assertTrue(found.size() == 1, "boulder forming");
        BoulderEntity boulder = found.get(0);
        ctx.assertTrue(boulder.center().y > p.getEyeY(), "hoisted above the caster's eyes");

        ctx.waitAndRun(2, () -> {
            boolean slammed = false;
            Vec3d leftKick = Vec3d.ZERO, rightKick = Vec3d.ZERO;
            for (int i = 0; i < 200 && !boulder.isRemoved(); i++) {
                boulder.tick();
                if (!slammed && boulder.stage() == BoulderEntity.ROLLING) {
                    slammed = true;
                    leftKick = left.getVelocity();
                    rightKick = right.getVelocity();
                }
            }
            ctx.assertTrue(slammed, "came down and rolled");
            ctx.assertTrue(boulder.isRemoved(), "broke apart in the end");
            ctx.assertTrue(left.getHealth() < left.getMaxHealth() && right.getHealth() < right.getMaxHealth(),
                    "slam hurt both, health " + left.getHealth() + " / " + right.getHealth());
            ctx.assertTrue(leftKick.x < -0.3 && rightKick.x > 0.3, "thrown outwards: " + leftKick + " / " + rightKick);
            ctx.assertTrue(leftKick.y > 0.2 && rightKick.y > 0.2, "and up off their feet");
            ctx.assertTrue(p.getHealth() == p.getMaxHealth(), "never hurts the caster");
            ctx.assertTrue(ctx.getBlockState(new BlockPos(3, 2, 7)).isOf(Blocks.STONE), "breaks no blocks");
            p.discard();
            left.discard();
            right.discard();
            ctx.complete();
        });
    }
}
