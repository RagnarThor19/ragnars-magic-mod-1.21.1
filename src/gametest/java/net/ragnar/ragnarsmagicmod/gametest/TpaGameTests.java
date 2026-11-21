package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOffers;
import net.minecraft.village.VillagerProfession;
import net.ragnar.ragnarsmagicmod.item.custom.PassiveTomes;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.tpa.Tpa;
import net.ragnar.ragnarsmagicmod.wizard.WizardTrades;

/** Tome of TPA: only its holder can ask, anyone can accept, and only librarians sell it. */
public class TpaGameTests implements FabricGameTest {

    private static ServerPlayerEntity player(TestContext ctx, BlockPos at) {
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.getInventory().clear();
        Vec3d pos = ctx.getAbsolute(Vec3d.ofBottomCenter(at));
        p.refreshPositionAndAngles(pos.x, pos.y, pos.z, 0f, 0f);
        return p;
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "passive_tpa")
    public void onlyTheHolderCanAskAndAcceptingTeleports(TestContext ctx) {
        ServerPlayerEntity asker = player(ctx, new BlockPos(0, 1, 0));
        ServerPlayerEntity host = player(ctx, new BlockPos(4, 1, 4));

        ctx.assertTrue(Tpa.request(asker, host) != null, "no request without the tome");
        ctx.assertFalse(Tpa.pending(asker, host), "nothing pending");
        ctx.assertTrue(Tpa.accept(host) != null, "nothing to accept");

        asker.getInventory().setStack(9, new ItemStack(Tpa.TOME_OF_TPA));
        ctx.assertTrue(Tpa.request(asker, asker) != null, "not to yourself");
        ctx.assertTrue(Tpa.request(asker, host) == null, "the holder can ask");
        ctx.assertTrue(Tpa.pending(asker, host), "request is open");

        // The host needs no tome to accept
        ctx.assertTrue(Tpa.accept(host) == null, "accepted");
        ctx.assertTrue(asker.getPos().distanceTo(host.getPos()) < 0.01, "asker is at the host");
        ctx.assertFalse(Tpa.pending(asker, host), "request used up");
        ctx.assertTrue(Tpa.accept(host) != null, "can't accept twice");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "passive_tpa_unique")
    public void soldByLibrariansAndNowhereElse(TestContext ctx) {
        ctx.assertFalse(PassiveTomes.all().contains(Tpa.TOME_OF_TPA), "not in the loot/trade tome pool");
        for (var pool : WizardTrades.pools().values()) {
            ctx.assertFalse(pool.contains((TomeItem) Tpa.TOME_OF_TPA), "the Wandering Wizard never swaps it");
        }

        VillagerEntity villager = ctx.spawnEntity(EntityType.VILLAGER, new BlockPos(1, 1, 1));
        for (int level = 1; level <= 2; level++) {
            boolean found = false;
            for (TradeOffers.Factory f : TradeOffers.PROFESSION_TO_LEVELED_TRADE.get(VillagerProfession.LIBRARIAN).get(level)) {
                TradeOffer offer;
                try {
                    offer = f.create(villager, villager.getRandom());
                } catch (RuntimeException e) {
                    continue;
                }
                if (offer == null || !offer.getSellItem().isOf(Tpa.TOME_OF_TPA)) continue;
                found = true;
                int price = offer.getOriginalFirstBuyItem().getCount();
                ctx.assertTrue(offer.getOriginalFirstBuyItem().isOf(net.minecraft.item.Items.DIAMOND), "paid in diamonds");
                ctx.assertTrue(price >= Tpa.MIN_PRICE && price <= Tpa.MAX_PRICE, "8-15 diamonds, was " + price);
            }
            ctx.assertTrue(found, "librarian level " + level + " sells it");
        }
        ctx.complete();
    }
}
