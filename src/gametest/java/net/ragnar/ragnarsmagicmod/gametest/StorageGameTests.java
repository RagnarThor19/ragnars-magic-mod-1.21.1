package net.ragnar.ragnarsmagicmod.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.StorageSpell;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;

import java.util.UUID;

/** Tome of Storage: a private 36-slot storage that belongs to the player, not the book. */
public class StorageGameTests implements FabricGameTest {

    private static boolean cast(TestContext ctx, ServerPlayerEntity p) {
        return Spells.get(SpellId.STORAGE).cast(ctx.getWorld(), p, ItemStack.EMPTY);
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void tomeIsABeginnerTomeWithTheRightNumbers(TestContext ctx) {
        ctx.assertEquals(TomeTier.BEGINNER, ModItems.TOME_OF_STORAGE.getTier(), "tier");
        ctx.assertEquals(2, ModItems.TOME_OF_STORAGE.getXpCost(), "xp");
        ctx.assertEquals(20, ModItems.TOME_OF_STORAGE.getCooldown(), "cooldown");
        ctx.assertTrue(Spells.get(SpellId.STORAGE) instanceof StorageSpell, "spell registered");
        ctx.assertTrue(ModItems.getTomeFor(SpellId.STORAGE, TomeTier.BEGINNER) == ModItems.TOME_OF_STORAGE, "staff lookup");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void opensYourOwnStorageAndItKeepsYourThings(TestContext ctx) {
        ServerPlayerEntity p = ctx.createMockCreativeServerPlayerInWorld();
        ServerPlayerEntity other = ctx.createMockCreativeServerPlayerInWorld();

        ctx.assertTrue(cast(ctx, p), "opens");
        ctx.assertTrue(p.currentScreenHandler instanceof GenericContainerScreenHandler, "a chest screen");
        GenericContainerScreenHandler screen = (GenericContainerScreenHandler) p.currentScreenHandler;
        ctx.assertEquals(4, screen.getRows(), "four rows");
        ctx.assertEquals(36, screen.getInventory().size(), "36 slots: more than a chest, less than a double chest");

        screen.getInventory().setStack(5, new ItemStack(Items.DIAMOND, 7));
        p.closeHandledScreen();

        // Like losing the tome and finding another: it opens the same storage
        ctx.assertTrue(cast(ctx, p), "opens again");
        ItemStack kept = ((GenericContainerScreenHandler) p.currentScreenHandler).getInventory().getStack(5);
        ctx.assertTrue(kept.isOf(Items.DIAMOND) && kept.getCount() == 7, "the diamonds are still there: " + kept);
        p.closeHandledScreen();

        // Someone else casting gets their own, empty one
        ctx.assertTrue(cast(ctx, other), "other player opens theirs");
        ctx.assertTrue(((GenericContainerScreenHandler) other.currentScreenHandler).getInventory().isEmpty(), "can't see anyone else's things");
        other.closeHandledScreen();

        p.discard();
        other.discard();
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void storageSurvivesSavingAndLoading(TestContext ctx) {
        var server = ctx.getWorld().getServer();
        var lookup = ctx.getWorld().getRegistryManager();
        UUID id = UUID.randomUUID();
        SimpleInventory inventory = StorageSpell.inventoryOf(server, id);
        ItemStack named = new ItemStack(Items.NETHERITE_SWORD);
        named.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Old Faithful"));
        inventory.setStack(0, named);
        inventory.setStack(35, new ItemStack(Items.GOLD_INGOT, 64));

        StorageSpell.State saved = server.getOverworld().getPersistentStateManager().get(
                new net.minecraft.world.PersistentState.Type<>(StorageSpell.State::new, StorageSpell.State::fromNbt, null), "ragnarsmagicmod_storage");
        ctx.assertTrue(saved != null && saved.isDirty(), "marked for saving after the change");
        NbtCompound nbt = saved.writeNbt(new NbtCompound(), lookup);
        StorageSpell.State loaded = StorageSpell.State.fromNbt(nbt, lookup);

        NbtCompound again = loaded.writeNbt(new NbtCompound(), lookup);
        ctx.assertTrue(again.getCompound("players").contains(id.toString()), "player's storage written back out");
        ItemStack first = ItemStack.fromNbtOrEmpty(lookup, again.getCompound("players").getCompound(id.toString())
                .getList("Items", 10).getCompound(0));
        ctx.assertTrue(first.isOf(Items.NETHERITE_SWORD) && "Old Faithful".equals(first.getName().getString()), "named sword kept: " + first);
        inventory.clear();
        ctx.complete();
    }
}
