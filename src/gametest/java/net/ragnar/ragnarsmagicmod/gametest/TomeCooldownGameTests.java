package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.world.PersistentState;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.util.TomeCooldowns;

import java.util.Map;

/** Tome cooldowns outlast leaving the world and dying; vanilla items are left alone. */
public class TomeCooldownGameTests implements FabricGameTest {

    @GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 40)
    public void readsTomeCooldownsAndPutsThemBack(TestContext ctx) {
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.getItemCooldownManager().set(ModItems.TOME_OF_HOME, 300);
        p.getItemCooldownManager().set(Items.ENDER_PEARL, 300);

        ctx.waitAndRun(10, () -> {
            p.getItemCooldownManager().update(); // mock players aren't ticked by a connection; step it by hand
            Map<Item, Integer> left = TomeCooldowns.remaining(p);
            ctx.assertTrue(left.containsKey(ModItems.TOME_OF_HOME), "tome cooldown read: " + left);
            ctx.assertFalse(left.containsKey(Items.ENDER_PEARL), "vanilla items left alone");
            ctx.assertTrue(left.get(ModItems.TOME_OF_HOME) > 290 && left.get(ModItems.TOME_OF_HOME) < 300,
                    "about 299 ticks left: " + left);

            ServerPlayerEntity fresh = ctx.createMockCreativeServerPlayerInWorld();
            ctx.assertFalse(fresh.getItemCooldownManager().isCoolingDown(ModItems.TOME_OF_HOME), "a new player starts clean");
            TomeCooldowns.restore(fresh, left);
            ctx.assertTrue(fresh.getItemCooldownManager().isCoolingDown(ModItems.TOME_OF_HOME), "put back");
            p.discard();
            fresh.discard();
            ctx.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void leavingSavesWhatsLeft(TestContext ctx) {
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        p.getItemCooldownManager().set(ModItems.TOME_OF_STORAGE, 500);
        String id = p.getUuidAsString();
        Map<Item, Integer> before = TomeCooldowns.remaining(p);

        // Leave the way a real player does: the connection closes, then the server notices
        p.networkHandler.disconnect(Text.literal("bye"));
        ctx.waitAndRun(3, () -> {
            var lookup = ctx.getWorld().getRegistryManager();
            TomeCooldowns.State state = ctx.getWorld().getServer().getOverworld().getPersistentStateManager().get(
                    new PersistentState.Type<>(TomeCooldowns.State::new, TomeCooldowns.State::fromNbt, null), "ragnarsmagicmod_cooldowns");
            ctx.assertTrue(state != null, "saved");
            NbtCompound nbt = state.writeNbt(new NbtCompound(), lookup);
            NbtCompound mine = nbt.getCompound("players").getCompound(id);
            ctx.assertTrue(mine.getInt("ragnarsmagicmod:tome_of_storage") > 490, "remaining ticks saved (before=" + before + ", saved=" + mine + ")");

            // And it reads back the same after a restart
            NbtCompound again = TomeCooldowns.State.fromNbt(nbt, lookup).writeNbt(new NbtCompound(), lookup);
            ctx.assertEquals(mine.getInt("ragnarsmagicmod:tome_of_storage"),
                    again.getCompound("players").getCompound(id).getInt("ragnarsmagicmod:tome_of_storage"), "survives a reload");
            ctx.complete();
        });
    }
}
