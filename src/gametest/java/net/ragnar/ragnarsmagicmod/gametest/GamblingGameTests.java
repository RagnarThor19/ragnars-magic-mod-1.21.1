package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.ragnar.ragnarsmagicmod.gambling.Gambling;
import net.ragnar.ragnarsmagicmod.gambling.GamblingSpell;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;

/**
 * Tome of Gambling: the numbers, what the wheel will and won't take, that each outcome pays exactly what it says - and,
 * above all, that no way of interrupting a spin (dying, leaving, casting again, emptying your hands) ever pays out
 * twice or leaves the stake in two places. Each test runs in its own batch.
 */
public class GamblingGameTests implements FabricGameTest {
    // Slices on the wheel (see Gambling.WHEEL)
    private static final int TRIPLE = 0, LOSE = 1, DOUBLE = 2;

    private static ServerPlayerEntity gambler(TestContext ctx) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.changeGameMode(GameMode.SURVIVAL);
        Vec3d at = Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(3, 1, 3)));
        p.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f);
        p.getInventory().clear();
        ItemStack staff = new ItemStack(ModItems.NETHERITE_STAFF);
        ((StaffItem) ModItems.NETHERITE_STAFF).insertTome(staff, Gambling.TOME_OF_GAMBLING);
        p.setStackInHand(Hand.MAIN_HAND, staff);
        return p;
    }

    private static boolean spin(TestContext ctx, ServerPlayerEntity p) {
        return Spells.get(SpellId.GAMBLING).cast(ctx.getWorld(), p, p.getMainHandStack());
    }

    /** Every {@code item} the player holds, plus every one lying on the ground nearby. */
    private static int everywhere(TestContext ctx, ServerPlayerEntity p, Item item) {
        int n = 0;
        for (int i = 0; i < p.getInventory().size(); i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (s.isOf(item)) n += s.getCount();
        }
        for (ItemEntity e : ctx.getWorld().getEntitiesByClass(ItemEntity.class, new Box(p.getBlockPos()).expand(12), e -> e.getStack().isOf(item))) {
            n += e.getStack().getCount();
        }
        return n;
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "gambling_numbers")
    public void tomeIsAnAdvancedTomeWithTheRightNumbers(TestContext ctx) {
        ctx.assertEquals(TomeTier.ADVANCED, Gambling.TOME_OF_GAMBLING.getTier(), "tier");
        ctx.assertEquals(5, Gambling.TOME_OF_GAMBLING.getXpCost(), "xp");
        ctx.assertEquals(500, Gambling.TOME_OF_GAMBLING.getCooldown(), "cooldown (25 seconds)");
        ctx.assertTrue(Spells.get(SpellId.GAMBLING) instanceof GamblingSpell, "spell registered");
        ctx.assertEquals(Gambling.TOME_OF_GAMBLING, ModItems.getTomeFor(SpellId.GAMBLING, TomeTier.ADVANCED), "lookup");
        int lose = 0, dbl = 0, tri = 0;
        for (int pay : Gambling.WHEEL) {
            if (pay == 0) lose++;
            if (pay == 2) dbl++;
            if (pay == 3) tri++;
        }
        ctx.assertTrue(lose == 11 && dbl == 8 && tri == 1 && Gambling.WHEEL[TRIPLE] == 3 && Gambling.WHEEL[LOSE] == 0
                && Gambling.WHEEL[DOUBLE] == 2, "the wheel: 11 lose, 8 double, 1 triple");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "gambling_refuse")
    public void theWheelOnlyTakesPlainStackableThings(TestContext ctx) {
        ServerPlayerEntity p = gambler(ctx);
        ctx.assertFalse(spin(ctx, p), "nothing to bet");
        for (ItemStack bad : new ItemStack[]{new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.SHULKER_BOX),
                new ItemStack(Items.BUNDLE), new ItemStack(Items.NETHER_STAR, 3), new ItemStack(Items.TOTEM_OF_UNDYING),
                new ItemStack(Items.ELYTRA), new ItemStack(Items.BEACON)}) {
            p.setStackInHand(Hand.OFF_HAND, bad.copy());
            ctx.assertFalse(spin(ctx, p), "refuses " + bad);
            ctx.assertTrue(ItemStack.areEqual(p.getOffHandStack(), bad), "and leaves it in your hand: " + bad);
        }
        ctx.assertFalse(Gambling.isSpinning(p), "no spin started");
        ctx.complete();
    }

    private void outcome(TestContext ctx, int slice, int bet, int expected, String what) {
        ServerPlayerEntity p = gambler(ctx);
        p.setStackInHand(Hand.OFF_HAND, new ItemStack(Items.DIAMOND, bet));
        Gambling.forcedSlice = slice;
        ctx.assertTrue(spin(ctx, p), "spins");
        ctx.assertTrue(p.getOffHandStack().isEmpty(), "the stake leaves your hand at once");
        ctx.assertEquals(0, everywhere(ctx, p, Items.DIAMOND), "while it spins the stake is nowhere but on the wheel");
        ctx.runAtTick(Gambling.SPIN_TICKS - 2, () -> ctx.assertEquals(0, everywhere(ctx, p, Items.DIAMOND), "nothing paid before it stops"));
        ctx.runAtTick(Gambling.SPIN_TICKS + 3, () -> {
            ctx.assertEquals(expected, everywhere(ctx, p, Items.DIAMOND), what);
            ctx.assertFalse(Gambling.isSpinning(p), "done");
        });
        ctx.runAtTick(Gambling.SPIN_TICKS + 40, () -> {
            ctx.assertEquals(expected, everywhere(ctx, p, Items.DIAMOND), what + ", and nothing more later");
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "gambling_double", tickLimit = 200)
    public void doublePaysTwiceTheStake(TestContext ctx) {
        outcome(ctx, DOUBLE, 10, 20, "10 diamonds doubled");
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "gambling_lose", tickLimit = 200)
    public void losingTakesTheStake(TestContext ctx) {
        outcome(ctx, LOSE, 10, 0, "10 diamonds lost");
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "gambling_triple", tickLimit = 200)
    public void tripleSplitsIntoFullStacks(TestContext ctx) {
        outcome(ctx, TRIPLE, 64, 192, "64 diamonds tripled, as three stacks");
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "gambling_twice", tickLimit = 200)
    public void cantSpinAgainWhileItTurns(TestContext ctx) {
        ServerPlayerEntity p = gambler(ctx);
        p.setStackInHand(Hand.OFF_HAND, new ItemStack(Items.DIAMOND, 5));
        Gambling.forcedSlice = DOUBLE;
        ctx.assertTrue(spin(ctx, p), "first spin");
        p.setStackInHand(Hand.OFF_HAND, new ItemStack(Items.EMERALD, 5));
        ctx.assertFalse(spin(ctx, p), "second spin refused while the first turns");
        ctx.assertEquals(5, p.getOffHandStack().getCount(), "the emeralds stay put");
        ctx.runAtTick(Gambling.SPIN_TICKS + 5, () -> {
            ctx.assertEquals(10, everywhere(ctx, p, Items.DIAMOND), "the first spin pays once");
            ctx.assertEquals(5, everywhere(ctx, p, Items.EMERALD), "the emeralds were never bet");
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "gambling_death", tickLimit = 200)
    public void dyingMidSpinDropsTheWinningsOnce(TestContext ctx) {
        ServerPlayerEntity p = gambler(ctx);
        boolean keep = ctx.getWorld().getGameRules().getBoolean(GameRules.KEEP_INVENTORY);
        p.setStackInHand(Hand.OFF_HAND, new ItemStack(Items.DIAMOND, 8));
        Gambling.forcedSlice = DOUBLE;
        ctx.assertTrue(spin(ctx, p), "spins");
        ctx.runAtTick(20, () -> {
            p.damage(ctx.getWorld().getDamageSources().genericKill(), 1000f);
            ctx.assertTrue(p.isDead(), "died");
            ctx.assertFalse(Gambling.isSpinning(p), "dying settles the spin");
            int n = everywhere(ctx, p, Items.DIAMOND);
            ctx.assertEquals(16, n, "the winnings fall with you (keepInventory=" + keep + ")");
        });
        ctx.runAtTick(Gambling.SPIN_TICKS + 20, () -> {
            ctx.assertEquals(16, everywhere(ctx, p, Items.DIAMOND), "the wheel stopping later pays nothing more");
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "gambling_leave", tickLimit = 200)
    public void leavingMidSpinSettlesIntoTheSavedInventoryOnce(TestContext ctx) {
        ServerPlayerEntity p = gambler(ctx);
        p.setStackInHand(Hand.OFF_HAND, new ItemStack(Items.DIAMOND, 8));
        Gambling.forcedSlice = DOUBLE;
        ctx.assertTrue(spin(ctx, p), "spins");
        ctx.runAtTick(20, () -> {
            // The real way out: the player manager removing (and saving) the player
            ctx.getWorld().getServer().getPlayerManager().remove(p);
            ctx.assertFalse(Gambling.isSpinning(p), "leaving settles the spin");
            ctx.assertEquals(16, everywhere(ctx, p, Items.DIAMOND), "paid into the inventory before it was saved");
        });
        ctx.runAtTick(Gambling.SPIN_TICKS + 20, () -> {
            ctx.assertEquals(16, everywhere(ctx, p, Items.DIAMOND), "and never again");
            ctx.complete();
        });
    }

    /** Music discs, tomes and enchanted books go on the wheel too, and come back as exact copies. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "gambling_singles", tickLimit = 400)
    public void discsTomesAndEnchantedBooksCanBeBet(TestContext ctx) {
        ServerPlayerEntity p = gambler(ctx);
        ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
        book.set(net.minecraft.component.DataComponentTypes.STORED_ENCHANTMENTS, net.minecraft.enchantment.EnchantmentHelper
                .getEnchantments(EnchantedBookHelper.mending(ctx)));
        ItemStack disc = new ItemStack(Items.MUSIC_DISC_PIGSTEP);
        ItemStack tome = new ItemStack(net.ragnar.ragnarsmagicmod.astral.AstralProjection.TOME_OF_ASTRAL_PROJECTION);

        // The enchanted book, doubled: two identical books
        p.setStackInHand(Hand.OFF_HAND, book.copy());
        Gambling.forcedSlice = DOUBLE;
        ctx.assertTrue(spin(ctx, p), "takes an enchanted book");
        ctx.assertTrue(p.getOffHandStack().isEmpty(), "the book leaves your hand");
        ctx.runAtTick(Gambling.SPIN_TICKS + 3, () -> {
            int books = 0;
            for (int i = 0; i < p.getInventory().size(); i++) {
                ItemStack s = p.getInventory().getStack(i);
                if (s.isOf(Items.ENCHANTED_BOOK)) {
                    ctx.assertTrue(ItemStack.areItemsAndComponentsEqual(s, book), "an exact copy, enchantments and all");
                    books += s.getCount();
                }
            }
            ctx.assertEquals(2, books, "two books");
            // The disc, tripled
            p.getItemCooldownManager().remove(Gambling.TOME_OF_GAMBLING);
            ctx.assertTrue(p.getOffHandStack().isOf(Items.ENCHANTED_BOOK), "the first book comes back to your hand");
            p.getInventory().insertStack(p.getOffHandStack().copy());
            p.setStackInHand(Hand.OFF_HAND, disc.copy());
            Gambling.forcedSlice = TRIPLE;
            ctx.assertTrue(spin(ctx, p), "takes a music disc");
        });
        ctx.runAtTick(2 * Gambling.SPIN_TICKS + 8, () -> {
            ctx.assertEquals(3, everywhere(ctx, p, Items.MUSIC_DISC_PIGSTEP), "three discs");
            // A tome, lost
            p.getItemCooldownManager().remove(Gambling.TOME_OF_GAMBLING);
            p.getInventory().insertStack(p.getOffHandStack().copy());
            p.setStackInHand(Hand.OFF_HAND, tome.copy());
            Gambling.forcedSlice = LOSE;
            ctx.assertTrue(spin(ctx, p), "takes a tome");
            ctx.assertTrue(p.getOffHandStack().isEmpty(), "the tome leaves your hand");
        });
        ctx.runAtTick(3 * Gambling.SPIN_TICKS + 14, () -> {
            ctx.assertEquals(0, everywhere(ctx, p, tome.getItem()), "the tome is gone");
            ctx.assertEquals(2, everywhere(ctx, p, Items.ENCHANTED_BOOK), "the books are untouched");
            ctx.complete();
        });
    }

    /** An enchanted book's enchantments, borrowed from a book made the vanilla way. */
    private static final class EnchantedBookHelper {
        static ItemStack mending(TestContext ctx) {
            var reg = ctx.getWorld().getRegistryManager().get(net.minecraft.registry.RegistryKeys.ENCHANTMENT);
            var mending = reg.getEntry(net.minecraft.enchantment.Enchantments.MENDING).orElseThrow();
            return net.minecraft.item.EnchantedBookItem.forEnchantment(new net.minecraft.enchantment.EnchantmentLevelEntry(mending, 1));
        }
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "gambling_fullbag", tickLimit = 200)
    public void aFullInventorySpillsTheRestAtYourFeet(TestContext ctx) {
        ServerPlayerEntity p = gambler(ctx);
        for (int i = 1; i < 36; i++) p.getInventory().setStack(i, new ItemStack(Items.COBBLESTONE, 64));
        p.setStackInHand(Hand.OFF_HAND, new ItemStack(Items.DIAMOND, 64));
        Gambling.forcedSlice = TRIPLE;
        ctx.assertTrue(spin(ctx, p), "spins");
        ctx.runAtTick(Gambling.SPIN_TICKS + 5, () -> {
            ctx.assertEquals(64, p.getOffHandStack().getCount(), "one stack back in your hand");
            ctx.assertEquals(192, everywhere(ctx, p, Items.DIAMOND), "the other two on the ground - none lost, none extra");
            ctx.complete();
        });
    }
}
